package app.amethyst.core.text

/**
 * Which language a dictation is in, as far as it can be told without a model: from the
 * text's small words, from what the engine detected, or from the one language it was set to.
 *
 * The cleanup names the language in its prompt (small models drift to the prompt's English on
 * short input otherwise) and checks that the reply kept it. Both only act on a sure answer:
 * "de,en" or a short phrase gives none, and the prompt then keeps its general sentence.
 */
object LanguageGuess {

    /**
     * Small words frequent in German and rare in English. Words both languages write alike
     * ("also", "was", "will", "die", "man", "in", "so", "an") count for neither.
     */
    private val GERMAN = setOf(
        "und", "ich", "nicht", "ist", "das", "der", "den", "dem", "des", "es", "wir", "mit", "auf", "für",
        "dass", "ein", "eine", "einen", "einem", "einer", "zu", "auch", "sich", "von", "bin", "du", "haben",
        "hast", "hat", "wie", "noch", "aber", "wenn", "schon", "ja", "nein", "nee", "bitte", "danke", "mal",
        "heute", "morgen", "kann", "kannst", "muss", "habe", "hab", "sind", "war", "er", "sie", "oder", "wird",
        "werden", "nur", "nach", "bei", "aus", "uns", "mir", "mich", "dir", "dich", "kein", "keine", "keinen",
        "ihr", "hier", "jetzt", "gleich", "doch", "wo", "warum", "gut", "sehr", "mehr", "dann", "weil", "ob",
        "zum", "zur", "vom", "beim", "gibt", "geht", "komme", "kommt", "bis", "ohne", "nichts", "wieder",
        "machen", "mach", "soll", "sollte", "würde", "möchte", "können", "genau", "passt", "wann",
    )

    /** And the English ones. */
    private val ENGLISH = setOf(
        "the", "and", "i", "you", "is", "to", "of", "it", "that", "we", "this", "what", "are", "have", "with",
        "for", "not", "be", "do", "my", "your", "he", "she", "they", "should", "can", "would", "could", "there",
        "their", "just", "about", "from", "if", "but", "or", "at", "on", "me", "him", "her", "them", "our",
        "been", "has", "had", "don't", "i'm", "it's", "can't", "won't", "please", "thanks", "thank", "yes",
        "no", "how", "why", "when", "where", "which", "who", "a", "going", "want", "need", "know", "think",
        "get", "see", "later", "today", "tomorrow", "let's", "let", "up", "out", "time", "into", "some",
    )

    /** At least this many marker words, and this many times as many as the other language's. */
    private const val MIN_HITS = 3
    private const val RATIO = 3

    /** Names for the prompt, by ISO 639-1 code. */
    private val NAMES = mapOf(
        "de" to "German", "en" to "English", "fr" to "French", "es" to "Spanish", "it" to "Italian",
        "nl" to "Dutch", "pt" to "Portuguese", "pl" to "Polish", "tr" to "Turkish", "sv" to "Swedish",
        "da" to "Danish", "no" to "Norwegian", "cs" to "Czech", "ru" to "Russian", "uk" to "Ukrainian",
    )

    /**
     * The code a language tag stands for when it names exactly one language: "de", "de-DE",
     * "de_AT", "german" (servers answering verbose_json name it) -> "de". Null for a list
     * ("de,en"), automatic detection or anything unknown.
     */
    fun fromTag(tag: String?): String? {
        val tags = tag?.lowercase()?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val single = tags.singleOrNull() ?: return null
        NAMES.entries.firstOrNull { it.value.lowercase() == single }?.let { return it.key }
        val code = single.substringBefore('-').substringBefore('_')
        return code.takeIf { it in NAMES }
    }

    /** "de" or "en" when [text]'s words say so clearly; null for short, mixed or other text. */
    fun fromWords(text: String): String? {
        val words = words(text)
        val german = words.count { it in GERMAN || it.any { c -> c in "äöüß" } }
        val english = words.count { it in ENGLISH }
        return when {
            german >= MIN_HITS && german >= RATIO * english -> "de"
            english >= MIN_HITS && english >= RATIO * german -> "en"
            else -> null
        }
    }

    /**
     * The dictation's language for the cleanup, in this order: the words when they are clear
     * ([fromWords], the rule [CleanupGuard] checks the reply by), what the engine detected,
     * the one language it was set to. Words first, because the guard goes by them: a prompt
     * asking for German on an English dictation gets a translation, which the guard rejects -
     * an engine set to "de-DE" hears English too. Null when none of them is sure.
     */
    fun forDictation(text: String, detected: String?, engineTag: String?): String? =
        fromWords(text) ?: fromTag(detected) ?: fromTag(engineTag)

    /** "German" for "de"; null for a code the prompt has no name for. */
    fun nameOf(code: String?): String? = code?.let { NAMES[fromTag(it) ?: it] }

    private fun words(text: String): List<String> =
        text.lowercase().split(Regex("""[^\p{L}\p{N}'’]+""")).map { it.replace('’', '\'').trim('\'') }.filter { it.isNotEmpty() }
}
