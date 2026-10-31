package app.amethyst.core.commands

/**
 * What one voice command went through, step by step, in short English lines - what the rules
 * made of the words, what the executor picked, what each step did, why it stopped - for
 * Diagnostics, where Fabi copies it from. Never for logcat: the lines name what was heard,
 * apps and screens.
 *
 * Bounded, so a thirty-step "ganz nach unten" stays readable: at most [maxLines] lines of at
 * most [maxChars] characters. Past the cap the first lines (what was understood) and the last
 * ones (how it ended) stay, and the middle is counted in a line of its own.
 */
class CommandTrace(private val maxLines: Int = MAX_LINES, private val maxChars: Int = MAX_CHARS) {

    init {
        require(maxLines >= 3) { "maxLines must leave room for a head, a gap and a tail, was $maxLines" }
        require(maxChars >= 2) { "maxChars must be at least 2, was $maxChars" }
    }

    private val headSize = maxLines / 2
    private val head = ArrayList<String>()

    /** Up to the cap it holds the rest; past it, the newest lines only. */
    private val tail = ArrayDeque<String>()
    private var dropped = 0

    /** As noted, bounded; a copy. */
    val lines: List<String>
        @Synchronized get() = when (dropped) {
            0 -> head + tail
            // One more tail line makes room for the gap line.
            else -> head + "… ${dropped + 1} lines left out" + tail.drop(1)
        }

    /** One line; a line break in it becomes a space, a blank one is dropped. */
    @Synchronized
    fun note(line: String) {
        val clean = LINE_BREAKS.replace(line, " ").trim()
        if (clean.isEmpty()) return
        val cut = if (clean.length > maxChars) clean.take(maxChars - 1) + "…" else clean
        if (head.size < headSize) {
            head += cut
            return
        }
        tail.addLast(cut)
        if (tail.size > maxLines - headSize) {
            tail.removeFirst()
            dropped++
        }
    }

    fun addAll(lines: List<String>) = lines.forEach(::note)

    companion object {
        const val MAX_LINES = 40
        const val MAX_CHARS = 160
        private val LINE_BREAKS = Regex("[\\r\\n]+")
    }
}
