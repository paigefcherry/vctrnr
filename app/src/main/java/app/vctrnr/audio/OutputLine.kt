package app.vctrnr.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * One continuously running media-audio stream, open for as long as the app is on screen.
 *
 * It plays silence until it's given a sound (a take or a chime). Keeping it running does
 * three useful things:
 *  1. Android routes earbud play/pause taps to the app that is currently playing media, so
 *     a constantly-playing (silent) stream keeps vctrnr in charge of those taps.
 *  2. The Bluetooth link to the buds stays awake, so the first syllable of a playback is
 *     never swallowed by the stream starting up.
 *  3. Starting a playback is instant: no new AudioTrack per take.
 */
class OutputLine(
    val sampleRate: Int,
    private val mainHandler: Handler,
    private val onRouteChanged: (AudioDeviceInfo?) -> Unit,
) {
    private val lock = Any()
    private val queue = ArrayDeque<ShortArray>() // guarded by lock
    private var pendingSounds = 0 // queued + being written + still audible; guarded by lock
    private var cancelCurrent = false // guarded by lock

    private val endMarks = ArrayDeque<EndMark>() // writer thread only
    private class EndMark(val frame: Long, val deadline: Long)

    @Volatile private var running = false
    @Volatile private var reclaimRequested = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    private val routingListener = AudioRouting.OnRoutingChangedListener { router ->
        onRouteChanged(router.routedDevice)
    }

    /** True while a sound is queued or still playing. */
    val isBusy: Boolean get() = synchronized(lock) { pendingSounds > 0 }

    val routedDevice: AudioDeviceInfo? get() = track?.routedDevice

    fun start(): Boolean {
        if (running) return true
        val t = try {
            buildTrack()
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't open audio output", e)
            return false
        }
        track = t
        t.addOnRoutingChangedListener(routingListener, mainHandler)
        running = true
        thread = Thread({ writeLoop(t) }, "vctrnr-output").also { it.start() }
        return true
    }

    /** Queue a sound. It plays after anything already queued. */
    fun play(pcm: ShortArray) {
        if (pcm.isEmpty()) return
        synchronized(lock) {
            queue.addLast(pcm)
            pendingSounds++
        }
    }

    /**
     * Android sends earbud taps to whichever app most recently *started* playing. If another
     * app (Spotify, YouTube...) played while we were open, briefly pausing and resuming our
     * stream makes us the most recent one again. Inaudible: it's silence.
     */
    fun reclaimMediaControls() {
        reclaimRequested = true
    }

    /** Drop everything queued and cut off whatever is playing (within a few ms). */
    fun stopSounds() {
        synchronized(lock) {
            pendingSounds -= queue.size
            queue.clear()
            cancelCurrent = true
        }
    }

    fun release() {
        running = false
        thread?.join(1000)
        thread = null
        track?.let {
            it.removeOnRoutingChangedListener(routingListener)
            runCatching { it.stop() }
            it.release()
        }
        track = null
        synchronized(lock) {
            queue.clear()
            pendingSounds = 0
        }
    }

    private fun buildTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(ATTRIBUTES)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer * 2)
            .build()
    }

    private fun writeLoop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val chunk = sampleRate / 100 // 10 ms
        val silence = ShortArray(chunk)
        var current: ShortArray? = null
        var offset = 0
        var framesWritten = 0L

        try {
            t.play()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Couldn't start audio output", e)
            running = false
            return
        }

        while (running) {
            if (reclaimRequested) {
                reclaimRequested = false
                try {
                    t.pause()
                    SystemClock.sleep(150)
                    t.play()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Couldn't restart output", e)
                }
            }
            synchronized(lock) {
                if (cancelCurrent) {
                    cancelCurrent = false
                    if (current != null) {
                        current = null
                        pendingSounds--
                    }
                }
                if (current == null) {
                    current = queue.removeFirstOrNull()
                    offset = 0
                }
            }

            val sound = current
            val written = if (sound != null) {
                val n = minOf(chunk, sound.size - offset)
                val w = t.write(sound, offset, n, AudioTrack.WRITE_BLOCKING)
                if (w > 0) {
                    offset += w
                    if (offset >= sound.size) {
                        val bufferedMs = (framesWritten + w - playedFrames(t)) * 1000 / sampleRate
                        endMarks.addLast(EndMark(framesWritten + w, SystemClock.elapsedRealtime() + bufferedMs + 1500))
                        synchronized(lock) {
                            // Only clear if stopSounds() didn't already cancel it mid-write.
                            if (current === sound) current = null
                        }
                    }
                }
                w
            } else {
                t.write(silence, 0, chunk, AudioTrack.WRITE_BLOCKING)
            }

            if (written < 0) {
                Log.e(TAG, "AudioTrack.write failed: $written")
                SystemClock.sleep(10)
                continue
            }
            framesWritten += written

            // A sound counts as finished once the playback head has passed its last frame
            // (or, defensively, once its deadline has passed).
            if (endMarks.isNotEmpty()) {
                val played = playedFrames(t)
                val now = SystemClock.elapsedRealtime()
                var finished = 0
                while (endMarks.isNotEmpty() && (played >= endMarks.first().frame || now > endMarks.first().deadline)) {
                    endMarks.removeFirst()
                    finished++
                }
                if (finished > 0) synchronized(lock) { pendingSounds = (pendingSounds - finished).coerceAtLeast(0) }
            }
        }
        endMarks.clear()
    }

    /** Frames the hardware has consumed. The head position is an unsigned 32-bit counter. */
    private fun playedFrames(t: AudioTrack): Long = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    companion object {
        private const val TAG = "vctrnr.Output"

        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
