package app.vctrnr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class SpeechDetectorTest {

    private val sr = 48_000

    /** Builds test signals: background noise plus a buzzy, vowel-like "voice". */
    private class Signal(val sr: Int, seed: Long = 7) {
        private val rnd = Random(seed)
        private val samples = ArrayList<Short>()
        val lengthMs: Int get() = (samples.size * 1000L / sr).toInt()

        fun noise(ms: Int, db: Double) = apply {
            val sigma = 32768.0 * 10.0.pow(db / 20.0)
            repeat(sr * ms / 1000) { samples += clip(rnd.nextGaussian() * sigma) }
        }

        /** Harmonic series (like a voiced vowel) with gentle vibrato, over a noise bed. */
        fun voice(ms: Int, db: Double, f0: Double = 170.0, bedDb: Double = -72.0) = apply {
            val harmonics = 8
            val norm = sqrt((1..harmonics).sumOf { 1.0 / (it * it) } / 2.0)
            val amp = 32768.0 * 10.0.pow(db / 20.0) / norm
            val bed = 32768.0 * 10.0.pow(bedDb / 20.0)
            var phase = 0.0
            repeat(sr * ms / 1000) { i ->
                val t = i.toDouble() / sr
                val f = f0 * (1.0 + 0.02 * sin(2 * PI * 5.0 * t))
                phase += 2 * PI * f / sr
                var v = 0.0
                for (k in 1..harmonics) v += sin(k * phase) / k
                samples += clip(amp * v + rnd.nextGaussian() * bed)
            }
        }

        fun quiet(ms: Int, bedDb: Double = -72.0) = noise(ms, bedDb)

        fun toArray() = ShortArray(samples.size) { samples[it] }

        private fun clip(v: Double) = v.coerceIn(-32768.0, 32767.0).toInt().toShort()
    }

    private data class Timed(val ms: Int, val event: SpeechDetector.Event)

    private fun run(detector: SpeechDetector, pcm: ShortArray): List<Timed> {
        val out = ArrayList<Timed>()
        val n = detector.frameSize
        var i = 0
        while (i + n <= pcm.size) {
            detector.process(pcm.copyOfRange(i, i + n))?.let { out += Timed(((i + n) * 1000L / sr).toInt(), it) }
            i += n
        }
        return out
    }

    private fun List<Timed>.started() = filter { it.event is SpeechDetector.Event.SpeechStarted }
    private fun List<Timed>.ended() = filter { it.event is SpeechDetector.Event.SpeechEnded }
    private fun Timed.utterance() = (event as SpeechDetector.Event.SpeechEnded).utterance

    private fun rmsDb(pcm: ShortArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += pcm[i].toDouble() * pcm[i]
        return 20 * log10(sqrt(s / (to - from)).coerceAtLeast(1e-9) / 32768.0)
    }

    @Test
    fun detectsOneTakeAndEndsAfterThePause() {
        val config = SpeechDetector.Config(endSilenceMs = 1000)
        val detector = SpeechDetector(sr, config)
        val sig = Signal(sr).quiet(1000)
        val voiceStart = sig.lengthMs
        sig.voice(1200, db = -28.0)
        val voiceEnd = sig.lengthMs
        sig.quiet(2000)

        val events = run(detector, sig.toArray())
        assertEquals(1, events.started().size)
        assertEquals(1, events.ended().size)

        val startDelay = events.started().single().ms - voiceStart
        assertTrue("onset took ${startDelay}ms", startDelay in 0..200)

        val end = events.ended().single()
        val endDelay = end.ms - voiceEnd
        assertTrue("end detected ${endDelay}ms after voice stopped", endDelay in 900..1150)

        val u = end.utterance()
        assertTrue("take was ${u.durationMs}ms", u.durationMs in 1300L..1900L)
        assertTrue("speech ${u.speechMs}ms", u.speechMs >= 1000)
        // The pre-roll means the take starts *before* the voice did: first 100 ms is room tone.
        assertTrue(rmsDb(u.pcm, 0, sr / 10) < -55)
    }

    @Test
    fun loudNoiseBurstDoesNotTrigger() {
        val detector = SpeechDetector(sr)
        val sig = Signal(sr).quiet(1000).noise(800, db = -22.0).quiet(1500)
        val events = run(detector, sig.toArray())
        assertTrue("noise triggered: $events", events.started().isEmpty())
        assertTrue(events.ended().isEmpty())
    }

    @Test
    fun shortBlipIsDiscardedNotPlayedBack() {
        val detector = SpeechDetector(sr)
        val sig = Signal(sr).quiet(1000).voice(120, db = -25.0).quiet(1500)
        val events = run(detector, sig.toArray())
        assertTrue(events.ended().isEmpty())
    }

    @Test
    fun shortGapKeepsOneTakeLongGapMakesTwo() {
        val shortGap = Signal(sr).quiet(800).voice(700, -28.0).quiet(400).voice(700, -28.0, f0 = 200.0).quiet(1600)
        assertEquals(1, run(SpeechDetector(sr), shortGap.toArray()).ended().size)

        val longGap = Signal(sr).quiet(800).voice(700, -28.0).quiet(2000).voice(700, -28.0, f0 = 200.0).quiet(1600)
        assertEquals(2, run(SpeechDetector(sr), longGap.toArray()).ended().size)
    }

    @Test
    fun adaptsToANoisyRoom() {
        val detector = SpeechDetector(sr)
        val sig = Signal(sr).noise(8000, db = -48.0)
        run(detector, sig.toArray())
        assertTrue("floor ${detector.noiseFloorDb}", detector.noiseFloorDb in -53f..-44f)

        val talk = Signal(sr, seed = 3).voice(1000, db = -30.0, bedDb = -48.0).noise(1500, db = -48.0)
        val events = run(detector, talk.toArray())
        assertEquals(1, events.ended().size)
    }

    @Test
    fun sensitivityControlsSoftSpeech() {
        fun takes(sensitivity: Float): Int {
            val d = SpeechDetector(sr, SpeechDetector.Config(sensitivity = sensitivity))
            val sig = Signal(sr).quiet(1500, bedDb = -78.0).voice(1000, db = -56.0, bedDb = -78.0).quiet(1500, bedDb = -78.0)
            return run(d, sig.toArray()).ended().size
        }
        assertEquals(1, takes(1f))
        assertEquals(0, takes(0f))
    }

    @Test
    fun longTakesAreCapped() {
        val detector = SpeechDetector(sr, SpeechDetector.Config(maxUtteranceMs = 2000))
        val sig = Signal(sr).quiet(800).voice(5000, -28.0).quiet(1500)
        val ended = run(detector, sig.toArray()).ended()
        assertTrue(ended.isNotEmpty())
        assertTrue(ended.first().utterance().durationMs <= 2000L)
    }

    @Test
    fun flushReturnsTakeInProgress() {
        val detector = SpeechDetector(sr)
        run(detector, Signal(sr).quiet(800).voice(900, -28.0).toArray())
        assertTrue(detector.isSpeaking)
        val u = detector.flush()
        assertNotNull(u)
        assertTrue(u!!.speechMs >= 700)
        assertNull(detector.flush())
    }

    @Test
    fun worksAt44100() {
        val sr441 = 44_100
        val d = SpeechDetector(sr441)
        val sig = Signal(sr441).quiet(1000).voice(1000, -30.0).quiet(1600)
        val n = d.frameSize
        var ended = 0
        val pcm = sig.toArray()
        var i = 0
        while (i + n <= pcm.size) {
            if (d.process(pcm.copyOfRange(i, i + n)) is SpeechDetector.Event.SpeechEnded) ended++
            i += n
        }
        assertEquals(1, ended)
    }
}
