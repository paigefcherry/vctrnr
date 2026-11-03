package app.vctrnr.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmAndWavTest {

    @Test
    fun normalizeBoostsQuietAudioToTarget() {
        val quiet = ShortArray(1000) { if (it % 2 == 0) 1000 else -1000 }
        val out = Pcm.normalized(quiet, targetPeakDb = -3f, maxGainDb = 30f)
        // -3 dBFS of full scale is ~23197.
        assertTrue(Pcm.peak(out) in 23000..23300)
    }

    @Test
    fun normalizeCapsGainAndNeverAttenuates() {
        val veryQuiet = ShortArray(100) { 10 }
        assertEquals(79, Pcm.peak(Pcm.normalized(veryQuiet, maxGainDb = 18f))) // 10 * 7.94

        val loud = ShortArray(100) { 30000 }
        assertArrayEquals(loud, Pcm.normalized(loud))
    }

    @Test
    fun fadesSilenceTheEdges() {
        val pcm = ShortArray(48000) { 10000 }
        Pcm.fadeEdges(pcm, 48000, fadeMs = 10)
        assertEquals(0, pcm.first().toInt())
        assertEquals(0, pcm.last().toInt())
        assertEquals(10000, pcm[24000].toInt())
    }

    @Test
    fun chimesAreShortAndNotTooLoud() {
        for (rising in listOf(true, false)) {
            val c = Pcm.chime(48000, rising)
            assertTrue(c.size in 9000..15000) // ~260 ms
            assertTrue(Pcm.peak(c) < 8000)
        }
    }

    @Test
    fun wavHeaderIsValid() {
        val pcm = shortArrayOf(1, -2, 3, 32767, -32768)
        val bytes = Wav.encode(pcm, 48000)
        assertEquals(Wav.HEADER_BYTES + pcm.size * 2, bytes.size)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(bytes.size - 8, b.getInt(4))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(48000, b.getInt(24))
        assertEquals(96000, b.getInt(28))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(pcm.size * 2, b.getInt(40))
        assertEquals(-32768, b.getShort(44 + 8).toInt())
    }
}
