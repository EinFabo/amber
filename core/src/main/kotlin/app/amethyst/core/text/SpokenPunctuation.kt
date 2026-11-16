package app.amethyst.core.text

/**
 * Punctuation said as a word - "Hallo Komma wie geht's", "see you then period" - becomes the
 * mark, on the phone and without a cleanup, in the dictation's language only: German words in
 * German text, English words in English text. Speech data, matched as spoken in each language
 * (like the filler words), never translated.
 *
 * Conservative: the same words are ordinary words too ("ein Komma", "der springende Punkt",
 * "Punkt zwölf", "Mittelpunkt", "a trial period", "the colon", "New Line Cinema"). A word only
 * becomes a mark where it stands alone and where it could not be a noun of the sentence:
 *
 * - a whole word, never part of another ("Mittelpunkt", "Doppelpunkt-Notation"), and never the
 *   first word - a mark ends something;
 * - not after an article, a possessive, a quantifier or a preposition ("ein Komma", "zum
 *   Punkt", "the period", "without comma"), nor after an attributive adjective ("guter Punkt",
 *   "ein großes Fragezeichen", "a big question mark", "the Oxford comma");
 * - "Komma", "Punkt", "comma" and "period" not before a number ("drei Komma fünf", "Punkt
 *   zwölf", "eins Komma fünfundzwanzig"), "Komma" not after one ("in null Komma nichts"),
 *   "Punkt" not before a time ("um Punkt Mitternacht", "Punkt Viertel nach acht"), and no
 *   mark before "für", "von", "of", "for" ("Punkt für Punkt", "period of time");
 * - English words in lower case, unless they follow a sentence's end ("done. Period.") - a
 *   capital in the middle of a sentence is a name ("Comma", "New Line");
 * - English "period" and "full stop" only at the end, before a mark (said or written) or before
 *   a capital - a new sentence - and not after "a difficult", "the rainy".
 *
 * A mark the recognizer put next to the word ("Hallo, Komma, wie") is taken in by the spoken
 * one rather than doubled; a sentence's end brings a capital after it. A line break keeps what
 * stands before it ("Liebe Anna,") and starts the next line with a capital - in German not
 * after a comma, where the letter goes on in lower case.
 */
object SpokenPunctuation {

    /** The text with the marks; [lineBreaks]: a spoken line break is in it; [trailing]: the mark it ends with, if spoken. */
    class Result(val text: String, val lineBreaks: Boolean, val trailing: Char?)

    private enum class Mark(val text: String, val endsSentence: Boolean = false, val breaks: Boolean = false) {
        COMMA(","),
        PERIOD(".", endsSentence = true),
        QUESTION("?", endsSentence = true),
        EXCLAMATION("!", endsSentence = true),
        COLON(":"),
        LINE("\n", breaks = true),
        PARAGRAPH("\n\n", breaks = true),
    }

    private val GERMAN_PHRASES = mapOf(
        "komma" to Mark.COMMA,
        "punkt" to Mark.PERIOD,
        "fragezeichen" to Mark.QUESTION,
        "ausrufezeichen" to Mark.EXCLAMATION,
        "doppelpunkt" to Mark.COLON,
        "neue zeile" to Mark.LINE,
        "neuer absatz" to Mark.PARAGRAPH,
    )

    private val ENGLISH_PHRASES = mapOf(
        "comma" to Mark.COMMA,
        "period" to Mark.PERIOD,
        "full stop" to Mark.PERIOD,
        "question mark" to Mark.QUESTION,
        "exclamation mark" to Mark.EXCLAMATION,
        "exclamation point" to Mark.EXCLAMATION,
        "colon" to Mark.COLON,
        "new line" to Mark.LINE,
        "new paragraph" to Mark.PARAGRAPH,
    )

    /** Whole words only: no letter, digit, apostrophe or hyphen right before or after. */
    private fun phrases(words: Collection<String>): Regex {
        val alternatives = words.sortedByDescending { it.length }.joinToString("|") { phrase ->
            phrase.split(' ').joinToString("[ \\t]+") { Regex.escape(it) }
        }
        return Regex("(?<![\\p{L}\\p{N}_'’-])(?:$alternatives)(?![\\p{L}\\p{N}_'’-])", RegexOption.IGNORE_CASE)
    }

    private val GERMAN = phrases(GERMAN_PHRASES.keys)
    private val ENGLISH = phrases(ENGLISH_PHRASES.keys)

    /** What a recognizer may have put beside a spoken mark; the spoken mark takes its place. */
    private const val MARKS = ",.;:!?"

    private const val SENTENCE_END = ".!?"

    /** [text] with the marks spoken in it, in German ([german]) or English. */
    fun apply(text: String, german: Boolean): Result {
        val regex = if (german) GERMAN else ENGLISH
        val table = if (german) GERMAN_PHRASES else ENGLISH_PHRASES
        var out = text
        var from = 0
        var lineBreaks = false
        var trailing: Char? = null
        while (from <= out.length) {
            val match = regex.find(out, from) ?: break
            val mark = table.getValue(match.value.lowercase().replace(Regex("[ \\t]+"), " "))
            if (!converts(out, match, mark, german)) {
                from = match.range.last + 1
                continue
            }
            val start = leftEdge(out, match.range.first, mark, floor = from)
            val end = rightEdge(out, match.range.last + 1)
            val before = out.substring(0, start)
            var rest = out.substring(end)
            val capital = when {
                mark.breaks -> !german || !before.endsWith(',')
                else -> mark.endsSentence
            }
            if (capital) rest = capitalized(rest)
            val joint = if (mark.breaks || rest.isEmpty() || rest.startsWith('\n')) "" else " "
            out = before + mark.text + joint + rest
            from = before.length + mark.text.length
            lineBreaks = lineBreaks || mark.breaks
            trailing = if (rest.isBlank() && !mark.breaks) mark.text.single() else null
        }
        return Result(out, lineBreaks, trailing?.takeIf { out.trimEnd().endsWith(it) })
    }

    /** Whether the words at [match] are the mark rather than a word of the sentence. */
    private fun converts(text: String, match: MatchResult, mark: Mark, german: Boolean): Boolean {
        val before = text.substring(0, match.range.first)
        val after = text.substring(match.range.last + 1)
        // A mark ends something: nothing before it, nothing to end.
        if (before.none { it.isLetterOrDigit() }) return false
        val previous = wordsBefore(before, 3)
        val prev1 = previous.getOrNull(0)?.lowercase() ?: return false
        val prev2 = previous.getOrNull(1)?.lowercase()
        val prev3 = previous.getOrNull(2)?.lowercase()
        val restAfter = after.trimStart(' ', '\t')
        val atEnd = restAfter.trimStart { it in MARKS || it == ' ' || it == '\t' }.let { it.isEmpty() || it.startsWith('\n') }
        val markAfter = restAfter.firstOrNull()?.let { it in MARKS } == true
        val next = wordAfter(after)
        val nextLower = next?.lowercase()
        val numberNext = next != null && isNumber(next, german)
        if ((mark == Mark.COMMA || mark == Mark.PERIOD) && numberNext) return false
        return if (german) {
            // "in null Komma nichts", "eins Komma fünfundzwanzig": a number's comma.
            if (mark == Mark.COMMA && isNumber(previous[0], german = true)) return false
            germanConverts(mark, prev1, prev2, prev3, previous[0], nextLower)
        } else {
            val afterSentence = before.trimEnd(' ', '\t').lastOrNull()?.let { it in SENTENCE_END || it == '\n' } == true
            if (match.value.any { it.isUpperCase() } && !afterSentence) return false
            // Another spoken mark after it ("period new paragraph") counts as a mark.
            val spokenNext = ENGLISH.find(restAfter)?.range?.first == 0
            englishConverts(mark, prev1, prev2, prev3, nextLower) &&
                (mark != Mark.PERIOD || atEnd || markAfter || spokenNext || next?.first()?.isUpperCase() == true)
        }
    }

    private fun germanConverts(mark: Mark, prev1: String, prev2: String?, prev3: String?, prev1AsWritten: String, next: String?): Boolean {
        if (prev1 in GERMAN_DETERMINERS || prev1 in GERMAN_PREPOSITIONS || prev1 in GERMAN_NAMING) return false
        if (germanAttributive(prev1)) return false
        // "der springende Punkt", "ein sehr großes Fragezeichen": an adjective - in lower case,
        // with its ending - after an article. A noun before it would be capitalized.
        val adjectiveShape = prev1AsWritten.first().isLowerCase() && GERMAN_ENDINGS.any { prev1.endsWith(it) && prev1.length > it.length + 2 }
        if (adjectiveShape && (prev2 in GERMAN_DETERMINERS || (prev2 in GERMAN_INTENSIFIERS && prev3 in GERMAN_DETERMINERS))) return false
        if (next in GERMAN_FOLLOWERS) return false
        // "um Punkt Mitternacht", "Punkt Viertel nach acht": a time to the minute.
        if (mark == Mark.PERIOD && next in GERMAN_TIME_FOLLOWERS) return false
        return true
    }

    /**
     * Whether [word] is a number: digits, or a number word - in German also one put together
     * ("fünfundzwanzig", "dreihundertzwölf", "zweitausend"), in English one with hyphens
     * ("twenty-five").
     */
    private fun isNumber(word: String, german: Boolean): Boolean {
        if (word.first().isDigit()) return true
        val lower = word.lowercase()
        if (!german) return lower.split('-').all { it in ENGLISH_NUMBERS }
        return lower in GERMAN_NUMBERS || (lower !in GERMAN_NUMBER_PARTS_ALONE && GERMAN_COMPOUND_NUMBER.matches(lower))
    }

    private fun englishConverts(mark: Mark, prev1: String, prev2: String?, prev3: String?, next: String?): Boolean {
        if (prev1 in ENGLISH_DETERMINERS || prev1 in ENGLISH_PREPOSITIONS || prev1 in ENGLISH_NAMING || prev1 in ENGLISH_MODIFIERS) return false
        if (mark == Mark.PERIOD) {
            // "a difficult period", "a very long period": the noun of a phrase.
            if (prev2 in ENGLISH_DETERMINERS) return false
            if (prev2 in ENGLISH_INTENSIFIERS && prev3 in ENGLISH_DETERMINERS) return false
        }
        if (next in ENGLISH_FOLLOWERS) return false
        if (mark == Mark.COMMA && next in COMMA_FOLLOWERS) return false
        if (mark == Mark.COLON && next in COLON_FOLLOWERS) return false
        return true
    }

    /** "guter", "nächsten", "großes": an adjective of the known kind with an ending - attributive. */
    private fun germanAttributive(word: String): Boolean =
        GERMAN_ADJECTIVES.any { stem -> word.startsWith(stem) && word.substring(stem.length) in GERMAN_ENDINGS }

    /** Up to [n] words before the end of [text], the nearest first, without the marks around them. */
    private fun wordsBefore(text: String, n: Int): List<String> =
        text.split(' ', '\t', '\n').asReversed().asSequence()
            .map { token -> token.trim { !it.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
            .take(n)
            .toList()

    /** The next word after [text]'s start, past spaces and marks, without the marks around it. */
    private fun wordAfter(text: String): String? =
        text.trimStart { it == ' ' || it == '\t' || it in MARKS }
            .takeWhile { !it.isWhitespace() }
            .trim { !it.isLetterOrDigit() }
            .takeIf { it.isNotEmpty() }

    /**
     * Where the replacement starts: back over the spaces before the word and, for a mark, over
     * a mark the recognizer put there - never into what a spoken mark already wrote ([floor]),
     * so "Fragezeichen Ausrufezeichen" stays "?!". A line break keeps the mark before it.
     */
    private fun leftEdge(text: String, start: Int, mark: Mark, floor: Int): Int {
        var i = start
        while (i > floor && (text[i - 1] == ' ' || text[i - 1] == '\t' || (!mark.breaks && text[i - 1] in MARKS))) i--
        return i
    }

    /** Where the rest starts: past the spaces and any mark the recognizer put after the word. */
    private fun rightEdge(text: String, end: Int): Int {
        var i = end
        while (i < text.length && (text[i] == ' ' || text[i] == '\t' || text[i] in MARKS)) i++
        return i
    }

    private fun capitalized(rest: String): String {
        val i = rest.indexOfFirst { !it.isWhitespace() }
        if (i < 0 || !rest[i].isLowerCase()) return rest
        return rest.substring(0, i) + rest[i].titlecaseChar() + rest.substring(i + 1)
    }

    // --- German ------------------------------------------------------------------------

    private val GERMAN_DETERMINERS = setOf(
        "der", "die", "das", "den", "dem", "des",
        "ein", "eine", "einen", "einem", "einer", "eines",
        "kein", "keine", "keinen", "keinem", "keiner", "keines",
        "mein", "meine", "meinen", "meinem", "meiner", "meines",
        "dein", "deine", "deinen", "deinem", "deiner", "deines",
        "seine", "seinen", "seinem", "seiner", "seines",
        "ihre", "ihren", "ihrem", "ihrer", "ihres",
        "unser", "unsere", "unseren", "unserem", "unserer", "unseres",
        "euer", "eure", "euren", "eurem", "eurer", "eures",
        "dieser", "diese", "diesen", "diesem", "dieses",
        "jeder", "jede", "jeden", "jedem", "jedes",
        "welcher", "welche", "welchen", "welchem", "welches",
        "jener", "jene", "jenen", "jenem", "jenes",
        "manche", "manchen", "solche", "solchen", "viele", "vielen", "einige", "einigen", "mehrere", "paar", "beide", "beiden",
    )

    /**
     * Prepositions that come before a noun. Not the ones that end a sentence as a verb's part
     * ("Ruf mich an Punkt", "Kommst du mit Fragezeichen"): an, auf, aus, mit, vor, nach, um.
     */
    private val GERMAN_PREPOSITIONS = setOf(
        "zum", "zur", "am", "im", "ins", "vom", "beim", "bis", "von", "für", "ohne", "gegen", "zwischen", "pro", "je", "in", "bei", "per",
    )

    /** "das Wort Komma", "setz Komma": the word is talked about, not said as the mark. */
    private val GERMAN_NAMING = setOf("wort", "zeichen", "namens", "heißt", "sagt", "sag", "sagte", "setz", "setze", "setzen", "tippe", "tipp")

    private val GERMAN_ENDINGS = listOf("e", "er", "en", "em", "es")

    /** Adjectives that stand before these nouns; with an ending they are attributive ("guter Punkt"). */
    private val GERMAN_ADJECTIVES = listOf(
        "gut", "wichtig", "interessant", "berechtigt", "spannend", "springend", "entscheidend", "zentral",
        "letzt", "nächst", "erst", "zweit", "dritt", "einzig", "weiter", "ander", "toll", "schwach", "stark",
        "kritisch", "heikl", "wund", "tot", "neu", "klein", "groß", "riesig", "dick", "fett", "halb", "ganz",
        "schwarz", "rot", "grün", "blau", "weiß", "gelb", "fehlend", "überflüssig", "falsch", "richtig",
    )

    private val GERMAN_INTENSIFIERS = setOf("sehr", "ganz", "wirklich", "echt", "total", "so", "ziemlich", "besonders", "recht")

    /** "Punkt für Punkt", "Komma von": the word is a noun with what follows. */
    private val GERMAN_FOLLOWERS = setOf("für", "von", "vom", "des")

    private val GERMAN_NUMBERS = setOf(
        "null", "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun", "zehn", "elf", "zwölf",
        "dreizehn", "vierzehn", "fünfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn", "zwanzig", "dreißig",
        "vierzig", "fünfzig", "sechzig", "siebzig", "achtzig", "neunzig", "hundert", "tausend", "halb",
    )

    /** The parts German number words are put together from ("ein-und-zwanzig", "sech-zehn"). */
    private val GERMAN_COMPOUND_NUMBER = Regex(
        "(?:null|eins|ein|zwei|zwo|drei|vier|fünf|sechs|sech|sieben|sieb|acht|neun|zehn|elf|zwölf|zwanzig|dreißig|" +
            "vierzig|fünfzig|sechzig|siebzig|achtzig|neunzig|hundert|tausend|und)+",
    )

    /** Parts that alone are other words: an article, "and", a syllable. */
    private val GERMAN_NUMBER_PARTS_ALONE = setOf("ein", "und", "sech", "sieb")

    /** Words after "Punkt" that make it a time to the minute ("Punkt Mitternacht"). */
    private val GERMAN_TIME_FOLLOWERS = setOf("mitternacht", "mittag", "viertel", "dreiviertel", "uhr", "halb")

    // --- English -----------------------------------------------------------------------

    private val ENGLISH_DETERMINERS = setOf(
        "a", "an", "the", "this", "that", "these", "those", "my", "your", "his", "her", "its", "our", "their",
        "no", "any", "each", "every", "another", "some", "which", "what", "whose", "one", "many", "several",
    )

    /** Before a noun only - not "up", "off", "back", "on", which end sentences ("call me back period"). */
    private val ENGLISH_PREPOSITIONS = setOf("of", "for", "without", "by", "from", "into", "during", "per", "than")

    private val ENGLISH_NAMING = setOf("word", "sign", "symbol", "called", "named", "say", "says", "said", "type", "typed")

    /** Words that make these a noun: "the Oxford comma", "a trial period", "a big question mark". */
    private val ENGLISH_MODIFIERS = setOf(
        "oxford", "serial", "inverted", "decimal", "missing", "stray", "extra", "single", "double",
        "trial", "grace", "waiting", "notice", "cooling", "billing", "reporting", "probation", "probationary",
        "transition", "transitional", "holiday", "rainy", "given", "certain", "same", "initial", "introductory",
        "menstrual", "historical", "study", "rest", "sample", "review", "election", "time", "school", "test",
        "whole", "entire", "giant", "huge", "big", "small", "little", "tiny", "new", "final", "last", "first", "next",
        "sigmoid", "ascending", "descending", "transverse",
    )

    private val ENGLISH_INTENSIFIERS = setOf("very", "really", "quite", "so", "pretty", "extremely", "rather", "fairly")

    /** "period of time", "comma for": a noun with what follows. */
    private val ENGLISH_FOLLOWERS = setOf("of", "for")
    private val COMMA_FOLLOWERS = setOf("separated", "delimited", "splice")
    private val COLON_FOLLOWERS = setOf("cancer", "surgery", "polyps", "health", "cleanse", "cleansing")

    private val ENGLISH_NUMBERS = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty",
        "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand",
    )
}
