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
 */
class TrainerEngine(
    private val context: Context,
    private val output: OutputLine,
    private val listener: Listener,
) {
    enum class Phase { Listening, Hearing, Playing }

    interface Listener {
        /** Called ~25×/s from the audio thread. */
        fun onStatus(phase: Phase, levelDb: Float, thresholdDb: Float)

        /** A finished take, already normalised. [playedBack] is false if it was cut off by stop. */
        fun onTake(pcm: ShortArray, sampleRate: Int, playedBack: Boolean)

        fun onError(message: String)
    }

    val sampleRate: Int get() = output.sampleRate

    private val detector = SpeechDetector(output.sampleRate)

    @Volatile private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    var config: SpeechDetector.Config
        get() = detector.config
        set(value) {
            detector.config = value
        }

    fun start() {
        if (running) return
        running = true
        thread = Thread(::captureLoop, "vctrnr-capture").also { it.start() }
    }

    /** Stops the loop and closes the mic. A take in progress is saved (not played). */
    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }

    @SuppressLint("MissingPermission") // Checked at the top of the method.
    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Microphone permission is off")
            return
        }

        val record = try {
            openMic()
        } catch (e: Exception) {
            Log.e(TAG, "openMic failed", e)
            null
        }
        if (record == null) {
            fail("Couldn't open the microphone")
            return
        }

        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                fail("The microphone is busy (another app may be using it)")
                return
            }
            loop(record)
        } catch (e: Exception) {
            Log.e(TAG, "capture loop crashed", e)
            fail("Recording stopped: ${e.message}")
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    private fun loop(record: AudioRecord) {
        val frame = ShortArray(detector.frameSize)
        detector.reset()
        var playbackActive = true // the start chime is playing; wait for it
        var cooldownUntil = 0L
        var phase = Phase.Playing
        var frameCount = 0

        while (running) {
            if (!readFrame(record, frame)) {
                if (running) fail("Lost the microphone")
                break
            }
            val now = SystemClock.elapsedRealtime()

            if (output.isBusy) {
                // Don't listen to ourselves: ignore the mic while anything is playing,
                // and for a moment after (Bluetooth latency + room echo).
                playbackActive = true
                cooldownUntil = now + COOLDOWN_MS
                phase = Phase.Playing
            } else if (now < cooldownUntil) {
                phase = Phase.Playing
            } else {
                if (playbackActive) {
                    playbackActive = false
                    detector.reset()
                }
                when (val event = detector.process(frame)) {
                    SpeechDetector.Event.SpeechStarted -> phase = Phase.Hearing
                    SpeechDetector.Event.Discarded -> phase = Phase.Listening
                    is SpeechDetector.Event.SpeechEnded -> {
                        val take = prepare(event.utterance.pcm)
                        output.play(take)
                        listener.onTake(take, sampleRate, playedBack = true)
                        playbackActive = true
                        cooldownUntil = now + COOLDOWN_MS
                        phase = Phase.Playing
                    }
                    null -> if (!detector.isSpeaking) phase = Phase.Listening
                }
            }

            if (frameCount++ % 2 == 0) {
                val level = if (phase == Phase.Playing) SpeechDetector.SILENCE_DB else detector.levelDb
                listener.onStatus(phase, level, detector.onsetThresholdDb)
            }
        }

        // Stopped mid-sentence: keep the take, but don't play it.
        if (detector.isSpeaking) detector.flush()?.let { listener.onTake(prepare(it.pcm), sampleRate, playedBack = false) }
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

    @SuppressLint("MissingPermission")
    private fun openMic(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return null
        val record = AudioRecord.Builder()
            // VOICE_RECOGNITION = no automatic gain control or noise suppression, so you hear
            // your voice as it really is, not as a call-processed version of it.
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
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
            return null
        }
        // Always use the phone's own mic. Using the earbuds' mic would force them into
        // "phone call" mode: low-fi audio both ways, and taps would stop working as play/pause.
        phoneMic()?.let { record.setPreferredDevice(it) }
        record.setPreferredMicrophoneDirection(MediaRecorder.MIC_DIRECTION_TOWARDS_USER)
        return record
    }

    private fun phoneMic(): AudioDeviceInfo? {
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        val mics = am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        return mics.firstOrNull { it.address.contains("bottom", ignoreCase = true) } ?: mics.firstOrNull()
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
