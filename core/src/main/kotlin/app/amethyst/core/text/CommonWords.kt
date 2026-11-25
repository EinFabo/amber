package app.amethyst.core.text

/**
 * Small words that are written lower-case in the middle of a sentence: articles, pronouns,
 * conjunctions, prepositions, auxiliaries. A recognizer hears every chunk as a sentence of its
 * own and starts it with a capital; dictated into the middle of a sentence - "Meet me at" and
 * then "The station." - that capital is wrong, and only for words like these can that be told.
 *
 * Precision over recall: the lists are short and hold no nouns (German writes every noun with a
 * capital), no names ("Will", "May") and not the formal German you ("Sie", "Ihr", "Ihnen").
 * A word not in them keeps its capital, and so does any word written in more capitals than the
 * first - "IT" is no "it", "US" no "us". "I" is in neither list.
 */
object CommonWords {

    private val ENGLISH = setOf(
        "a", "an", "the", "this", "that", "these", "those", "some", "any", "every", "each", "all", "no",
        "you", "he", "she", "it", "we", "they", "me", "him", "her", "us", "them",
        "my", "your", "his", "its", "our", "their",
        "and", "but", "or", "nor", "so", "because", "if", "than", "then", "when", "while", "where",
        "what", "which", "who", "how", "why",
        "of", "in", "on", "at", "to", "for", "with", "from", "by", "about", "into", "onto", "over",
        "under", "after", "before", "between", "through", "without", "around", "near", "up", "out", "off",
        "is", "are", "was", "were", "be", "been", "am", "do", "does", "did", "have", "has", "had",
        "can", "could", "would", "should", "might", "must",
        "not", "also", "just", "there", "here", "very", "too",
        "it's", "that's", "there's", "don't", "let's",
    )

    private val GERMAN = setOf(
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "eines",
        "kein", "keine", "keinen", "keinem", "keiner",
        "ich", "du", "er", "es", "wir", "man", "mich", "mir", "dich", "dir", "uns", "euch", "ihm", "ihn", "sich",
        "mein", "meine", "meinen", "meinem", "meiner", "dein", "deine", "deinen", "deinem", "deiner",
        "sein", "seine", "seinen", "seinem", "seiner", "unser", "unsere", "unseren", "unserem",
        "dies", "diese", "dieser", "dieses", "diesen", "diesem", "alle",
        "und", "oder", "aber", "denn", "sondern", "dass", "weil", "wenn", "als", "ob", "wie", "damit", "obwohl",
        "in", "im", "ins", "an", "am", "ans", "auf", "aus", "bei", "beim", "mit", "nach", "von", "vom",
        "zu", "zum", "zur", "für", "über", "unter", "vor", "hinter", "neben", "zwischen", "durch", "gegen",
        "ohne", "um", "bis", "seit", "ab",
        "bin", "bist", "ist", "sind", "seid", "war", "warst", "waren", "habe", "hast", "hat", "haben", "habt",
        "hatte", "hatten", "wird", "wirst", "werden", "wurde", "wurden", "kann", "kannst", "können", "könnte",
        "muss", "musst", "müssen", "soll", "sollen", "sollte", "will", "willst", "wollen", "möchte", "darf",
        "nicht", "auch", "noch", "schon", "so", "dann", "da", "hier", "dort", "sehr", "nur", "ja", "nein", "doch",
    )

    /** For a language nobody is sure of: only what is lower-case in both. */
    private val BOTH = ENGLISH intersect GERMAN

    /** Words that make a name of the capitalized word after them: "The Hague", "A Tribe Called Quest". */
    private val ENGLISH_ARTICLES = setOf("the", "a", "an")

    /**
     * Whether [word] - one bare word, no spaces or punctuation around it - is written lower-case
     * mid-sentence in [language] ("en", "de-AT"; a list or null when unsure, then only words
     * both lists have count). A capital past the first letter means an acronym: never common.
     * Any other language has no list, so nothing is common in it.
     */
    fun isCommonLowerCase(word: String, language: String?): Boolean {
        if (word.isEmpty() || word.drop(1).any { it.isUpperCase() }) return false
        val list = listFor(language) ?: return false
        return word.lowercase().replace('’', '\'') in list
    }

    /**
     * [text] with its first letter lower-case where it begins with a common word
     * ([isCommonLowerCase]) - else unchanged. A text that starts with anything but a letter (a
     * quote may be a sentence of its own), a single letter with a full stop (an initial: "A.
     * Smith") and, in English, an article before a capitalized word ("The Hague") keep it.
     */
    fun lowerLeading(text: String, language: String?): String {
        val first = text.firstOrNull() ?: return text
        if (!first.isLetter() || !first.isUpperCase()) return text
        val word = text.takeWhile { it.isLetter() || it == '\'' || it == '’' }.trimEnd('\'', '’')
        if (!isCommonLowerCase(word, language)) return text
        val rest = text.substring(word.length)
        if (word.length == 1 && rest.startsWith('.')) return text
        if (isEnglish(language) && word.lowercase() in ENGLISH_ARTICLES && startsCapitalized(rest)) return text
        return first.lowercaseChar() + text.substring(1)
    }

    /** The list for [language]: one of the two, both's common part when unsure, null for another language. */
    private fun listFor(language: String?): Set<String>? {
        val bases = language?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() && !it.equals("auto", ignoreCase = true) }
            ?.map { LanguageGuess.baseOf(it) }.orEmpty()
        if (bases.isEmpty()) return BOTH
        val known = bases.filter { it == "en" || it == "de" }.toSet()
        return when {
            known.isEmpty() -> null
            known.size == 1 && bases.size == 1 -> if ("en" in known) ENGLISH else GERMAN
            // Several, German or English among them: what is common in both.
            else -> BOTH
        }
    }

    private fun isEnglish(language: String?): Boolean = listFor(language) === ENGLISH

    private fun startsCapitalized(rest: String): Boolean {
        val next = rest.trimStart(' ').firstOrNull() ?: return false
        return rest.startsWith(' ') && next.isUpperCase()
    }
}
