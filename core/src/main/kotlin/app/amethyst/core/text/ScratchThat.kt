package app.amethyst.core.text

/**
 * "Scratch that" / "streich das" at the very end of a dictation takes back the sentence before
 * it: both go, and what came earlier stays. Said twice at the end, two sentences go. Only these
 * explicit phrases, only at the end - anywhere else they are words of the text ("streich das
 * Zimmer blau"). Speech data, matched as spoken; both languages always, since neither phrase
 * means anything in the other.
 *
 * Only a phrase that stands as its own sentence: at the start, after a sentence's end or a
 * comma, or after an ordinary word when the recognizer wrote no marks ("Ich komme um drei
 * streich das"). Not after words that make it part of a sentence - "Ich streich das.", "Bitte
 * streich das.", "Dann streich das.", "Don't scratch that.", and "Bitte, streich das" - which
 * go in as they are.
 *
 * It runs before the spoken punctuation turns "Punkt" into ".", so a spoken sentence end
 * ("Punkt", "Fragezeichen", "period", "new line" ...) ends a sentence here too: "Hallo Anna
 * Punkt ich komme später Punkt streich das" keeps "Hallo Anna Punkt", which becomes "Hallo
 * Anna." afterwards. A word that only looks like one ("der springende Punkt") can only make
 * less go, never more.
 *
 * A "." after a known abbreviation ("Dr.", "z.B.", "usw.", "Mr.", "e.g.") or an initial, or
 * before a word in lower case, ends no sentence.
 *
 * Not on the list: "vergiss das", "lösch das", "delete that" - people say them to each other
 * ("Ach, vergiss das."), and such a message would vanish.
 *
 * As a recognizer may write the German phrase: "streiche das", and "streich dass" / "daß" - no
 * sentence ends in "dass", so that one is always the phrase misheard. "Streicht das" only as a
 * sentence of its own (at the start, after a sentence's end or a line break): after a word it
 * is a sentence's verb ("Anna streicht das.").
 */
object ScratchThat {

    /** The phrases, as spoken. */
    val PHRASES = listOf("scratch that", "streich das")

    /** What became of a phrase at the end, for the diagnostics - never the words themselves. */
    enum class Verdict {
        /** Nothing like it at the end. */
        NOT_SAID,

        /** Taken back, with the sentence before it ([Result.sentences]). */
        TOOK_BACK,

        /** Said as part of a sentence ("Ich streich das."): kept as words. */
        PART_OF_A_SENTENCE,

        /** The last words start like it ("streichen das", "scratched that") without being it. */
        LOOKS_LIKE_IT,
    }

    /**
     * [text] after the scratch; [scratched]: a phrase was there and went, with [sentences]
     * sentences before it.
     */
    class Result(val text: String, val scratched: Boolean, val verdict: Verdict = Verdict.NOT_SAID, val sentences: Int = 0) {
        /** Nothing worth inserting is left: no letter, no digit. */
        val nothingLeft: Boolean get() = scratched && text.none { it.isLetterOrDigit() }
    }

    /** The phrase and how a recognizer may write it, wherever a sentence of its own can stand. */
    private const val PHRASE = "scratch[ \\t]+that|streiche?[ \\t]+(?:das|dass|daß)"

    /** "Streicht das": only where nothing before it could be its subject. */
    private const val PHRASE_ALONE = "streicht[ \\t]+(?:das|dass|daß)"

    /** The phrase as the last words: a whole word first, marks after it allowed. */
    private val AT_END = Regex(
        "(?<![\\p{L}\\p{N}_'’-])(?:($PHRASE)|$PHRASE_ALONE)[\\s,.;:!?]*$",
        RegexOption.IGNORE_CASE,
    )

    /** The last words start like the phrase: "streich", "scratch" among the last three. */
    private val LOOKALIKE = Regex(
        "(?<![\\p{L}\\p{N}_'’-])(?:streich|scratch)\\S*(?:\\s+\\S+){0,2}[\\s,.;:!?]*$",
        RegexOption.IGNORE_CASE,
    )

    private const val SENTENCE_END = ".!?"

    /**
     * Sentence ends said as words, German and English, as [SpokenPunctuation] knows them -
     * speech data, matched as spoken.
     */
    private val SPOKEN_ENDS = listOf(
        "punkt", "fragezeichen", "ausrufezeichen", "neue zeile", "neuer absatz",
        "period", "full stop", "question mark", "exclamation mark", "exclamation point", "new line", "new paragraph",
    )

    private val SPOKEN_END = Regex(
        "(?<![\\p{L}\\p{N}_'’-])(?:" + SPOKEN_ENDS.joinToString("|") { it.replace(" ", "[ \\t]+") } + ")(?![\\p{L}\\p{N}_'’-])",
        RegexOption.IGNORE_CASE,
    )

    private val SPOKEN_END_AT_END = Regex(SPOKEN_END.pattern + "[\\s,;:]*$", RegexOption.IGNORE_CASE)

    /**
     * Words right before the phrase that make it part of a sentence: a subject, a request, a
     * negation, a link ("Ich streich das", "Dann streich das", "Don't scratch that").
     */
    private val PART_OF_A_SENTENCE = setOf(
        // German
        "ich", "du", "er", "sie", "es", "wir", "ihr", "man", "bitte", "dann", "nicht", "nie", "doch", "mal", "einfach",
        "auch", "noch", "gern", "gerne", "lieber", "schon", "jetzt", "gleich", "und", "oder", "aber", "sonst",
        // English
        "i", "you", "we", "they", "he", "she", "it", "please", "then", "don't", "dont", "do", "not", "never", "just",
        "also", "and", "or", "but", "to", "will", "can", "could", "should", "would", "must", "let's", "i'll", "we'll",
        "you'll", "they'll", "won't", "can't", "didn't", "now",
    )

    /** Words that make it a request even across a comma ("Bitte, streich das"). */
    private val REQUEST = setOf("bitte", "please")

    /**
     * Abbreviations whose "." ends no sentence, lower case and without their last ".". Only those
     * that hardly ever end one ("usw.", "etc.", "p.m." often do): a sentence end missed would
     * take back one sentence too many.
     */
    private val ABBREVIATIONS = setOf(
        // German
        "dr", "prof", "hr", "fr", "frl", "nr", "st", "z.b", "zb", "d.h", "dh", "bzw", "ca", "evtl", "ggf", "inkl",
        "exkl", "bspw", "vgl", "sog", "z.t", "z.hd", "tel", "mio", "mrd", "abs", "geb", "allg", "ehem", "eigtl",
        "gem", "zzgl", "u.u", "v.a",
        // English
        "mr", "mrs", "ms", "e.g", "i.e", "vs", "approx", "dept",
    )

    /** Months, after which a number's "." is a German date's ("am 3. Mai"), not a sentence's end. */
    private val MONTHS = setOf(
        "januar", "jänner", "februar", "märz", "april", "mai", "juni", "juli", "august", "september", "oktober",
        "november", "dezember",
    )

    fun apply(text: String): Result {
        var rest = text.trimEnd()
        var count = 0
        var inASentence = false
        while (true) {
            val match = AT_END.find(rest) ?: break
            val before = rest.substring(0, match.range.first)
            // "Streicht das" needs a sentence's end or nothing before it; the phrase itself
            // also a comma or an ordinary word.
            val alone = if (match.groups[1] != null) standsAlone(before) else afterSentenceEnd(before)
            if (!alone) {
                inASentence = true
                break
            }
            rest = before.trimEnd()
            count++
        }
        if (count == 0) {
            val verdict = when {
                inASentence -> Verdict.PART_OF_A_SENTENCE
                LOOKALIKE.containsMatchIn(text.trimEnd()) -> Verdict.LOOKS_LIKE_IT
                else -> Verdict.NOT_SAID
            }
            return Result(text, scratched = false, verdict = verdict)
        }
        repeat(count) { rest = withoutLastSentence(rest) }
        return Result(rest, scratched = true, verdict = Verdict.TOOK_BACK, sentences = count)
    }

    /** Nothing before it, a sentence's end (written or said) or a line break. */
    private fun afterSentenceEnd(before: String): Boolean {
        val trimmed = before.trimEnd()
        if (trimmed.isEmpty()) return true
        if ('\n' in before.substring(trimmed.length)) return true
        if (trimmed.last() in SENTENCE_END) return true
        return SPOKEN_END_AT_END.containsMatchIn(trimmed)
    }

    /**
     * Whether the phrase after [before] is a sentence of its own: nothing before it, a
     * sentence's end (written or said), or a word that does not make it part of a sentence -
     * also across a comma ("Bitte, streich das" is still a request).
     */
    private fun standsAlone(before: String): Boolean {
        if (afterSentenceEnd(before)) return true
        val trimmed = before.trimEnd()
        val comma = trimmed.last() in ",;:"
        val word = trimmed.trimEnd { it in ",;:" || it.isWhitespace() }
            .takeLastWhile { !it.isWhitespace() }
            .trim { !it.isLetterOrDigit() && it != '\'' && it != '’' }
            .replace('’', '\'')
            .lowercase()
        return word !in (if (comma) REQUEST else PART_OF_A_SENTENCE)
    }

    /**
     * [text] up to the end of its last sentence but one: back to the last sentence end - a ".",
     * "!" or "?" that a space follows, a spoken one, or a line break - nothing when there is none.
     */
    private fun withoutLastSentence(text: String): String {
        // The marks, commas and spoken ends that close the sentence being dropped are part of it.
        var sentence = text
        while (true) {
            val trimmed = sentence.trimEnd { it.isWhitespace() || it in SENTENCE_END || it in ",;:" }
            val spoken = SPOKEN_END_AT_END.find(trimmed)
            sentence = if (spoken != null) trimmed.substring(0, spoken.range.first) else trimmed
            if (spoken == null) break
        }
        var cut = -1
        for (i in sentence.indices) {
            val c = sentence[i]
            if (c == '\n') cut = i
            if (c in SENTENCE_END && sentence.getOrNull(i + 1)?.isWhitespace() == true && endsSentence(sentence, i)) cut = i + 1
        }
        for (match in SPOKEN_END.findAll(sentence)) {
            if (match.range.last + 1 > cut) cut = match.range.last + 1
        }
        if (cut <= 0) return ""
        return sentence.substring(0, cut).trimEnd()
    }

    /** Whether the mark at [i] in [text], a space after it, ends a sentence. */
    private fun endsSentence(text: String, i: Int): Boolean {
        if (text[i] != '.') return true
        val word = text.substring(0, i).takeLastWhile { !it.isWhitespace() }.trimStart { !it.isLetterOrDigit() }
        val next = text.substring(i + 1).trimStart().takeWhile { !it.isWhitespace() }.trimEnd { !it.isLetterOrDigit() }
        if (word.lowercase() in ABBREVIATIONS) return false
        // An initial ("J. Smith") or a letter of an abbreviation written apart ("z. B.").
        if (word.length == 1 && word[0].isLetter()) return false
        // "ca. drei", "z. B. morgen": a sentence starts with a capital.
        if (next.firstOrNull()?.isLowerCase() == true) return false
        // "am 3. Mai": a German date.
        if (word.isNotEmpty() && word.all { it.isDigit() } && next.lowercase() in MONTHS) return false
        return true
    }
}
