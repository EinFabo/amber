package app.amethyst.core.text

/**
 * Deterministic tidy-up of raw engine output.
 *
 * Wispr Flow does this with an LLM round trip. A good share of the benefit - collapsed
 * whitespace, dropped hesitation sounds, no space before a comma - needs no model at all,
 * costs nothing and cannot hallucinate, so Amethyst does it locally and unconditionally.
 * The LLM pass stays a separate, optional stage on top.
 */
object TranscriptNormalizer {

    /** Hesitation sounds, German and English. Deliberately short: only unambiguous ones. */
    val DEFAULT_FILLERS: Set<String> = setOf(
        "äh", "ähm", "ähh", "öh", "öhm", "hm", "hmm", "mhm", "mh",
        "um", "uh", "uhm", "erm", "eh",
    )

    private val WHITESPACE = Regex("\\s+")
    private val SPACE_BEFORE_PUNCTUATION = Regex("\\s+([,.;:!?])")
    private val REPEATED_PUNCTUATION = Regex("([,.;:!?])\\1+")
    private val TRIM_CHARS = charArrayOf(' ', '\t', ',', ';', ':')

    fun normalize(raw: String, options: NormalizeOptions = NormalizeOptions()): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        if (options.stripFillers) {
            text = text
                .split(WHITESPACE)
                .filterNot { token -> coreWord(token) in options.fillers }
                .joinToString(" ")
        }

        if (options.collapseWhitespace) {
            text = text.replace(WHITESPACE, " ")
        }

        text = text
            .replace(SPACE_BEFORE_PUNCTUATION, "$1")
            .replace(REPEATED_PUNCTUATION, "$1")
            .trim(*TRIM_CHARS)

        return text
    }

    /** The bare word behind a token, so "ähm," is recognised as a filler. */
    private fun coreWord(token: String): String =
        token.trim(',', '.', ';', ':', '!', '?', '-', '"', '\'').lowercase()
}

data class NormalizeOptions(
    val collapseWhitespace: Boolean = true,
    val stripFillers: Boolean = true,
    val fillers: Set<String> = TranscriptNormalizer.DEFAULT_FILLERS,
)
