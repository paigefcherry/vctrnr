package app.vctrnr.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decides when you start talking and when you've finished, one 20 ms frame at a time.
 *
 * Pure Kotlin (no Android APIs) so it can be unit tested on the JVM.
 *
 * How it works:
 *  - Each frame is high-passed (to ignore rumble/handling noise) and its loudness measured.
 *  - A background-noise floor is tracked continuously while you're not talking, so the
 *    trigger level adapts to the room.
 *  - "Speech" has to be both louder than the floor *and* voiced (periodic, like a vocal-fold
 *    buzz). That voicing check is what stops typing, door thumps and fan hiss from
 *    triggering a playback.
 *  - A short pre-roll is kept so the first syllable isn't clipped, and a take ends once
 *    you've been quiet for [Config.endSilenceMs].
 */
class SpeechDetector(val sampleRate: Int, config: Config = Config()) {

    data class Config(
        /** 0 = only clear, close speech triggers. 1 = soft/distant speech triggers too. */
        val sensitivity: Float = 0.5f,
        /** How long you must be quiet before the take is considered finished. */
        val endSilenceMs: Int = 1000,
        /** Takes with less voiced sound than this are dropped (coughs, "hm", bumps). */
        val minSpeechMs: Int = 250,
        /** Audio kept from *before* the trigger so the start of the take isn't clipped. */
        val preRollMs: Int = 300,
        /** Audio kept after the last voiced sound, so endings don't sound chopped. */
        val tailMs: Int = 250,
        /** Safety cap: a take is force-finished at this length. */
        val maxUtteranceMs: Int = 120_000,
    )

    class Utterance(val pcm: ShortArray, val sampleRate: Int, val speechMs: Int) {
        val durationMs: Long get() = pcm.size * 1000L / sampleRate
    }

    sealed interface Event {
        data object SpeechStarted : Event
        class SpeechEnded(val utterance: Utterance) : Event
        /** Something triggered the detector but it was too short to count as a take. */
        data object Discarded : Event
    }

    /** May be replaced from any thread; picked up on the next frame. */
    @Volatile
    var config: Config = config

    /** Samples per frame. Feed [process] exactly this many samples each call. */
    val frameSize: Int = sampleRate / FRAMES_PER_SECOND

    // Read by the UI for the level meter; written only by the audio thread.
    @Volatile var levelDb: Float = SILENCE_DB; private set
    @Volatile var noiseFloorDb: Float = INITIAL_FLOOR_DB; private set
    @Volatile var onsetThresholdDb: Float = INITIAL_FLOOR_DB; private set
    @Volatile var isSpeaking: Boolean = false; private set

    private val highPass = HighPass(sampleRate, cutoffHz = 100.0)
    private val filtered = FloatArray(frameSize)

    // --- Voicing (pitch-periodicity) analysis on a decimated copy of the signal ---
    private val decimation = max(1, sampleRate / 12_000)
    private val dsRate = sampleRate / decimation
    private val dsFrame = frameSize / decimation
    private val dsWindow = FloatArray(dsFrame * 2) // last 40 ms
    private val centered = FloatArray(dsWindow.size)
    private val energyPrefix = FloatArray(dsWindow.size + 1)
    private val minLag = max(2, dsRate / MAX_F0_HZ)
    private val maxLag = min(dsWindow.size / 2, dsRate / MIN_F0_HZ)

    // --- Onset history (last ONSET_WINDOW frames) ---
    private val loudHistory = BooleanArray(ONSET_WINDOW)
    private val voicedHistory = BooleanArray(ONSET_WINDOW)
    private var historyPos = 0

    // --- Audio buffers ---
    private val preRoll = ArrayDeque<ShortArray>()
    private val utterance = ArrayList<ShortArray>()
    private var lastActiveFrames = 0 // utterance.size right after the most recent active frame
    private var silentFrames = 0
    private var voicedFrames = 0
    private var framesListened = 0L

    /**
     * Analyse one frame. Returns an [Event] when something changes, otherwise null.
     * The frame array may be reused by the caller; it's copied if kept.
     */
    fun process(frame: ShortArray): Event? {
        require(frame.size == frameSize) { "Expected $frameSize samples, got ${frame.size}" }
        val cfg = config
        val level = measure(frame)
        levelDb = level

        val s = cfg.sensitivity.coerceIn(0f, 1f)
        val margin = lerp(16f, 7f, s) // dB above the noise floor needed to trigger
        val absoluteMin = lerp(-48f, -66f, s) // never trigger below this, however quiet the room
        val onsetThreshold = max(noiseFloorDb + margin, absoluteMin)
        val holdThreshold = max(noiseFloorDb + margin * 0.5f, absoluteMin - 6f)
        onsetThresholdDb = onsetThreshold

        val voicing = if (level >= holdThreshold) voicing() else 0f
        val loud = level >= onsetThreshold
        val voiced = loud && voicing >= VOICED_THRESHOLD

        return if (isSpeaking) {
            follow(frame, active = level >= holdThreshold && voicing >= ACTIVE_VOICING, voiced, cfg)
        } else {
            listen(frame, level, loud, voiced, cfg)
        }
    }

    /**
     * Ends any take in progress right now (e.g. the user pressed stop) and returns it if it
     * was long enough to keep.
     */
    fun flush(): Utterance? {
        if (!isSpeaking) return null
        return (finish(config) as? Event.SpeechEnded)?.utterance
    }

    /**
     * Forget recent audio (pre-roll, onset history, any take in progress) but keep the learned
     * noise floor. Called after a playback so the playback itself can't trigger a new take.
     */
    fun reset() {
        isSpeaking = false
        preRoll.clear()
        utterance.clear()
        clearHistory()
        dsWindow.fill(0f)
        highPass.reset()
        silentFrames = 0
        voicedFrames = 0
    }

    private fun listen(frame: ShortArray, level: Float, loud: Boolean, voiced: Boolean, cfg: Config): Event? {
        trackNoiseFloor(level, voiced)

        preRoll.addLast(frame.copyOf())
        val maxPreRoll = max(1, cfg.preRollMs / FRAME_MS)
        while (preRoll.size > maxPreRoll) preRoll.removeFirst()

        loudHistory[historyPos] = loud
        voicedHistory[historyPos] = voiced
        historyPos = (historyPos + 1) % ONSET_WINDOW

        val loudCount = loudHistory.count { it }
        val voicedCount = voicedHistory.count { it }
        if (loudCount < ONSET_LOUD_FRAMES || voicedCount < ONSET_VOICED_FRAMES) return null

        isSpeaking = true
        utterance.clear()
        utterance.addAll(preRoll)
        preRoll.clear()
        lastActiveFrames = utterance.size
        silentFrames = 0
        voicedFrames = voicedCount
        clearHistory()
        return Event.SpeechStarted
    }

    private fun follow(frame: ShortArray, active: Boolean, voiced: Boolean, cfg: Config): Event? {
        utterance.add(frame.copyOf())
        if (voiced) voicedFrames++
        if (active) {
            silentFrames = 0
            lastActiveFrames = utterance.size
        } else {
            silentFrames++
        }
        val quietLongEnough = silentFrames * FRAME_MS >= cfg.endSilenceMs
        val tooLong = utterance.size * FRAME_MS >= cfg.maxUtteranceMs
        return if (quietLongEnough || tooLong) finish(cfg) else null
    }

    private fun finish(cfg: Config): Event {
        isSpeaking = false
        val keepFrames = min(utterance.size, lastActiveFrames + cfg.tailMs / FRAME_MS)
        val speechMs = voicedFrames * FRAME_MS
        val event = if (speechMs < cfg.minSpeechMs || keepFrames == 0) {
            Event.Discarded
        } else {
            val pcm = ShortArray(keepFrames * frameSize)
            for (i in 0 until keepFrames) {
                utterance[i].copyInto(pcm, destinationOffset = i * frameSize)
            }
            Event.SpeechEnded(Utterance(pcm, sampleRate, speechMs))
        }
        utterance.clear()
        preRoll.clear()
        clearHistory()
        silentFrames = 0
        voicedFrames = 0
        return event
    }

    private fun trackNoiseFloor(level: Float, voiced: Boolean) {
        framesListened++
        val warmingUp = framesListened <= WARMUP_FRAMES
        var floor = noiseFloorDb
        if (level < floor) {
            // Fall quickly: a quieter reading is good evidence the room is quieter.
            floor += (level - floor) * (if (warmingUp) 0.5f else 0.2f)
        } else if (!voiced) {
            // Rise slowly (and never because of voice), so talking doesn't raise the bar.
            floor += min(level - floor, 12f) * (if (warmingUp) 0.15f else FLOOR_RISE_PER_FRAME)
        }
        noiseFloorDb = floor.coerceIn(-90f, -25f)
    }

    /** High-pass the frame, update the voicing window, and return the frame level in dBFS. */
    private fun measure(frame: ShortArray): Float {
        var sumSq = 0.0
        for (i in 0 until frameSize) {
            val y = highPass.process(frame[i].toDouble())
            filtered[i] = y.toFloat()
            sumSq += y * y
        }

        // Slide the 40 ms decimated window along by one frame.
        val keep = dsWindow.size - dsFrame
        System.arraycopy(dsWindow, dsFrame, dsWindow, 0, keep)
        for (j in 0 until dsFrame) {
            var acc = 0f
            val offset = j * decimation
            for (k in 0 until decimation) acc += filtered[offset + k]
            dsWindow[keep + j] = acc / decimation
        }

        val rms = sqrt(sumSq / frameSize)
        if (rms < 1e-6) return SILENCE_DB
        return (20.0 * log10(rms / 32768.0)).toFloat().coerceAtLeast(SILENCE_DB)
    }

    /**
     * Peak normalised autocorrelation over plausible voice pitch periods (60-500 Hz).
     * ~0.7-0.95 for sung/spoken vowels, ~0.1-0.3 for broadband noise.
     */
    private fun voicing(): Float {
        val n = dsWindow.size
        var mean = 0f
        for (v in dsWindow) mean += v
        mean /= n
        energyPrefix[0] = 0f
        for (i in 0 until n) {
            val c = dsWindow[i] - mean
            centered[i] = c
            energyPrefix[i + 1] = energyPrefix[i] + c * c
        }
        if (energyPrefix[n] <= 1e-3f) return 0f

        var best = 0f
        for (lag in minLag..maxLag) {
            val m = n - lag
            var cross = 0f
            for (i in 0 until m) cross += centered[i] * centered[i + lag]
            if (cross <= 0f) continue
            val e0 = energyPrefix[m]
            val e1 = energyPrefix[n] - energyPrefix[lag]
            val denom = sqrt(e0 * e1)
            if (denom > 0f) {
                val r = cross / denom
                if (r > best) best = r
            }
        }
        return best
    }

    private fun clearHistory() {
        loudHistory.fill(false)
        voicedHistory.fill(false)
        historyPos = 0
    }

    /** RBJ-cookbook 2nd-order high-pass, used for analysis only (never on the saved audio). */
    private class HighPass(sampleRate: Int, cutoffHz: Double) {
        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        init {
            val w0 = 2.0 * PI * cutoffHz / sampleRate
            val alpha = sin(w0) / (2.0 * 0.7071)
            val cosW = cos(w0)
            val a0 = 1.0 + alpha
            b0 = (1.0 + cosW) / 2.0 / a0
            b1 = -(1.0 + cosW) / a0
            b2 = (1.0 + cosW) / 2.0 / a0
            a1 = -2.0 * cosW / a0
            a2 = (1.0 - alpha) / a0
        }

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }

        fun reset() {
            x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
        }
    }

    companion object {
        const val FRAMES_PER_SECOND = 50
        const val FRAME_MS = 1000 / FRAMES_PER_SECOND
        const val SILENCE_DB = -100f

        private const val INITIAL_FLOOR_DB = -60f
        private const val WARMUP_FRAMES = 25 // 0.5 s of fast floor adaptation after first start
        private const val FLOOR_RISE_PER_FRAME = 0.004f

        private const val MIN_F0_HZ = 60
        private const val MAX_F0_HZ = 500
        private const val VOICED_THRESHOLD = 0.5f
        private const val ACTIVE_VOICING = 0.25f

        private const val ONSET_WINDOW = 10 // 200 ms
        private const val ONSET_LOUD_FRAMES = 4 // 80 ms loud...
        private const val ONSET_VOICED_FRAMES = 3 // ...of which 60 ms clearly voiced

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    }
}
