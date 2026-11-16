package app.amethyst.core.text

/**
 * "Scratch that" / "streich das" at the very end of a dictation takes back the sentence before
 * it: both go, and what came earlier stays. Said twice at the end, two sentences go. Only these
 * explicit phrases, only at the end - anywhere else they are words of the text ("streich das
 * Zimmer blau"). Speech data, matched as spoken; both languages always, since neither phrase
 * means anything in the other.
 *
 * Not on the list: "vergiss das", "lösch das", "delete that" - people say them to each other
 * ("Ach, vergiss das."), and such a message would vanish.
 */
object ScratchThat {

    /** The phrases, as spoken. */
    val PHRASES = listOf("scratch that", "streich das")

    /** [text] after the scratch; [scratched]: a phrase was there and went. */
    class Result(val text: String, val scratched: Boolean)

    /** A phrase as the last words: a whole word first, marks after it allowed. */
    private val AT_END = Regex(
        "(?<![\\p{L}\\p{N}_'’-])(?:" + PHRASES.joinToString("|") { it.replace(" ", "[ \\t]+") } + ")[\\s,.;:!?]*$",
        RegexOption.IGNORE_CASE,
    )

    private const val SENTENCE_END = ".!?"

    fun apply(text: String): Result {
        var rest = text.trimEnd()
        var count = 0
        while (true) {
            val match = AT_END.find(rest) ?: break
            rest = rest.substring(0, match.range.first).trimEnd()
            count++
        }
        if (count == 0) return Result(text, scratched = false)
        repeat(count) { rest = withoutLastSentence(rest) }
        return Result(rest, scratched = true)
    }

    /**
     * [text] up to the end of its last sentence but one: back to the last ".", "!" or "?" that a
     * space follows, or to a line break - nothing when there is none.
     */
    private fun withoutLastSentence(text: String): String {
        // The marks and commas that close the sentence being dropped are part of it.
        val sentence = text.trimEnd { it.isWhitespace() || it in SENTENCE_END || it in ",;:" }
        for (i in sentence.indices.reversed()) {
            val c = sentence[i]
            if (c == '\n') return sentence.substring(0, i).trimEnd()
            if (c in SENTENCE_END && sentence.getOrNull(i + 1)?.isWhitespace() == true) return sentence.substring(0, i + 1)
        }
        return ""
    }
}
