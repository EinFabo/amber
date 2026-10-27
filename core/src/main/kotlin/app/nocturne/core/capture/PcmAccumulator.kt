package app.nocturne.core.capture

import app.nocturne.core.audio.PcmAudio
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

    fun reset() {
        chunks.clear()
        sampleCount = 0
    }

    companion object {
        private const val MAX_PCM_VALUE = 32_768.0

        /**
         * Maps a frame's RMS onto 0f..1f for the amplitude ring.
         *
         * Logarithmic on purpose: normal speech sits near the bottom of the linear range, so
         * a linear ring barely moves and the bubble looks broken while it is working fine.
         */
        fun normalizeLoudness(rms: Double): Float {
            if (rms <= 1.0) return 0f
            return (log10(rms) / log10(MAX_PCM_VALUE)).toFloat().coerceIn(0f, 1f)
        }
    }
}
