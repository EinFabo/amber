package app.amethyst.core.audio

/**
 * Where a long recording is cut into pieces a model on the phone takes one at a time.
 *
 * A dictation is normally recognized in parts while it is spoken (ChunkPlanner, at most 25 s
 * each). A whole recording reaches a model in one piece only when chunking is off, when a
 * chunk failed, or when the phone takes over from a server - and then it may be five minutes.
 * Whisper slides over it in blind 30 s windows (without timestamps it cannot place a cut, so a
 * word on the edge is split), and Parakeet's encoder attends over all of it at once, its
 * memory growing with the square of the length. Pieces of at most [PIECE_MS], each cut at the
 * quietest moment of its last [SEARCH_MS], keep both within what they do well.
 */
object LongAudio {

    /** Longer than this, a recording is recognized in pieces; a ChunkPlanner chunk never is. */
    const val SPLIT_ABOVE_MS = 28_000

    /** The longest piece: inside Whisper's 30 s window, with room for its padding. */
    const val PIECE_MS = 25_000

    /** How far back from [PIECE_MS] a cut looks for the quietest moment. */
    const val SEARCH_MS = 3_000

    /** The shortest last piece: never a scrap of a word on its own. */
    const val MIN_REST_MS = 2_000

    private const val FRAME_MS = 20

    /**
     * [samples] at [sampleRate] as pieces, each [IntRange] of sample indices; one piece for a
     * recording up to [SPLIT_ABOVE_MS]. The pieces cover every sample once, in order.
     */
    fun pieces(samples: FloatArray, sampleRate: Int = PcmAudio.DEFAULT_SAMPLE_RATE): List<IntRange> {
        val total = samples.size
        if (total == 0) return emptyList()
        if (total.toLong() * 1000 <= SPLIT_ABOVE_MS.toLong() * sampleRate) return listOf(0 until total)
        val piece = samplesOf(PIECE_MS, sampleRate)
        val search = samplesOf(SEARCH_MS, sampleRate)
        val minRest = samplesOf(MIN_REST_MS, sampleRate)
        val frame = samplesOf(FRAME_MS, sampleRate).coerceAtLeast(1)
        val out = mutableListOf<IntRange>()
        var start = 0
        while (total - start > piece) {
            val from = start + piece - search
            val to = minOf(start + piece, total - minRest)
            val cut = if (to <= from) start + piece else quietest(samples, from, to, frame)
            out += start until cut
            start = cut
        }
        out += start until total
        return out
    }

    /**
     * The middle of the quiet stretch around the quietest [frame] between [from] and [to] - in
     * a pause, its middle, so each side keeps a little silence.
     */
    private fun quietest(samples: FloatArray, from: Int, to: Int, frame: Int): Int {
        val energies = DoubleArray((to - from) / frame) { k ->
            var energy = 0.0
            for (i in from + k * frame until from + (k + 1) * frame) energy += samples[i].toDouble() * samples[i]
            energy
        }
        if (energies.isEmpty()) return from
        var best = 0
        for (k in energies.indices) if (energies[k] < energies[best]) best = k
        // As quiet as the quietest, give or take: the same pause.
        val quiet = energies[best] * 2 + 1e-9
        var first = best
        var last = best
        while (first > 0 && energies[first - 1] <= quiet) first--
        while (last < energies.lastIndex && energies[last + 1] <= quiet) last++
        return from + ((first + last + 1) * frame) / 2
    }

    private fun samplesOf(ms: Int, sampleRate: Int): Int = (ms.toLong() * sampleRate / 1000).toInt()
}
