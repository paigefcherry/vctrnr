package app.vctrnr.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * The record → wait for a pause → play it back loop. Runs on its own audio thread between
 * [start] and [stop]; the microphone is only open while it's running.
 *
 * With [Mic.Earbuds] each cycle is:
 *   call mode (earbud mic live) → you talk, then pause → music mode → take plays at full
 *   quality → call mode again → soft tick → listening.
 * With [Mic.Phone] the earbuds stay in music mode the whole time and playback is instant.
 */
class TrainerEngine(
    context: Context,
    private val output: OutputLine,
    private val listener: Listener,
) {
    enum class Phase { Preparing, Listening, Hearing, Playing }

    interface Listener {
        /** Called ~25×/s from the audio thread. */
        fun onStatus(phase: Phase, levelDb: Float, thresholdDb: Float)

        /** Which mic the session ended up using, plus a note if it isn't the one asked for. */
        fun onMicInUse(mic: Mic, note: String?)

        /** A finished take, already normalised. [playedBack] is false if it was cut off by stop. */
        fun onTake(pcm: ShortArray, sampleRate: Int, playedBack: Boolean)

        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    val sampleRate: Int get() = output.sampleRate

    private val detector = SpeechDetector(output.sampleRate)
    private val earbudMic = EarbudMic(appContext)
    private val readyTick = Pcm.tick(output.sampleRate)

    @Volatile private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    var config: SpeechDetector.Config
        get() = detector.config
        set(value) {
            detector.config = value
        }

    /** Mic to use. Takes effect the next time the engine starts. */
    @Volatile var preferredMic: Mic = Mic.Earbuds

    fun start() {
        if (running) return
        running = true
        thread = Thread(::run, "vctrnr-capture").also { it.start() }
    }

    /** Stops the loop and closes the mic. A take in progress is saved (not played). */
    fun stop() {
        running = false
        thread?.join(2500)
        thread = null
    }

    /** The open microphone, and whether the earbuds are in call mode for it. */
    private class Capture(var record: AudioRecord?, var callMode: Boolean)

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Microphone permission is off")
            return
        }
        var capture: Capture? = null
        try {
            // Let the start chime finish in full quality before switching anything.
            waitUntilQuiet()
            if (!running) return
            capture = openCapture(preferredMic)
            if (capture == null) {
                if (running) fail("Couldn't open the microphone (another app may be using it)")
                return
            }
            if (capture.callMode) output.play(readyTick)
            loop(capture)
        } catch (e: Exception) {
            Log.e(TAG, "capture crashed", e)
            fail("Recording stopped: ${e.message}")
        } finally {
            capture?.record?.let { closeRecord(it) }
            if (capture?.callMode == true) earbudMic.exit()
        }
    }

    private fun loop(capture: Capture) {
        val frame = ShortArray(detector.frameSize)
        detector.reset()
        var ignoreMic = true // something (a chime) may be playing; wait for quiet first
        var playingTake = false // so the UI only says "playing back" for actual takes
        var cooldownUntil = 0L
        var phase = Phase.Listening
        var frameCount = 0

        while (running) {
            val record = capture.record ?: break
            if (!readFrame(record, frame)) {
                if (running) fail("Lost the microphone")
                break
            }
            val now = SystemClock.elapsedRealtime()

            if (output.isBusy) {
                // Don't listen to ourselves: ignore the mic while anything is playing,
                // and for a moment after (Bluetooth latency + room echo).
                ignoreMic = true
                cooldownUntil = now + COOLDOWN_MS
                phase = if (playingTake) Phase.Playing else Phase.Listening
            } else if (now < cooldownUntil) {
                phase = if (playingTake) Phase.Playing else Phase.Listening
            } else {
                if (ignoreMic) {
                    ignoreMic = false
                    playingTake = false
                    detector.reset()
                }
                when (val event = detector.process(frame)) {
                    SpeechDetector.Event.SpeechStarted -> phase = Phase.Hearing
                    SpeechDetector.Event.Discarded -> phase = Phase.Listening
                    is SpeechDetector.Event.SpeechEnded -> {
                        val take = prepare(event.utterance.pcm)
                        listener.onTake(take, sampleRate, playedBack = true)
                        if (capture.callMode) {
                            if (!playInMusicMode(capture, take)) break
                            phase = Phase.Listening
                        } else {
                            output.play(take)
                            playingTake = true
                            phase = Phase.Playing
                        }
                        ignoreMic = true
                        cooldownUntil = SystemClock.elapsedRealtime() + COOLDOWN_MS
                    }
                    null -> if (!detector.isSpeaking) phase = Phase.Listening
                }
            }

            if (frameCount++ % 2 == 0) {
                val level = if (ignoreMic) SpeechDetector.SILENCE_DB else detector.levelDb
                listener.onStatus(phase, level, detector.onsetThresholdDb)
            }
        }

        // Stopped mid-sentence: keep the take, but don't play it.
        if (detector.isSpeaking) detector.flush()?.let { listener.onTake(prepare(it.pcm), sampleRate, playedBack = false) }
    }

    /**
     * Earbud-mic cycle: close the mic, flip the earbuds to music mode, play the take, then go
     * back to call mode. Returns false if the session was stopped (or failed) meanwhile.
     */
    private fun playInMusicMode(capture: Capture, take: ShortArray): Boolean {
        capture.record?.let { closeRecord(it) }
        capture.record = null
        status(Phase.Playing)

        earbudMic.exit()
        capture.callMode = false
        earbudMic.waitForMusicMode(output, ::stopping)
        if (!running) return false

        output.play(take)
        waitUntilQuiet()
        sleepWhileRunning(COOLDOWN_MS)
        if (!running) return false

        val next = openCapture(Mic.Earbuds) ?: run {
            if (running) fail("Couldn't reopen the microphone")
            return false
        }
        capture.record = next.record
        capture.callMode = next.callMode
        if (next.callMode) output.play(readyTick)
        return running
    }

    /**
     * Opens the requested mic. For the earbuds that means entering call mode first; if that
     * doesn't work out, falls back to the phone mic so the session still works.
     */
    private fun openCapture(want: Mic): Capture? {
        if (want == Mic.Earbuds) {
            status(Phase.Preparing)
            if (earbudMic.enter(::stopping)) {
                val record = buildRecord(callMode = true)
                if (record != null && startRecord(record) && !routedToPhoneMic(record)) {
                    listener.onMicInUse(Mic.Earbuds, null)
                    return Capture(record, callMode = true)
                }
                record?.let { closeRecord(it) }
                earbudMic.exit()
            }
            if (!running) return null
            listener.onMicInUse(Mic.Phone, "Couldn't use the earbud mic, so using the phone mic")
        } else {
            listener.onMicInUse(Mic.Phone, null)
        }
        val record = buildRecord(callMode = false) ?: return null
        if (!startRecord(record)) {
            closeRecord(record)
            return null
        }
        return Capture(record, callMode = false)
    }

    private fun startRecord(record: AudioRecord): Boolean {
        record.startRecording()
        return record.recordingState == AudioRecord.RECORDSTATE_RECORDING
    }

    /** True if the system quietly sent a "call mode" recording to the phone mic instead. */
    private fun routedToPhoneMic(record: AudioRecord): Boolean =
        record.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC

    private fun closeRecord(record: AudioRecord) {
        runCatching { record.stop() }
        record.release()
    }

    @SuppressLint("MissingPermission") // Checked at the top of run().
    private fun buildRecord(callMode: Boolean): AudioRecord? = try {
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            null
        } else {
            val record = AudioRecord.Builder()
                .setAudioSource(
                    // Earbuds: the call-audio source is what routes to a Bluetooth headset mic.
                    // Phone: VOICE_RECOGNITION = no automatic gain or noise suppression, so you
                    // hear your voice as it really is.
                    if (callMode) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION,
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer * 2, sampleRate)) // sampleRate bytes = 500 ms of 16-bit mono
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                null
            } else {
                val device = if (callMode) earbudMic.input() else phoneMic()
                device?.let { record.setPreferredDevice(it) }
                if (!callMode) record.setPreferredMicrophoneDirection(MediaRecorder.MIC_DIRECTION_TOWARDS_USER)
                record
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Couldn't create AudioRecord (callMode=$callMode)", e)
        null
    }

    private fun phoneMic(): AudioDeviceInfo? {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mics = am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        return mics.firstOrNull { it.address.contains("bottom", ignoreCase = true) } ?: mics.firstOrNull()
    }

    private fun prepare(pcm: ShortArray): ShortArray =
        Pcm.normalized(pcm).also { Pcm.fadeEdges(it, sampleRate) }

    private fun readFrame(record: AudioRecord, frame: ShortArray): Boolean {
        var filled = 0
        while (filled < frame.size) {
            val n = record.read(frame, filled, frame.size - filled, AudioRecord.READ_BLOCKING)
            if (n < 0) {
                Log.e(TAG, "AudioRecord.read error $n")
                return false
            }
            filled += n
            if (!running) return false
        }
        return true
    }

    private fun status(phase: Phase) = listener.onStatus(phase, SpeechDetector.SILENCE_DB, detector.onsetThresholdDb)

    private fun stopping() = !running

    private fun waitUntilQuiet() {
        while (running && output.isBusy) SystemClock.sleep(20)
    }

    private fun sleepWhileRunning(ms: Long) {
        val until = SystemClock.elapsedRealtime() + ms
        while (running && SystemClock.elapsedRealtime() < until) SystemClock.sleep(20)
    }

    private fun fail(message: String) {
        running = false
        listener.onError(message)
    }

    companion object {
        private const val TAG = "vctrnr.Engine"
        private const val COOLDOWN_MS = 300L

        /** 48 kHz is the S23's native rate (no resampling); 44.1 kHz is the universal fallback. */
        fun pickSampleRate(): Int = listOf(48_000, 44_100).firstOrNull {
            AudioRecord.getMinBufferSize(it, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0
        } ?: 44_100
    }
}
