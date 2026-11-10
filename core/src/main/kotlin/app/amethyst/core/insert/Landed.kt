package app.amethyst.core.insert

/**
 * Whether inserted words show up in a field read back afterwards, and how long to keep looking.
 *
 * Fields do not hand back exactly what they were given: line breaks become spaces in a one-line
 * field, a messenger turns ":)" into an emoji or "**x**" into bold, the case of the first
 * letter follows the seam. A long dictation - five minutes are several thousand characters -
 * almost always differs somewhere, and an exact comparison then reported text as lost that the
 * user could see in the field.
 *
 * So the comparison is loose where it can be and strict where it matters:
 * - case and whitespace do not count: any run of spaces, tabs and line breaks is one space;
 * - a short text must be there as a whole;
 * - a long text ([LONG_TEXT] characters on) counts as there when its first and its last
 *   [PIECE] characters are: the middle may have been reformatted, but a text that did not
 *   arrive - or was cut off by a length limit - lacks its end.
 *
 * Every piece is counted before and after: it must be there *once more* than before. A field
 * that already held the same words (Insert again, a repeated "Thank you") would otherwise read
 * as a success before anything arrived. Nothing is ever taken as there that is not - a lost
 * text stays lost, so it is reported, not quietly dropped.
 */
object Landed {

    /** From this length on, start and end are enough. */
    const val LONG_TEXT = 200

    /** How much of the start and the end of a long text must arrive unchanged. */
    const val PIECE = 60

    /** Reads at most, at [InsertionEngine]'s interval of 60 ms: about 3 s. */
    const val MAX_ATTEMPTS = 50

    /** One more read per this many characters past [LONG_TEXT]. */
    private const val CHARS_PER_EXTRA_READ = 100

    /**
     * Whether [expected] arrived in a field that read [before] and now reads [after].
     * [before] null means the field could not be read before: the words only need to be there.
     */
    fun inField(before: String?, after: String, expected: String): Boolean {
        val words = normalize(expected)
        if (words.isEmpty()) return true
        val was = before?.let(::normalize).orEmpty()
        val now = normalize(after)
        if (arrived(was, now, words)) return true
        if (words.length < LONG_TEXT) return false
        return arrived(was, now, words.take(PIECE).trimEnd()) && arrived(was, now, words.takeLast(PIECE).trimStart())
    }

    /**
     * How many times to read the field for a text of [length] characters, starting from
     * [base] for a short one: an app takes longer to take in a long text, and the accessibility
     * read of it is slower too.
     */
    fun attempts(base: Int, length: Int): Int =
        (base + (length - LONG_TEXT).coerceAtLeast(0) / CHARS_PER_EXTRA_READ).coerceAtMost(maxOf(base, MAX_ATTEMPTS))

    private fun arrived(before: String, after: String, piece: String): Boolean =
        count(after, piece) > count(before, piece)

    private fun count(haystack: String, needle: String): Int {
        var found = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return found
            found++
            from = at + needle.length
        }
    }

    /** Lower case, every run of whitespace (no-break spaces too) one space, trimmed. */
    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        var space = false
        for (c in text) {
            if (c.isWhitespace() || c == ' ' || c == ' ' || c == ' ') {
                space = true
                continue
            }
            if (space && out.isNotEmpty()) out.append(' ')
            space = false
            out.append(c.lowercaseChar())
        }
        return out.toString()
    }
}
