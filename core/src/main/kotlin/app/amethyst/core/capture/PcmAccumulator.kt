package app.amethyst.core.capture

import app.amethyst.core.audio.PcmAudio
import kotlin.math.log10

/**
 * Collects recorded frames into one buffer.
 *
 * Separate from the recorder because the two things that can go wrong here are arithmetic,
 * not Android: frames arriving shorter than requested (the last read of a stream almost
 * always is), and an amplitude scale that makes the bubble's ring look dead. Both are
 * testable without a microphone.
 */
class PcmAccumulator(private val sampleRate: Int = PcmAudio.DEFAULT_SAMPLE_RATE) {

    private val chunks = ArrayList<ShortArray>()

    var sampleCount: Int = 0
        private set

    val durationMs: Long get() = sampleCount * 1000L / sampleRate

    /** Appends the first [length] samples of [frame]; reads are often shorter than the buffer. */
    fun append(frame: ShortArray, length: Int = frame.size) {
        if (length <= 0) return
        val safeLength = length.coerceAtMost(frame.size)
        chunks.add(frame.copyOf(safeLength))
        sampleCount += safeLength
    }

    fun build(): PcmAudio {
        val out = ShortArray(sampleCount)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return PcmAudio(out, sampleRate)
    }

    /** Samples [from] until [to] (exclusive) as audio, without building the whole buffer. */
    fun slice(from: Int, to: Int = sampleCount): PcmAudio {
        val start = from.coerceIn(0, sampleCount)
        val end = to.coerceIn(start, sampleCount)
        val out = ShortArray(end - start)
        var chunkOffset = 0
        for (chunk in chunks) {
            val chunkEnd = chunkOffset + chunk.size
            if (chunkEnd > start && chunkOffset < end) {
                val copyFrom = maxOf(start, chunkOffset)
                val copyTo = minOf(end, chunkEnd)
                chunk.copyInto(out, copyFrom - start, copyFrom - chunkOffset, copyTo - chunkOffset)
            }
            if (chunkOffset >= end) break
            chunkOffset = chunkEnd
        }
        return PcmAudio(out, sampleRate)
    }

    fun reset() {
        chunks.clear()
        sampleCount = 0
    }

    companion object {
        private const val MAX_PCM_VALUE = 32_768.0

        /**
         * Maps a frame's RMS onto 0f..1f for the bubble's level, in decibels between
         * [FLOOR_DBFS] and [FULL_DBFS].
         *
         * Logarithmic, since normal speech sits near the bottom of the linear range. The window
         * starts above a quiet room: measured on a Pixel 9a, the room sat near -58 dBFS and
         * speech between -35 and -22 - spread over the whole range, the room alone showed a
         * third of the level and soft speech moved it as much as loud.
         */
        fun normalizeLoudness(rms: Double): Float {
            if (rms <= 1.0) return 0f
            val dbfs = 20 * log10(rms / MAX_PCM_VALUE)
            return ((dbfs - FLOOR_DBFS) / (FULL_DBFS - FLOOR_DBFS)).toFloat().coerceIn(0f, 1f)
        }

        /** Quieter than this shows as nothing: a quiet room, breath, the phone's own hiss. */
        const val FLOOR_DBFS = -55.0

        /** Loud speech close to the phone: the full level. */
        const val FULL_DBFS = -20.0
    }
}
