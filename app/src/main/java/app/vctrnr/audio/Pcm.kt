package app.vctrnr.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Small helpers for 16-bit mono PCM. Pure Kotlin, unit tested on the JVM. */
object Pcm {

    fun peak(pcm: ShortArray): Int {
        var p = 0
        for (s in pcm) {
            val a = abs(s.toInt())
            if (a > p) p = a
        }
        return p
    }

    /**
     * Returns a copy boosted so its loudest point sits at [targetPeakDb] dBFS, so quiet takes
     * are easy to hear in the earbuds. Never turns audio down, and never boosts more than
     * [maxGainDb] (so near-silence isn't blown up into hiss).
     */
    fun normalized(pcm: ShortArray, targetPeakDb: Float = -3f, maxGainDb: Float = 18f): ShortArray {
        val peak = peak(pcm)
        if (peak == 0) return pcm.copyOf()
        val target = 32767.0 * 10.0.pow(targetPeakDb / 20.0)
        val maxGain = 10.0.pow(maxGainDb / 20.0)
        val gain = (target / peak).coerceIn(1.0, maxGain)
        if (gain < 1.001) return pcm.copyOf()
        return ShortArray(pcm.size) { i ->
            (pcm[i] * gain).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** Short linear fade in/out (in place) so takes don't start or end with a click. */
    fun fadeEdges(pcm: ShortArray, sampleRate: Int, fadeMs: Int = 10) {
        val n = min(pcm.size / 2, sampleRate * fadeMs / 1000)
        if (n <= 0) return
        for (i in 0 until n) {
            val g = i.toFloat() / n
            pcm[i] = (pcm[i] * g).toInt().toShort()
            val j = pcm.size - 1 - i
            pcm[j] = (pcm[j] * g).toInt().toShort()
        }
    }

    fun silence(sampleRate: Int, ms: Int): ShortArray = ShortArray(sampleRate * ms / 1000)

    /**
     * A soft two-note chime: rising when recording turns on, falling when it turns off.
     * Lets you know an earbud tap registered without looking at the phone.
     */
    fun chime(sampleRate: Int, rising: Boolean): ShortArray {
        val notes = if (rising) doubleArrayOf(660.0, 990.0) else doubleArrayOf(990.0, 660.0)
        val noteSamples = sampleRate * 120 / 1000
        val gapSamples = sampleRate * 20 / 1000
        val out = ShortArray(notes.size * noteSamples + (notes.size - 1) * gapSamples)
        val amplitude = 0.16 * 32767
        val attack = sampleRate * 6 / 1000
        val decayTau = sampleRate * 0.045
        notes.forEachIndexed { n, freq ->
            val start = n * (noteSamples + gapSamples)
            for (i in 0 until noteSamples) {
                val env = if (i < attack) i.toDouble() / attack else exp(-(i - attack) / decayTau)
                val t = i.toDouble() / sampleRate
                val v = sin(2 * PI * freq * t) + 0.25 * sin(4 * PI * freq * t)
                out[start + i] = (amplitude * env * v / 1.25).roundToInt().toShort()
            }
        }
        return out
    }
}
