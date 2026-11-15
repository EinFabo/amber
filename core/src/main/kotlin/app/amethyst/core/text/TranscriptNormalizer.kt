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

    /** Spaces and tabs - whitespace that is not a line break. */
    private val HORIZONTAL = Regex("[\\t\\u000B\\u000C\\r ]+")
    private val SPACES_AROUND_BREAK = Regex(" *\\n *")
    private val BLANK_LINES = Regex("\\n{3,}")

    /**
     * Characters that change how text is shown without being text: C0/C1 controls (tab and
     * line breaks are whitespace and handled below), bidi overrides and isolates that can make
     * "txt.exe" read "exe.txt", zero-width spaces and the BOM. A server or a cleanup model could
     * send them; they have no business in a dictation. Zero-width joiners stay (emoji, scripts).
     * So do soft hyphens, the word joiner and the invisible operators (U+2060-2064).
     */
    private val INVISIBLE = Regex("[\\u0000-\\u0008\\u000B\\u000E-\\u001F\\u007F-\\u009F\\u00AD\\u061C\\u200B\\u200E\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF]")

    /**
     * Unicode's tag characters (U+E0000-E007F) are invisible and can carry a hidden message
     * for whatever reads the field next. Their one honest use stays: after a black flag they
     * spell the flags of England, Scotland and Wales.
     */
    private fun withoutTags(text: String): String {
        if (text.none { it == TAG_LEAD }) return text
        val out = StringBuilder(text.length)
        var inFlag = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val tag = cp in 0xE0000..0xE007F
            if (!tag || inFlag) out.appendCodePoint(cp)
            // The flag's letters are tags from U+E0020; U+E007F ends it.
            inFlag = cp == BLACK_FLAG || (tag && inFlag && cp >= 0xE0020 && cp != 0xE007F)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /** The high surrogate every tag character starts with. */
    private const val TAG_LEAD = '\uDB40'
    private const val BLACK_FLAG = 0x1F3F4

    /** Unicode's own line and paragraph separators, which some fields render as breaks. */
    private val UNICODE_BREAKS = Regex("[\\u2028\\u2029]")
    private val SPACE_BEFORE_PUNCTUATION = Regex("\\s+([,.;:!?])")
    private val REPEATED_PUNCTUATION = Regex("([,.;:!?])\\1+")
    private val TRIM_CHARS = charArrayOf(' ', '\t', ',', ';', ':')

    /**
     * [language] is what the engine detected or was set to ("de", "de-DE", "de,en"); it decides
     * whether German comma rules apply, together with the words themselves. With
     * [keepLineBreaks] - a text a cleanup model formatted - paragraphs and list lines survive
     * (at most one blank line between them); otherwise the text becomes one line.
     */
    fun normalize(
        raw: String,
        options: NormalizeOptions = NormalizeOptions(),
        language: String? = null,
        keepLineBreaks: Boolean = false,
    ): String {
        var text = withoutTags(raw.replace(INVISIBLE, "")).replace(UNICODE_BREAKS, if (keepLineBreaks) "\n" else " ").trim()
        if (text.isEmpty()) return ""
        val spaced = text.replace(WHITESPACE, " ")
        val german = GermanCommas.isGerman(language, spaced)
        // "um" and "eh" are words in German: dropped only where the text is plainly English.
        val english = !german && GermanCommas.isEnglish(language, spaced)
        // Marks said as words, in the dictation's language. A raw transcript's own line breaks
        // never count ([spaced]); the spoken ones do, as a cleanup's do.
        var breaks = keepLineBreaks
        var spokenEnd: Char? = null
        if (options.spokenPunctuation && (german || english)) {
            val base = if (keepLineBreaks) text else spaced
            val spoken = SpokenPunctuation.apply(base, german)
            if (spoken.text != base) {
                text = spoken.text
                breaks = keepLineBreaks || spoken.lineBreaks
                spokenEnd = spoken.trailing
            }
        }
        val lines = if (breaks) text.split('\n') else listOf(text)

        text = lines.joinToString("\n") { line -> tidyLine(line, options, german, english) }
        text = if (breaks) {
            text.replace(SPACES_AROUND_BREAK, "\n").replace(BLANK_LINES, "\n\n").trim('\n', ' ')
        } else {
            text
        }
        val out = text.trim(*TRIM_CHARS)
        // A comma or colon said at the very end was meant ("Liebe Anna Komma"): the next
        // dictation goes on after it. One the recognizer left there still goes.
        return if (spokenEnd != null && out.isNotEmpty() && !out.endsWith(spokenEnd)) out + spokenEnd else out
    }

    /** One line: fillers, spacing, punctuation, German commas. */
    private fun tidyLine(raw: String, options: NormalizeOptions, german: Boolean, english: Boolean): String {
        var text = raw
        if (options.stripFillers) {
            val fillers = if (english) options.fillers + ENGLISH_ONLY_FILLERS else options.fillers
            text = text
                .split(WHITESPACE)
                .filterNot { token -> coreWord(token) in fillers }
                .joinToString(" ")
        }

        if (options.collapseWhitespace) {
            text = text.replace(WHITESPACE, " ")
        } else {
            text = text.replace(HORIZONTAL, " ")
        }

        // Only spaces here: a line may well end in "," ("Hallo Anna,") or ":" ("Einkaufen:").
        // Stray marks at the very start and end of the whole text go in normalize().
        text = text
            .replace(SPACE_BEFORE_PUNCTUATION, "$1")
            .replace(REPEATED_PUNCTUATION, "$1")
            .trim()

        if (options.germanCommas && german && text.isNotEmpty()) {
            // The commas German needs, then out with the ones it has none for (", und" in a
            // list) - set by the engine or the cleanup model, never by apply().
            text = GermanCommas.dropBeforeJoiners(GermanCommas.apply(text))
        }
        return text
    }

    /**
     * For a field that holds one line (a search box, a subject line): the line breaks of a
     * formatted text become spaces, so nothing is cut off or swallowed at the first break.
     */
    fun oneLine(text: String): String = text.replace(Regex(" *\\n+ *"), " ").trim()

    /** The bare word behind a token, so "ähm," is recognised as a filler. */
    private fun coreWord(token: String): String =
        token.trim(',', '.', ';', ':', '!', '?', '-', '"', '\'').lowercase()
}

data class NormalizeOptions(
    val collapseWhitespace: Boolean = true,
    val stripFillers: Boolean = true,
    val fillers: Set<String> = TranscriptNormalizer.DEFAULT_FILLERS,
    /**
     * The commas German needs before "dass", "weil", "um ... zu", and none before a list's
     * "und"; see [GermanCommas].
     */
    val germanCommas: Boolean = true,
    /**
     * "Komma", "neue Zeile", "comma", "new line" become the marks ([SpokenPunctuation]). Off by
     * default here; the setting ([app.amethyst.core.settings.WordsSettings]) is on by default.
     */
    val spokenPunctuation: Boolean = false,
)
