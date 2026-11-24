package app.amethyst.core.text

import app.amethyst.core.settings.WritingStyle

/**
 * Whether a cleanup model's reply is still the dictation, tidied - or an answer, a summary, a
 * translation, or a text that says something else. A reply that fails goes nowhere: the
 * dictation is typed as recognized, and the [Verdict]'s reason goes into the diagnostics.
 *
 * Rules, not a model, and each one cheap: the length band, the share of the dictation's own
 * words, the language and the script, the negations and the numbers, a question mark, a
 * preamble, the line breaks. Words of a script written without spaces (Chinese, Japanese,
 * Thai) count character by character; the negation and number words are German and English,
 * and in other languages only numbers in digits are checked. A spoken self-correction ("um drei, nee, um vier") rightly drops a number or a
 * negation - but only one said before the correction marker, in the part taken back. What
 * comes after the last marker is kept, so its negations and numbers must survive; the
 * "added" checks never relax.
 */
object CleanupGuard {

    enum class Verdict(val reason: String) {
        OK("ok"),
        EMPTY("empty reply"),
        QUESTION("question mark lost"),
        LINE_BREAKS("line breaks lost"),
        LENGTH("length changed too much"),
        LANGUAGE("language changed"),
        PREAMBLE("preamble added"),
        OWN_WORDS("too few of the dictation's words"),
        NEGATION_ADDED("negation added"),
        NEGATION_DROPPED("negation dropped"),
        NUMBER_ADDED("number added"),
        NUMBER_DROPPED("number dropped"),
    }

    /** How short a reply may get when the speaker took something back. */
    private const val CORRECTED_LOW = 0.3

    /** Share of the reply's words that must be the dictation's own when the user's instruction may rephrase. */
    const val OWN_WORDS_WITH_INSTRUCTION = 0.4

    /**
     * [language] is what the caller knows of the dictation's language ("de"); the dictation's
     * own words decide first. [ownInstruction]: the user's instruction may rephrase more, so the
     * word test asks for a smaller share - it still catches a translation of the same length.
     */
    fun verdict(
        original: String,
        cleaned: String,
        style: WritingStyle = WritingStyle.AS_SPOKEN,
        ownInstruction: Boolean = false,
        language: String? = null,
    ): Verdict {
        if (cleaned.isBlank()) return Verdict.EMPTY
        // A question that comes back without its question mark was answered, not tidied.
        val greek = hasGreek(original)
        if (asks(original, greek) && !asks(cleaned, greek)) return Verdict.QUESTION
        // Spoken line breaks are the user's; a tidy-up keeps them.
        if (cleaned.count { it == '\n' } < original.count { it == '\n' }) return Verdict.LINE_BREAKS
        val correction = correction(original)
        if (!lengthFits(original, cleaned, style, corrected = correction != null)) return Verdict.LENGTH
        if (languageChanged(original, cleaned, language) || scriptChanged(original, cleaned)) return Verdict.LANGUAGE
        if (hasPreamble(original, cleaned)) return Verdict.PREAMBLE
        if (!ownWordsFit(original, cleaned, style, ownInstruction)) return Verdict.OWN_WORDS
        // The dictation's language, for the one word that is a negation in English only: "not".
        val spoken = LanguageGuess.fromWords(original)
            ?: LanguageGuess.leaning(original)
            ?: LanguageGuess.fromTag(language)
        val negationsBefore = negations(original, spoken)
        val negationsAfter = negations(cleaned, spoken)
        if (negationsAfter > negationsBefore) return Verdict.NEGATION_ADDED
        // Only the part taken back may lose its negations and numbers.
        val kept = correction?.kept ?: original
        if (negationsAfter < negationsBefore && negationsAfter < negations(kept, spoken)) return Verdict.NEGATION_DROPPED
        val before = numberGroups(original)
        val after = numberGroups(cleaned)
        val added = after.count { !it.foundIn(before) }
        // Number words the guard cannot read ("tres", "三") may rightly come back as digits:
        // each then took a word of the dictation with it.
        if (added > 0 && (numberWordsKnown(original, language) || added > lostWords(original, cleaned))) {
            return Verdict.NUMBER_ADDED
        }
        if (numberGroups(kept).any { !it.foundIn(after) }) return Verdict.NUMBER_DROPPED
        return Verdict.OK
    }

    /**
     * A cleanup changes a text's length only so far: tidying neither halves nor doubles it, a
     * note may shrink more, a formal message grow a little more. Very short dictations get
     * room, since one word more or less is a big share of them. A self-correction ([corrected])
     * drops the version taken back, which may be most of what was said.
     */
    private fun lengthFits(original: String, cleaned: String, style: WritingStyle, corrected: Boolean): Boolean {
        val slack = if (original.length < 40) 20 else 0
        val (low, high) = when (style) {
            WritingStyle.AS_SPOKEN, WritingStyle.CASUAL -> 0.5 to 1.5
            WritingStyle.FORMAL -> 0.5 to 2.0
            WritingStyle.CONCISE -> 0.25 to 1.2
        }
        val lowest = if (corrected) minOf(low, CORRECTED_LOW) else low
        return cleaned.length in (original.length * lowest).toInt() - slack..(original.length * high).toInt() + slack
    }

    /**
     * Length alone lets a short translation through ("Ich hab kein Wasser." -> "I have no
     * water."), so most of the reply's words must be the dictation's own: a tidy-up only
     * deletes and fixes a word here and there. A formal message may rephrase more. The same
     * shares as Amber PC's cleanup; a number says nothing about the language, and "zehn" may
     * rightly come back as "10", so a word with a digit in it ("10", "14th") does not count.
     */
    private fun ownWordsFit(original: String, cleaned: String, style: WritingStyle, ownInstruction: Boolean): Boolean {
        val share = if (ownInstruction) {
            OWN_WORDS_WITH_INSTRUCTION
        } else {
            when (style) {
                WritingStyle.AS_SPOKEN, WritingStyle.CASUAL -> 0.7
                WritingStyle.FORMAL -> 0.5
                WritingStyle.CONCISE -> 0.6
            }
        }
        val source = words(original).toSet()
        val reply = words(cleaned).filterNot { word -> word.any(Char::isDigit) }
        return reply.isEmpty() || reply.count { it in source }.toDouble() / reply.size >= share
    }

    /**
     * Only when both sides are sure: the dictation German (by its words, else by the way its
     * few words lean, else by what the caller knows) and the reply clearly English, or the
     * other way round. A short English dictation from an engine set to German counts as English.
     */
    private fun languageChanged(original: String, cleaned: String, language: String?): Boolean {
        val before = LanguageGuess.fromWords(original)
            ?: LanguageGuess.leaning(original)
            ?: LanguageGuess.fromTag(language)?.takeIf { it == "de" || it == "en" }
        val after = LanguageGuess.fromWords(cleaned)
        return before != null && after != null && before != after
    }

    /**
     * A translation into another script: most of the dictation's letters in one script
     * (Cyrillic, Greek, Arabic, Devanagari, Chinese and Japanese, Hangul, Latin...) and few of
     * the reply's. Catches what the German and English words cannot tell, in any language.
     */
    private fun scriptChanged(original: String, cleaned: String): Boolean {
        val before = scripts(original)
        val total = before.values.sum()
        val (main, count) = before.maxByOrNull { it.value } ?: return false
        if (total < MIN_SCRIPT_LETTERS || count < total * MAIN_SCRIPT_SHARE) return false
        val after = scripts(cleaned)
        val afterTotal = after.values.sum()
        return afterTotal > 0 && (after[main] ?: 0) < afterTotal * KEPT_SCRIPT_SHARE
    }

    private const val MIN_SCRIPT_LETTERS = 3
    private const val MAIN_SCRIPT_SHARE = 0.6
    private const val KEPT_SCRIPT_SHARE = 0.3

    /** Letters by script; Chinese characters and Japanese kana count as one. */
    private fun scripts(text: String): Map<String, Int> {
        val counts = HashMap<String, Int>()
        text.codePoints().forEach { cp ->
            if (!Character.isLetter(cp)) return@forEach
            val script = when (val s = Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> "CJK"
                else -> s.name
            }
            counts[script] = (counts[script] ?: 0) + 1
        }
        return counts
    }

    private fun hasGreek(text: String): Boolean =
        text.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.GREEK }

    /** A question mark in any script: "?", the full-width "？", the Arabic "؟" - and in Greek ";". */
    private fun asks(text: String, greek: Boolean): Boolean =
        text.any { it in QUESTION_MARKS || (greek && (it == ';' || it == '\u037E')) }

    private const val QUESTION_MARKS = "?？؟"

    /**
     * Whether the guard reads the dictation's number words: German and English, named so or
     * leaning so by the words ([LanguageGuess.leaning]). In any other language only digits are
     * numbers to it.
     */
    private fun numberWordsKnown(original: String, language: String?): Boolean {
        val base = LanguageGuess.baseOf(language)
        return if (base != null) base == "de" || base == "en" else LanguageGuess.leaning(original) != null
    }

    /** How many of the dictation's words (not counting numbers in digits) the reply no longer has. */
    private fun lostWords(original: String, cleaned: String): Int {
        val left = words(cleaned).groupingBy { it }.eachCount().toMutableMap()
        return words(original).filterNot { word -> word.any(Char::isDigit) }.count { word ->
            val n = left[word] ?: 0
            if (n > 0) left[word] = n - 1
            n == 0
        }
    }

    /**
     * "Here is the cleaned text:" - a lead before a colon, early in the reply, that is mostly
     * words the dictation did not have. "Einkaufsliste:" is the dictation's own.
     */
    private fun hasPreamble(original: String, cleaned: String): Boolean {
        // Not the colon of a time ("15:30").
        val colon = Regex("""(?<!\d)[:：]|[:：](?!\d)""").find(cleaned)?.range?.first?.takeIf { it <= 60 } ?: return false
        val lead = words(cleaned.substring(0, colon))
        val source = words(original).toSet()
        val foreign = lead.count { it !in source }
        return lead.size >= 3 && foreign * 2 > lead.size
    }

    // --- Self-corrections ---------------------------------------------------------------

    private fun phrase(phrase: String) = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(phrase) + "(?![\\p{L}\\p{N}])")

    /** Phrases with which a speaker takes something back, wherever they stand. */
    private val CORRECTION_PHRASES = listOf(
        // "stop": how Whisper often writes a spoken "stopp".
        "nee", "nö", "stopp", "stop", "ich meine", "ich mein", "besser gesagt", "korrektur",
        "quatsch", "no wait", "wait no", "i mean", "scratch that", "oder besser", "oder vielmehr",
        // The same in other languages, only as unmistakable phrases.
        "no espera", "mejor dicho", "non attends", "je veux dire", "no aspetta", "não espera", "quer dizer",
        "nee wacht", "ik bedoel", "nie czekaj", "нет стоп", "όχι στάσου", "नहीं रुको", "لا انتظر",
    ).map(::phrase)

    /** And in scripts written without spaces, where a phrase has letters on both sides. */
    private val UNSPACED_CORRECTIONS = listOf("不对", "我是说", "じゃなくて", "間違えた").map { Regex(Regex.escape(it)) }

    /**
     * Everyday words that take something back only when they stand alone, set off before more
     * words ("um drei, obwohl, um vier"): "Ich komme nicht, obwohl ich will" joins a clause.
     */
    private val INTERJECTIONS = listOf("obwohl", "warte", "actually", "sorry").map(::phrase)

    /** After an interjection: a punctuation mark, then more words. */
    private val SET_OFF = Regex("^\\s*[,.;:!?–—-]+\\s*[\\p{L}\\p{N}]")

    /** "nein" or "no," - an answer at the very start, a correction anywhere later. */
    private val NO = Regex("(?<![\\p{L}\\p{N}])(nein(?![\\p{L}\\p{N}])|no\\s*,)")
    private val FIRST_WORD = Regex("[\\p{L}\\p{N}]+")

    /** Whether the speaker corrected themselves somewhere in [text]. */
    fun hasCorrectionMarker(text: String): Boolean = markers(text.lowercase()).any()

    /** Where in [lower] the speaker takes something back. */
    private fun markers(lower: String): Sequence<IntRange> {
        val phrases = (CORRECTION_PHRASES + UNSPACED_CORRECTIONS).asSequence().flatMap { it.findAll(lower) }.map { it.range }
        val interjections = INTERJECTIONS.asSequence().flatMap { it.findAll(lower) }.map { it.range }
            .filter { SET_OFF.containsMatchIn(lower.substring(it.last + 1)) }
        // "Nein, ich habe keine Zeit." answers; "um drei, nein, um vier" and "... Nein, mit ..." correct.
        val firstWord = FIRST_WORD.find(lower)?.range?.last
        val no = if (firstWord == null) emptySequence() else NO.findAll(lower).map { it.range }.filter { it.first > firstWord }
        return phrases + interjections + no
    }

    /** A self-correction: what is still said after the last marker. */
    private class Correction(val kept: String)

    /**
     * The speaker's self-correction in [text], when there is one with something said before it
     * to take back - "Sorry, ich habe keine Zeit" takes nothing back. Null otherwise.
     */
    private fun correction(text: String): Correction? {
        val lower = text.lowercase()
        val last = markers(lower).maxByOrNull { it.last } ?: return null
        if (FIRST_WORD.find(lower.substring(0, last.first)) == null) return null
        return Correction(kept = lower.substring(last.last + 1))
    }

    // --- Negations ----------------------------------------------------------------------

    private val GERMAN_NEGATIONS = setOf(
        "nicht", "nichts", "kein", "keine", "keinen", "keinem", "keiner", "keines", "keins", "nie", "niemals",
        "niemand", "niemandem", "niemanden", "nirgends", "nirgendwo", "ohne", "weder", "keinesfalls", "keineswegs",
    )
    private val ENGLISH_NEGATIONS = setOf("not", "never", "nothing", "nobody", "none", "without", "cannot", "nowhere", "neither", "nor")

    /**
     * How many negations [text] holds. "No" counts inside a sentence ("I have no time"), not as
     * its first word, where it answers - and "nein" never: it answers or takes back. A stutter
     * ("nicht, nicht so") is one negation: the cleanup rightly drops the repeat, and in a long
     * dictation one such repeat would otherwise reject the whole tidy-up. In a German
     * dictation ([language] "de") "not" is the noun ("in der Not", or a word misheard as it),
     * not the English negation.
     */
    fun negations(text: String, language: String? = null): Int {
        val german = LanguageGuess.baseOf(language) == "de"
        var count = 0
        var sentenceStart = true
        var previous: String? = null
        for (token in tokens(text)) {
            val word = token.word
            val englishOnly = german && word == "not"
            val negation = word in GERMAN_NEGATIONS || (word in ENGLISH_NEGATIONS && !englishOnly) || word.endsWith("n't") ||
                (word == "no" && !sentenceStart)
            if (negation && word != previous) count++
            previous = word
            sentenceStart = token.endsSentence
        }
        return count
    }

    // --- Numbers ------------------------------------------------------------------------

    /**
     * A number as spoken or written: its parts ("zwei null vier" -> 2, 0, 4; "15:30" -> 15, 30)
     * and, where they form one, the whole value ("twenty three" -> 23, "1.000" -> 1000).
     */
    internal class NumberGroup(
        val parts: List<Long>,
        val whole: Long?,
        /** As written, for a number in digits ("0176"). */
        digits: String? = null,
        /** An article that may mean one ("ein", "a"): matches a 1, is never required. */
        val soft: Boolean = false,
    ) {
        private val values get() = parts + listOfNotNull(whole)

        /** "0176", whether written so or spoken digit by digit ("null eins sieben sechs"). */
        private val asDigits: String? = digits
            ?: parts.takeIf { it.size > 1 && it.all { part -> part in 0..9 } }?.joinToString("")

        /** Said again on the other side: as a whole, part by part, or digit by digit. */
        fun foundIn(others: List<NumberGroup>): Boolean {
            val known = others.flatMap { it.values }.toSet()
            return (whole != null && whole in known) || parts.all { it in known } ||
                (asDigits != null && others.any { it.asDigits == asDigits }) ||
                // "ein Uhr" -> "1 Uhr": an article may become a one, it is never missed.
                (parts == listOf(1L) && others.any { it.soft })
        }
    }

    private val GERMAN_NUMBERS = mapOf(
        "null" to 0L, "eins" to 1L, "zwei" to 2L, "zwo" to 2L, "drei" to 3L, "vier" to 4L, "fünf" to 5L,
        "sechs" to 6L, "sieben" to 7L, "acht" to 8L, "neun" to 9L, "zehn" to 10L, "elf" to 11L, "zwölf" to 12L,
        "dreizehn" to 13L, "vierzehn" to 14L, "fünfzehn" to 15L, "sechzehn" to 16L, "siebzehn" to 17L,
        "achtzehn" to 18L, "neunzehn" to 19L, "zwanzig" to 20L, "dreißig" to 30L, "vierzig" to 40L,
        "fünfzig" to 50L, "sechzig" to 60L, "siebzig" to 70L, "achtzig" to 80L, "neunzig" to 90L,
        "hundert" to 100L, "tausend" to 1000L,
    )

    /** Units that only appear inside a compound ("einundzwanzig", "sechzehn" aside). */
    private val GERMAN_COMPOUND_UNITS = GERMAN_NUMBERS.filterValues { it in 1..9 } + mapOf("ein" to 1L, "sech" to 6L, "sieb" to 7L)

    private val ENGLISH_NUMBERS = mapOf(
        "zero" to 0L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L, "five" to 5L, "six" to 6L,
        "seven" to 7L, "eight" to 8L, "nine" to 9L, "ten" to 10L, "eleven" to 11L, "twelve" to 12L,
        "thirteen" to 13L, "fourteen" to 14L, "fifteen" to 15L, "sixteen" to 16L, "seventeen" to 17L,
        "eighteen" to 18L, "nineteen" to 19L, "twenty" to 20L, "thirty" to 30L, "forty" to 40L, "fifty" to 50L,
        "sixty" to 60L, "seventy" to 70L, "eighty" to 80L, "ninety" to 90L, "hundred" to 100L, "thousand" to 1000L,
        "million" to 1_000_000L,
    )

    private val ORDINALS = mapOf(
        "erst" to 1L, "dritt" to 3L, "siebt" to 7L, "acht" to 8L, "first" to 1L, "second" to 2L, "third" to 3L, "fifth" to 5L,
        "eighth" to 8L, "ninth" to 9L, "twelfth" to 12L,
    )

    private val ARTICLES_AS_ONE = setOf("ein", "eine", "einen", "einem", "einer", "eines", "a", "an")

    /** "1.000" and "1,000" as one number; "2,5" and "15:30" as two. */
    private val THOUSANDS = Regex("""^\d{1,3}([.,]\d{3})+$""")

    internal fun numberGroups(text: String): List<NumberGroup> {
        val groups = mutableListOf<NumberGroup>()
        val run = mutableListOf<Long>()
        fun closeRun() {
            if (run.isNotEmpty()) groups += NumberGroup(run.toList(), combine(run))
            run.clear()
        }
        for (token in tokens(text)) {
            val raw = token.raw
            if (raw.any(Char::isDigit)) {
                closeRun()
                val digits = Regex("""\d[\d.,:]*\d|\d""").findAll(raw).map { it.value }.toList()
                digits.forEach { number ->
                    if (THOUSANDS.matches(number)) {
                        val parts = number.split('.', ',').map { it.toLong() }
                        groups += NumberGroup(parts, number.filter(Char::isDigit).toLongOrNull())
                    } else {
                        val parts = number.split('.', ',', ':').filter { it.isNotEmpty() }.mapNotNull { it.toLongOrNull() }
                        groups += NumberGroup(parts, parts.singleOrNull(), digits = number.takeIf { it.all(Char::isDigit) })
                    }
                }
                continue
            }
            val word = token.word
            if (word == "and" && run.isNotEmpty()) continue // "two hundred and four"
            // "zweieinhalb" -> 2,5 - and "anderthalb" -> 1,5.
            val half = if (word == "anderthalb") 1L else word.takeIf { it.length > 7 && it.endsWith("einhalb") }?.let { numberWord(it.dropLast(7)) }
            if (half != null) {
                closeRun()
                groups += NumberGroup(listOf(half, 5L), null)
                continue
            }
            val value = numberWord(word)
            if (value != null) {
                run += value
                if (token.endsSentence || token.endsClause) closeRun()
            } else {
                closeRun()
                if (word in ARTICLES_AS_ONE) groups += NumberGroup(emptyList(), null, soft = true)
            }
        }
        closeRun()
        return groups
    }

    /** What a run of number words adds up to ("twenty three", "two hundred four"); null when it does not form one. */
    private fun combine(run: List<Long>): Long? {
        if (run.size == 1) return run.single()
        var total = 0L
        var current = 0L
        var lastSmall: Long? = null
        for (value in run) {
            when {
                value == 100L -> { current = (if (current == 0L) 1 else current) * 100; lastSmall = null }
                value >= 1000L -> { total += (if (current == 0L) 1 else current) * value; current = 0; lastSmall = null }
                value in 20..90 && value % 10 == 0L -> {
                    if (lastSmall != null && current % 100 != 0L) return null
                    current += value; lastSmall = value
                }
                else -> {
                    // A unit after a ten ("twenty three") or after a hundred; two units in a row are digits.
                    if (lastSmall != null && (lastSmall < 20 || value >= 10)) return null
                    current += value; lastSmall = value
                }
            }
        }
        return total + current
    }

    /** The value of one number word, German compounds and ordinals included; null for any other word. */
    internal fun numberWord(word: String): Long? {
        GERMAN_NUMBERS[word]?.let { return it }
        ENGLISH_NUMBERS[word]?.let { return it }
        germanCompound(word)?.let { return it }
        // Ordinals: "vierzehnten", "zwanzigsten", "dritte", "fourteenth".
        for (suffix in listOf("sten", "ster", "stes", "stem", "ste", "ten", "ter", "tes", "tem", "te", "en", "er", "es", "em", "e")) {
            if (word.length <= suffix.length + 2 || !word.endsWith(suffix)) continue
            val stem = word.dropLast(suffix.length)
            ORDINALS[stem]?.let { return it }
            if (suffix.startsWith("t") || suffix.startsWith("s")) {
                GERMAN_NUMBERS[stem]?.takeIf { it != 0L }?.let { return it }
                germanCompound(stem)?.let { return it }
            }
        }
        ORDINALS[word]?.let { return it }
        if (word.endsWith("th")) {
            val stem = word.dropLast(2)
            ENGLISH_NUMBERS[stem]?.let { return it }
            if (stem.endsWith("ie")) ENGLISH_NUMBERS[stem.dropLast(2) + "y"]?.let { return it } // twentieth
        }
        return null
    }

    /**
     * "dreiundzwanzig", "zweihundertvier", "zweitausendsechsundzwanzig",
     * "dreihundertfünfzigtausend"; null for any other word.
     */
    private fun germanCompound(word: String): Long? {
        if (word.length < 7) return null
        for ((scale, name) in listOf(1000L to "tausend", 100L to "hundert")) {
            val at = word.indexOf(name)
            if (at < 0) continue
            val left = word.substring(0, at)
            val right = word.substring(at + name.length)
            // Before "tausend" also hundreds: "zweihundert-", "hundert-", "zweihundertdrei-".
            val factor = if (left.isEmpty()) 1L else GERMAN_COMPOUND_UNITS[left] ?: germanBelowHundred(left)
                ?: germanCompound(left)?.takeIf { scale == 1000L && it < 1000L } ?: return null
            val rest = if (right.isEmpty()) 0L else GERMAN_NUMBERS[right]?.takeIf { it < scale } ?: germanBelowHundred(right)
                ?: germanCompound(right)?.takeIf { it < scale } ?: return null
            return factor * scale + rest
        }
        return germanBelowHundred(word)
    }

    /** "einundzwanzig" -> 21. */
    private fun germanBelowHundred(word: String): Long? {
        GERMAN_NUMBERS[word]?.takeIf { it < 100 }?.let { return it }
        val at = word.indexOf("und").takeIf { it > 0 } ?: return null
        val unit = GERMAN_COMPOUND_UNITS[word.substring(0, at)] ?: return null
        val ten = GERMAN_NUMBERS[word.substring(at + 3)]?.takeIf { it in 20..90 } ?: return null
        return ten + unit
    }

    // --- Words --------------------------------------------------------------------------

    private class Token(val raw: String, val word: String, val endsSentence: Boolean, val endsClause: Boolean)

    private fun tokens(text: String): List<Token> =
        text.split(Regex("\\s+")).filter { it.isNotEmpty() }.flatMap { chunk ->
            // "twenty-three" and "Amber-Schnittstelle" are words of their own.
            val pieces = if (chunk.any(Char::isDigit)) listOf(chunk) else chunk.split('-', '–').filter { it.isNotEmpty() }
            pieces.mapIndexed { i, piece ->
                val last = i == pieces.lastIndex
                val word = piece.lowercase().replace('’', '\'').trim { !it.isLetterOrDigit() && it != '\'' }.trim('\'')
                Token(
                    raw = piece,
                    word = word,
                    endsSentence = last && piece.trimEnd('"', '“', '”', '»', '«', ')').lastOrNull()?.let { it in ".!?。！？؟" } == true,
                    endsClause = last && piece.lastOrNull()?.let { it in ",;:，、；：،" } == true,
                )
            }
        }

    /**
     * The words of [text], lower case. A vowel sign is part of its word (Hindi, Thai); a script
     * written without spaces gives a word per character - else a whole Chinese clause is one
     * word, and dropping a filler from it leaves the reply none of the dictation's words.
     */
    private fun words(text: String): List<String> {
        val out = mutableListOf<String>()
        WORD.findAll(text.lowercase()).forEach { match ->
            val run = StringBuilder()
            match.value.codePoints().forEach { cp ->
                if (unspaced(cp)) {
                    if (run.isNotEmpty()) out += run.toString().also { run.clear() }
                    out += String(Character.toChars(cp))
                } else {
                    run.appendCodePoint(cp)
                }
            }
            if (run.isNotEmpty()) out += run.toString()
        }
        return out
    }

    private val WORD = Regex("""[\p{L}\p{M}\p{N}]+""")

    private fun unspaced(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.THAI, Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER,
        Character.UnicodeScript.MYANMAR,
        -> true
        else -> false
    }
}
