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
        "uh", "uhm", "erm",
    )

    /**
     * English hesitations that are words in German - "um zu", "um 10 Uhr", "das ist eh egal" -
     * so they are only dropped from text that is not German. Until 0.16 they were dropped
     * everywhere: 21 times in 80 German test sentences a real "um" went missing.
     */
    val ENGLISH_ONLY_FILLERS: Set<String> = setOf("um", "eh")

    private val WHITESPACE = Regex("\\s+")
    private val SPACE_BEFORE_PUNCTUATION = Regex("\\s+([,.;:!?])")
    private val REPEATED_PUNCTUATION = Regex("([,.;:!?])\\1+")
    private val TRIM_CHARS = charArrayOf(' ', '\t', ',', ';', ':')

    /**
     * [language] is what the engine detected or was set to ("de", "de-DE", "de,en"); it decides
     * whether German comma rules apply, together with the words themselves.
     */
    fun normalize(raw: String, options: NormalizeOptions = NormalizeOptions(), language: String? = null): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""
        val german = GermanCommas.isGerman(language, text.replace(WHITESPACE, " "))

        if (options.stripFillers) {
            val fillers = if (german) options.fillers else options.fillers + ENGLISH_ONLY_FILLERS
            text = text
                .split(WHITESPACE)
                .filterNot { token -> coreWord(token) in fillers }
                .joinToString(" ")
        }

        if (options.collapseWhitespace) {
            text = text.replace(WHITESPACE, " ")
        }

        text = text
            .replace(SPACE_BEFORE_PUNCTUATION, "$1")
            .replace(REPEATED_PUNCTUATION, "$1")
            .trim(*TRIM_CHARS)

        if (options.germanCommas && german) {
            text = GermanCommas.apply(text)
        }
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
    /** The commas German needs before "dass", "weil", "um ... zu"; see [GermanCommas]. */
    val germanCommas: Boolean = true,
)
