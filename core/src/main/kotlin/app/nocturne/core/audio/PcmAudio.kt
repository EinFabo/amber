package app.nocturne.core.audio

/**
 * A chunk of signed 16-bit PCM audio. The capture layer produces these; engines consume them.
 */
class PcmAudio(
    val samples: ShortArray,
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    val channels: Int = 1,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        require(channels == 1 || channels == 2) { "only mono or stereo supported, was $channels" }
    }

    val frameCount: Int get() = samples.size / channels

    val durationMs: Long get() = frameCount * 1000L / sampleRate

    /** Loudness of the whole buffer, in the same units as [rms]. */
    fun rms(): Double = rms(samples, 0, samples.size)

    /**
     * Wraps the samples in a RIFF/WAVE container. STT HTTP endpoints want a real audio
     * file, not raw PCM, and a 44-byte header is cheaper than pulling in an encoder.
     */
    fun toWav(): ByteArray {
        val dataBytes = samples.size * 2
        val out = ByteArray(WAV_HEADER_SIZE + dataBytes)
        var p = 0

        fun ascii(s: String) {
            for (c in s) out[p++] = c.code.toByte()
        }

        fun le32(v: Int) {
            out[p++] = (v and 0xFF).toByte()
            out[p++] = ((v ushr 8) and 0xFF).toByte()
            out[p++] = ((v ushr 16) and 0xFF).toByte()
            out[p++] = ((v ushr 24) and 0xFF).toByte()
        }

        fun le16(v: Int) {
            out[p++] = (v and 0xFF).toByte()
            out[p++] = ((v ushr 8) and 0xFF).toByte()
        }

        val byteRate = sampleRate * channels * 2

        ascii("RIFF")
        le32(36 + dataBytes)
        ascii("WAVE")
        ascii("fmt ")
        le32(16)                 // PCM fmt chunk size
        le16(1)                  // audio format: PCM
        le16(channels)
        le32(sampleRate)
        le32(byteRate)
        le16(channels * 2)       // block align
        le16(16)                 // bits per sample
        ascii("data")
        le32(dataBytes)

        for (s in samples) {
            val v = s.toInt()
            out[p++] = (v and 0xFF).toByte()
            out[p++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    companion object {
        /** What every supported STT model expects, so capture never has to resample. */
        const val DEFAULT_SAMPLE_RATE = 16_000
        const val WAV_HEADER_SIZE = 44

        fun rms(buffer: ShortArray, offset: Int = 0, length: Int = buffer.size - offset): Double {
            if (length <= 0) return 0.0
            var sum = 0.0
            for (i in offset until offset + length) {
                val v = buffer[i].toDouble()
                sum += v * v
            }
            return kotlin.math.sqrt(sum / length)
        }
    }
}
