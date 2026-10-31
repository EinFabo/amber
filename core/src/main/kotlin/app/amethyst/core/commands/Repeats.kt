package app.amethyst.core.commands

/**
 * How often a command is said to run - "zehnmal runter", "scroll 10-mal nach unten", "swipe 5
 * times down", "drei Wörter löschen" - in digits or number words from one to twenty, German and
 * English, glued to "mal" or not, the way speech engines write them. Works on folded words
 * ([VoiceCommands.normalize]), so "fünfmal" arrives as "funfmal".
 */
object Repeats {

    /** More than this is taken as this: nobody means 50 scrolls, and each step takes time. */
    const val MAX_TIMES = 20

    /** "To the end" stops when nothing moves - or after this many steps. */
    const val MAX_TO_END = 30

    /** [times] null when no count was said; 0 is kept, so "0 mal" can be refused. */
    data class Counted(val times: Int?, val words: List<String>)

    private val NUMBERS: Map<String, Int> = buildMap {
        listOf(
            "eins", "zwei", "drei", "vier", "funf", "sechs", "sieben", "acht", "neun", "zehn",
            "elf", "zwolf", "dreizehn", "vierzehn", "funfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn", "zwanzig",
        ).forEachIndexed { i, word -> put(word, i + 1) }
        listOf(
            "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
        ).forEachIndexed { i, word -> put(word, i + 1) }
        // Spoken and spelled variants: "ein Mal", "zwo", "fuenf" typed without the umlaut.
        putAll(mapOf("ein" to 1, "eine" to 1, "einen" to 1, "zwo" to 2, "fuenf" to 5, "zwoelf" to 12, "fuenfzehn" to 15))
    }

    /** Words that stand for a count alone. */
    private val ADVERBS = mapOf("once" to 1, "twice" to 2, "thrice" to 3)

    /** What follows a number to make it a count: "10 mal", "5 times", "3 x". */
    private val MULTIPLIERS = setOf("mal", "times", "x")

    /** What a number counts directly, staying in the phrase: "drei Wörter", "2 Seiten", "three steps". */
    private val UNITS = setOf(
        "wort", "worter", "worte", "word", "words", "seite", "seiten", "page", "pages", "schritt", "schritte", "step", "steps",
    )

    private val DIGITS_GLUED = Regex("(\\d+)(mal|x)")

    /** The number [word] stands for: digits, or a number word from one to twenty. */
    fun number(word: String): Int? = word.toIntOrNull()?.takeIf { word.all(Char::isDigit) } ?: NUMBERS[word]

    /** The first count in [words], taken out of them - a unit it counts stays. */
    fun extract(words: List<String>): Counted {
        for ((i, word) in words.withIndex()) {
            val alone = ADVERBS[word] ?: glued(word)
            if (alone != null) return Counted(alone, words.without(i, 1))
            val n = number(word) ?: continue
            val next = words.getOrNull(i + 1)
            when (next) {
                in MULTIPLIERS -> return Counted(n, words.without(i, 2))
                in UNITS -> return Counted(n, words.without(i, 1))
            }
        }
        return Counted(null, words)
    }

    /** The pieces German and English number words are built of, folded. */
    private val SMALL_PARTS = setOf(
        "ein", "eins", "zwei", "zwo", "drei", "vier", "funf", "fuenf", "sechs", "sieben", "acht", "neun", "zehn", "elf",
        "zwolf", "zwoelf", "dreizehn", "vierzehn", "funfzehn", "fuenfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn",
        "und",
    )

    /** The pieces that make a number word larger than twenty: tens, hundreds, thousands. */
    private val LARGE_PARTS = setOf(
        "zwanzig", "dreissig", "dreisig", "vierzig", "funfzig", "fuenfzig", "sechzig", "siebzig", "achtzig", "neunzig",
        "hundert", "tausend",
        "thirty", "forty", "fourty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand",
    )
    private val PARTS = SMALL_PARTS + LARGE_PARTS

    /**
     * Whether [word] is surely a count - "dreißigmal", "dreihundert", "fifty", digits too long
     * for a number - that [extract] cannot read: the whole word, "mal" aside, made of number
     * pieces, one of them past twenty. "Jahrhundert" or "Tausendfüßler" only contain one. For
     * Diagnostics' "why not understood".
     */
    fun unreadable(word: String): Boolean {
        if (number(word) != null || glued(word) != null) return false
        val stem = DIGITS_GLUED.matchEntire(word)?.groupValues?.get(1) ?: word.removeSuffix("mal")
        if (stem.isEmpty()) return false
        if (stem.all(Char::isDigit)) return true
        return numberPieces(stem)?.any { it in LARGE_PARTS } == true
    }

    /** [stem] split completely into number pieces, or null where some of it is none. */
    private fun numberPieces(stem: String): List<String>? {
        // Pieces ending at each position, found left to right; null where no split reaches it.
        val reach = arrayOfNulls<List<String>>(stem.length + 1)
        reach[0] = emptyList()
        for (end in 1..stem.length) {
            for (start in 0 until end) {
                val before = reach[start] ?: continue
                val piece = stem.substring(start, end)
                if (piece in PARTS) {
                    reach[end] = before + piece
                    break
                }
            }
        }
        return reach[stem.length]
    }

    /** "zehnmal", "einmal", "12mal", "3x" - not "normal" or "manchmal". */
    private fun glued(word: String): Int? {
        DIGITS_GLUED.matchEntire(word)?.let { return it.groupValues[1].toIntOrNull() }
        if (!word.endsWith("mal") || word.length <= 3) return null
        return NUMBERS[word.dropLast(3)]
    }

    private fun List<String>.without(index: Int, count: Int): List<String> = take(index) + drop(index + count)
}
