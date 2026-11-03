package app.vctrnr.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal WAV (RIFF, PCM 16-bit, mono) encoder. Plays in every audio app and in Praat. */
object Wav {
    const val HEADER_BYTES = 44

    fun encode(pcm: ShortArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val blockAlign = channels * bitsPerSample / 8
        val dataBytes = pcm.size * blockAlign
        val buf = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataBytes)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16) // fmt chunk size
        buf.putShort(1.toShort()) // PCM
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * blockAlign) // byte rate
        buf.putShort(blockAlign.toShort())
        buf.putShort(bitsPerSample.toShort())
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataBytes)
        for (s in pcm) buf.putShort(s)
        return buf.array()
    }
}
