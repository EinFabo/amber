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
 * A real pause ([longPauseMs], 800 ms) is preferred: a shorter breath is often inside a sentence,
 * and a cut there makes the recognizer end each side as a sentence of its own. Only in the last
 * quarter before [maxChunkMs] a breath of [pauseMs] cuts too, rather than a forced cut in a word.
 * The chunk says how long the pause at its end was ([Chunk.pauseMs]), so the join of the texts
 * can tell a cut at a breath from a cut at a pause.
 *
 * Fed one frame at a time with the sample count recorded so far and whether the frame is
 * speech; returns the finished chunk, or null.
 */
class ChunkPlanner(
    private val sampleRate: Int,
    private val frameMs: Int = 20,
    private val minChunkMs: Int = 4_000,
    private val pauseMs: Int = 450,
    private val longPauseMs: Int = 800,
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

    /** Where the last speech frame ended, in samples since the start of the recording. */
    private var lastSpeechEnd = 0
    private var quietestRms = Double.MAX_VALUE
    private var quietestAt = 0

    /**
     * Samples [start] until [end] (exclusive) of the recording. [pauseMs] is the silence heard
     * at the end when it was cut: 0 for a forced cut in the middle of speech.
     */
    data class Chunk(val start: Int, val end: Int, val pauseMs: Int = 0)

    fun onFrame(totalSamples: Int, rms: Double, speech: Boolean): Chunk? {
        if (speech) {
            speechSinceCut = true
            silenceMs = 0
            lastSpeechEnd = totalSamples
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

        val atPause = speechSinceCut && chunkMs >= minChunkMs && pausedEnough(chunkMs)
        val cut = when {
            atPause ->
                totalSamples - samples(silenceMs / 2)
            chunkMs >= maxChunkMs -> quietestAt.takeIf { it > chunkStart } ?: totalSamples
            else -> null
        } ?: return null

        val pause = if (atPause) silenceMs else 0
        val chunk = Chunk(chunkStart, cut, pause)
        chunkStart = cut
        // A forced cut goes back to the quietest frame, so words may follow it - even when
        // this frame is a breath. Without them counted, a stop right now would drop them.
        speechSinceCut = lastSpeechEnd > cut
        silenceMs = 0
        quietestRms = Double.MAX_VALUE
        quietestAt = 0
        return chunk
    }

    /** A pause, or a breath once the chunk is in the last quarter before the longest one. */
    private fun pausedEnough(chunkMs: Long): Boolean =
        silenceMs >= longPauseMs || (silenceMs >= pauseMs && chunkMs >= maxChunkMs - maxChunkMs / 4)

    private fun ms(sampleCount: Int): Long = sampleCount * 1000L / sampleRate

    private fun samples(ms: Int): Int = (ms.toLong() * sampleRate / 1000).toInt()
}
