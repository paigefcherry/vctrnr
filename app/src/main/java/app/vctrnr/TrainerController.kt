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
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import app.vctrnr.audio.Mic
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
    /** The mic the current/last session actually used (may differ from the setting). */
    val micInUse: Mic? = null,
    val micGranted: Boolean = false,
    val micPermanentlyDenied: Boolean = false,
    val message: String? = null,
    val settings: TrainerSettings = TrainerSettings(),
    /** vctrnr was closed (notification, Close button, or swiped away). */
    val closed: Boolean = false,
)

/**
 * Owns everything audio while vctrnr is open: [open] when the service starts, [close] when
 * you close vctrnr. Lives in [TrainerService] so it keeps working with the screen off.
 * All public methods are main-thread only.
 */
class TrainerController(
    private val context: Context,
    private val onCloseRequested: () -> Unit,
) : TrainerEngine.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val store = RecordingStore(context.applicationContext)
    private val settingsStore = SettingsStore(context)
    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vctrnr:session")
        .apply { setReferenceCounted(false) }

    private val sampleRate = TrainerEngine.pickSampleRate()
    private val startChime = Pcm.silence(sampleRate, 150) + Pcm.chime(sampleRate, rising = true)
    private val stopChime = Pcm.chime(sampleRate, rising = false)

    private val _state = MutableStateFlow(UiState(settings = settingsStore.load(), micGranted = hasMicPermission()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Set by the activity while it's on screen, so a start can ask for the mic permission. */
    var onMicPermissionNeeded: (() -> Unit)? = null

    /** Called (main thread) when the one-line status changes, to refresh the notification. */
    var onSummaryChanged: (() -> Unit)? = null

    val sessionToken: MediaSession.Token? get() = controls?.sessionToken

    private var output: OutputLine? = null
    private var engine: TrainerEngine? = null
    private var controls: EarbudControls? = null
    private var focusRequest: AudioFocusRequest? = null
    private var noisyRegistered = false
    private var startWhenPermitted = false
    private var lastSummary = ""

    // Written on main, read on the audio/save threads.
    @Volatile private var session: String? = null
    private val takeCounter = AtomicInteger(0)
    @Volatile private var lastPhase: TrainerEngine.Phase? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            // Also fires on some route switches; only stop if the headphones are really gone.
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && _state.value.running && !headphonesConnected()) {
                stop(playChime = false)
                showMessage("Stopped: earbuds disconnected")
            }
        }
    }

    fun hasMicPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** vctrnr opened: take over media audio and the earbud controls. */
    fun open() {
        if (output != null) return
        _state.update { it.copy(closed = false, micGranted = hasMicPermission()) }

        // Session first, then audio: Android hands earbud taps to the app that has a media
        // session *and* most recently started playing.
        controls = EarbudControls(
            context,
            main,
            onToggle = ::toggle,
            onStopRequested = { stop() },
            onCloseRequested = onCloseRequested,
        )
        requestFocus()

        val out = OutputLine(sampleRate, main, onRouteChanged = ::updateRoute)
        if (!out.start()) showMessage("Couldn't open audio output")
        output = out
        engine = TrainerEngine(context, out, this).also { applySettings(it, _state.value.settings) }
        updateRoute(out.routedDevice)

        ContextCompat.registerReceiver(
            context,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyRegistered = true
        publishSummary()
    }

    /** vctrnr closed: stop everything and let go of audio. */
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
        _state.update { it.copy(closed = true) }
    }

    fun requestClose() = onCloseRequested()

    fun toggle() {
        if (_state.value.running) stop() else start()
    }

    fun start() {
        val out = output ?: return
        val eng = engine ?: return
        if (eng.isRunning) return
        if (!hasMicPermission()) {
            val ask = onMicPermissionNeeded
            if (ask != null) {
                startWhenPermitted = true
                ask()
            } else {
                showMessage("Open vctrnr to allow the microphone")
            }
            return
        }
        requestFocus()
        out.reclaimMediaControls()

        session = store.newSessionName()
        takeCounter.set(0)
        lastPhase = null
        out.stopSounds()
        out.play(startChime)
        eng.start()
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        _state.update { it.copy(running = true, phase = TrainerEngine.Phase.Listening, takes = 0, message = null) }
        publishSummary()
    }

    fun stop(playChime: Boolean = true) {
        val eng = engine ?: return
        if (!eng.isRunning && !_state.value.running) return
        eng.stop() // Saves (without playing) a take that was cut off mid-sentence.
        output?.let {
            it.stopSounds()
            if (playChime) it.play(stopChime)
        }
        if (wakeLock.isHeld) wakeLock.release()
        _state.update { it.copy(running = false, phase = null, levelDb = SpeechDetector.SILENCE_DB) }
        publishSummary()
    }

    fun updateSettings(settings: TrainerSettings) {
        settingsStore.save(settings)
        engine?.let { applySettings(it, settings) }
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

    /** One line for the notification and lock-screen player. */
    fun summary(): String {
        val s = _state.value
        if (!s.running) return "Off. Tap an earbud or ▶ to start"
        return when (s.phase) {
            TrainerEngine.Phase.Preparing -> "Getting the earbud mic ready…"
            TrainerEngine.Phase.Hearing -> "Hearing you…"
            TrainerEngine.Phase.Playing -> "Playing it back…"
            else -> "Listening"
        } + if (s.takes > 0) " · ${s.takes} saved" else ""
    }

    // --- TrainerEngine.Listener (called on the audio thread) ---

    override fun onStatus(phase: TrainerEngine.Phase, levelDb: Float, thresholdDb: Float) {
        _state.update { if (it.running) it.copy(phase = phase, levelDb = levelDb, thresholdDb = thresholdDb) else it }
        if (phase != lastPhase) {
            lastPhase = phase
            main.post(::publishSummary)
        }
    }

    override fun onMicInUse(mic: Mic, note: String?) {
        _state.update { it.copy(micInUse = mic, message = note ?: it.message) }
    }

    override fun onTake(pcm: ShortArray, sampleRate: Int, playedBack: Boolean) {
        val folder = session ?: return
        val number = takeCounter.incrementAndGet()
        _state.update { it.copy(takes = number) }
        main.post(::publishSummary)
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

    // --- Audio focus: pause other media while vctrnr is open ---

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

    private fun headphonesConnected(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in BLUETOOTH_OUTPUTS || it.type in WIRED_OUTPUTS }

    private fun publishSummary() {
        val text = summary()
        controls?.update(_state.value.running, text)
        if (text != lastSummary) {
            lastSummary = text
            onSummaryChanged?.invoke()
        }
    }

    private fun showMessage(text: String) = _state.update { it.copy(message = text) }

    private fun applySettings(engine: TrainerEngine, s: TrainerSettings) {
        engine.config = SpeechDetector.Config(sensitivity = s.sensitivity, endSilenceMs = s.pauseMs)
        engine.preferredMic = s.mic
    }

    private companion object {
        const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L

        val BLUETOOTH_OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
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
