package app.amethyst.core.capture

/**
 * Decides where a running recording can be cut, so the finished part is recognized while the
 * user is still speaking and only the last part is left when they stop.
 *
 * Cuts land only in real pauses, in the middle of the pause, so no word is split and each side
 * keeps a little silence around its speech. A chunk must be long enough to carry context
 * ([minChunkMs]); a chunk without a pause is cut at [maxChunkMs] - Whisper's window is 30 s -
 * at the quietest moment of its last stretch rather than wherever it happens to be.
 *
 * Fed one frame at a time with the sample count recorded so far and whether the frame is
 * speech; returns the finished chunk, or null.
 */
class ChunkPlanner(
    private val sampleRate: Int,
    private val frameMs: Int = 20,
    private val minChunkMs: Int = 4_000,
    private val pauseMs: Int = 450,
    private val maxChunkMs: Int = 25_000,
    /** How far back from [maxChunkMs] a forced cut looks for the quietest frame. */
    private val quietSearchMs: Int = 2_000,
) {

    /** Where the current chunk began, in samples since the start of the recording. */
    var chunkStart: Int = 0
        private set

    /** The current chunk contains speech; a chunk of pure silence is never cut off on its own. */
    var speechSinceCut: Boolean = false
        private set

    private var silenceMs = 0
    private var quietestRms = Double.MAX_VALUE
    private var quietestAt = 0

    /** Samples [start] until [end] (exclusive) of the recording. */
    data class Chunk(val start: Int, val end: Int)

    fun onFrame(totalSamples: Int, rms: Double, speech: Boolean): Chunk? {
        if (speech) {
            speechSinceCut = true
            silenceMs = 0
        } else {
            silenceMs += frameMs
        }

        val chunkMs = ms(totalSamples - chunkStart)
        if (chunkMs >= maxChunkMs - quietSearchMs && rms < quietestRms) {
            quietestRms = rms
            quietestAt = totalSamples
        }

        if (!speechSinceCut && chunkMs >= maxChunkMs) {
            // Long silence (holding without speaking): nothing to recognize, so skip past it
            // instead of making Whisper chew on 25 s of room noise.
            chunkStart = totalSamples
            quietestRms = Double.MAX_VALUE
            return null
        }

        val cut = when {
            speechSinceCut && chunkMs >= minChunkMs && silenceMs >= pauseMs ->
                totalSamples - samples(silenceMs / 2)
            chunkMs >= maxChunkMs -> quietestAt.takeIf { it > chunkStart } ?: totalSamples
            else -> null
        } ?: return null

        val chunk = Chunk(chunkStart, cut)
        chunkStart = cut
        // A forced cut can land in speech, so what follows may still be speech.
        speechSinceCut = ms(totalSamples - cut) > 0 && silenceMs == 0
        silenceMs = 0
        quietestRms = Double.MAX_VALUE
        quietestAt = 0
        return chunk
    }

    private fun ms(sampleCount: Int): Long = sampleCount * 1000L / sampleRate

    private fun samples(ms: Int): Int = (ms.toLong() * sampleRate / 1000).toInt()
}
