package app.vctrnr

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import app.vctrnr.audio.OutputLine
import app.vctrnr.audio.Pcm
import app.vctrnr.audio.SpeechDetector
import app.vctrnr.audio.TrainerEngine
import app.vctrnr.media.EarbudControls
import app.vctrnr.storage.RecordingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/** Where playback is going right now. */
sealed interface OutputRoute {
    data class Earbuds(val name: String) : OutputRoute
    data object Wired : OutputRoute
    data object Speaker : OutputRoute
}

data class UiState(
    val running: Boolean = false,
    val phase: TrainerEngine.Phase? = null,
    val levelDb: Float = SpeechDetector.SILENCE_DB,
    val thresholdDb: Float = -60f,
    val takes: Int = 0,
    val route: OutputRoute = OutputRoute.Speaker,
    val micGranted: Boolean = false,
    val micPermanentlyDenied: Boolean = false,
    val message: String? = null,
    val settings: TrainerSettings = TrainerSettings(),
)

/**
 * Owns everything audio while the app is on screen: [open] in onStart, [close] in onStop.
 * Nothing here outlives the visible activity, so closing the app turns everything off.
 * All public methods are main-thread only.
 */
class TrainerController(
    private val context: Context,
    private val requestMicPermission: () -> Unit,
) : TrainerEngine.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val store = RecordingStore(context.applicationContext)
    private val settingsStore = SettingsStore(context)

    private val sampleRate = TrainerEngine.pickSampleRate()
    private val startChime = Pcm.silence(sampleRate, 150) + Pcm.chime(sampleRate, rising = true)
    private val stopChime = Pcm.chime(sampleRate, rising = false)

    private val _state = MutableStateFlow(UiState(settings = settingsStore.load(), micGranted = hasMicPermission()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var output: OutputLine? = null
    private var engine: TrainerEngine? = null
    private var controls: EarbudControls? = null
    private var focusRequest: AudioFocusRequest? = null
    private var noisyRegistered = false
    private var startWhenPermitted = false

    // Written on main, read on the audio/save threads.
    @Volatile private var session: String? = null
    private val takeCounter = AtomicInteger(0)

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && _state.value.running) {
                stop(playChime = false)
                showMessage("Stopped: earbuds disconnected")
            }
        }
    }

    fun hasMicPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** App became visible: take over media audio and the earbud controls. */
    fun open() {
        if (output != null) return
        _state.update { it.copy(micGranted = hasMicPermission()) }

        // Session first, then audio: Android hands earbud taps to the app that has a media
        // session *and* most recently started playing.
        controls = EarbudControls(context, main, onToggle = ::toggle, onStopRequested = { stop() })
        requestFocus()

        val out = OutputLine(sampleRate, main, onRouteChanged = ::updateRoute)
        if (!out.start()) showMessage("Couldn't open audio output")
        output = out
        engine = TrainerEngine(context.applicationContext, out, this).also { it.config = detectorConfig(_state.value.settings) }
        updateRoute(out.routedDevice)

        ContextCompat.registerReceiver(
            context,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyRegistered = true
    }

    /** App went to the background or was closed: stop everything and let go of audio. */
    fun close() {
        stop(playChime = false)
        controls?.release()
        controls = null
        engine = null
        output?.release()
        output = null
        abandonFocus()
        if (noisyRegistered) {
            context.unregisterReceiver(noisyReceiver)
            noisyRegistered = false
        }
    }

    fun toggle() {
        if (_state.value.running) stop() else start()
    }

    fun start() {
        val out = output ?: return
        val eng = engine ?: return
        if (eng.isRunning) return
        if (!hasMicPermission()) {
            startWhenPermitted = true
            requestMicPermission()
            return
        }
        requestFocus()
        out.reclaimMediaControls()

        session = store.newSessionName()
        takeCounter.set(0)
        out.stopSounds()
        out.play(startChime)
        eng.start()
        controls?.setRunning(true)
        _state.update { it.copy(running = true, phase = TrainerEngine.Phase.Playing, takes = 0, message = null) }
    }

    fun stop(playChime: Boolean = true) {
        val eng = engine ?: return
        if (!eng.isRunning && !_state.value.running) return
        eng.stop() // Saves (without playing) a take that was cut off mid-sentence.
        output?.let {
            it.stopSounds()
            if (playChime) it.play(stopChime)
        }
        controls?.setRunning(false)
        _state.update { it.copy(running = false, phase = null, levelDb = SpeechDetector.SILENCE_DB) }
    }

    fun updateSettings(settings: TrainerSettings) {
        settingsStore.save(settings)
        engine?.config = detectorConfig(settings)
        _state.update { it.copy(settings = settings) }
    }

    fun openRecordingsFolder() {
        if (!store.openFolder()) {
            showMessage("No file manager found. Recordings are in ${RecordingStore.DISPLAY_PATH}")
        }
    }

    fun onMicPermissionResult(granted: Boolean, permanentlyDenied: Boolean) {
        _state.update { it.copy(micGranted = granted, micPermanentlyDenied = !granted && permanentlyDenied) }
        if (granted && startWhenPermitted) start()
        startWhenPermitted = false
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // --- TrainerEngine.Listener (called on the audio thread) ---

    override fun onStatus(phase: TrainerEngine.Phase, levelDb: Float, thresholdDb: Float) {
        _state.update { if (it.running) it.copy(phase = phase, levelDb = levelDb, thresholdDb = thresholdDb) else it }
    }

    override fun onTake(pcm: ShortArray, sampleRate: Int, playedBack: Boolean) {
        val folder = session ?: return
        val number = takeCounter.incrementAndGet()
        _state.update { it.copy(takes = number) }
        store.saveTake(folder, number, pcm, sampleRate) { ok ->
            if (!ok) main.post { showMessage("Couldn't save take $number") }
        }
    }

    override fun onError(message: String) {
        main.post {
            stop(playChime = false)
            showMessage(message)
        }
    }

    // --- Audio focus: pause other media while the app is open ---

    private fun requestFocus() {
        if (focusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(OutputLine.ATTRIBUTES)
            .setOnAudioFocusChangeListener(::onFocusChange, main)
            .build()
        if (audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = request
        }
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Another app started playing for real. Pressing Start takes it back.
                focusRequest = null
                if (_state.value.running) {
                    stop(playChime = false)
                    showMessage("Stopped: another app started playing audio")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Phone call, voice assistant, etc. The mic would be taken away anyway.
                if (_state.value.running) {
                    stop(playChime = false)
                    showMessage("Stopped for a call or another app")
                }
            }
        }
    }

    // --- Output route (for the "Playing through…" chip) ---

    private fun updateRoute(device: AudioDeviceInfo?) {
        val route = describe(device) ?: fallbackRoute()
        _state.update { it.copy(route = route) }
    }

    private fun describe(device: AudioDeviceInfo?): OutputRoute? {
        if (device == null) return null
        return when (device.type) {
            in BLUETOOTH_OUTPUTS -> OutputRoute.Earbuds(device.productName?.toString().orEmpty())
            in WIRED_OUTPUTS -> OutputRoute.Wired
            else -> OutputRoute.Speaker
        }
    }

    private fun fallbackRoute(): OutputRoute {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        outputs.firstOrNull { it.type in BLUETOOTH_OUTPUTS }?.let { return OutputRoute.Earbuds(it.productName?.toString().orEmpty()) }
        if (outputs.any { it.type in WIRED_OUTPUTS }) return OutputRoute.Wired
        return OutputRoute.Speaker
    }

    private fun showMessage(text: String) = _state.update { it.copy(message = text) }

    private fun detectorConfig(s: TrainerSettings) =
        SpeechDetector.Config(sensitivity = s.sensitivity, endSilenceMs = s.pauseMs)

    private companion object {
        val BLUETOOTH_OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
        val WIRED_OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}
