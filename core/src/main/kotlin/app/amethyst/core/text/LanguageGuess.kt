package app.amethyst.core.text

import java.util.Locale

/**
 * Which language a dictation is in, as far as it can be told without a model: from what the
 * engine detected, from the one language it was set to, and - between German and English -
 * from the text's small words.
 *
 * The cleanup names the language in its prompt (small models drift to the prompt's English on
 * short input otherwise) and checks that the reply kept it. Both only act on a sure answer:
 * "de,en" or a short phrase gives none, and the prompt then keeps its general sentence.
 * Any language the platform has a name for can be named ([nameOf]).
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

    /**
     * Small words of Spanish, French, Italian, Portuguese and Dutch that neither list above has.
     * German and English share some of their markers with these languages ("es", "des", "a",
     * "no", "do"): "es que no es posible" counted as German, "eu vou a casa do meu pai" as
     * English, and the prompt asked for a translation. Words like these speak against both.
     */
    private val OTHER = setOf(
        "que", "el", "la", "los", "las", "y", "por", "para", "con", "muy", "pero", "más", "como", "una", "del",
        "lo", "se", "su", "esto", "esta", "está", "estoy", "tengo", "hoy", "mañana", "sí", "qué", "porque", "yo",
        "le", "les", "et", "est", "je", "il", "elle", "nous", "vous", "pas", "une", "avec", "dans", "pour", "sur",
        "mais", "très", "c'est", "j'ai", "suis", "ce", "qui", "au", "aux", "un", "en", "o", "e",
        "che", "di", "è", "per", "non", "sono", "ma", "anche", "questo", "molto", "gli", "della", "domani", "oggi",
        "eu", "você", "não", "é", "uma", "com", "meu", "minha", "em", "os", "vou", "amanhã", "hoje", "muito",
        "het", "een", "niet", "ik", "maar", "ook", "jij", "zijn",
    )

    /** At least this many marker words, and this many times as many as the other language's and the others'. */
    private const val MIN_HITS = 3
    private const val RATIO = 3

    private val GERMAN_OR_ENGLISH = setOf("de", "en")

    /** Languages by their English name, as servers answering verbose_json give them ("german"). */
    private val BY_NAME: Map<String, String> by lazy {
        Locale.getISOLanguages().associateBy { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).lowercase() }
    }

    /**
     * Languages whose writing differs by country, so a region in the tag is kept: Brazil's
     * Portuguese spelling, Taiwan's and Hong Kong's traditional characters.
     */
    private val REGIONAL = setOf("pt", "zh")

    /**
     * The language a tag stands for when it names exactly one: "de", "de-DE", "de_AT",
     * "german" (servers answering verbose_json name it) -> "de"; "zh-Hant" and "pt-BR" keep
     * their script and region, which change how the language is written. Null for a list
     * ("de,en"), automatic detection or anything unknown ("xx-YY").
     */
    fun fromTag(tag: String?): String? {
        val single = tags(tag).singleOrNull() ?: return null
        BY_NAME[single.lowercase()]?.let { return it }
        val locale = Locale.forLanguageTag(single.replace('_', '-'))
        val language = locale.language
        if (language.length !in 2..3) return null
        // A code the platform has no name for is no language ("xx", "auto").
        if (locale.getDisplayLanguage(Locale.ENGLISH).equals(language, ignoreCase = true)) return null
        return buildString {
            append(language)
            if (locale.script.isNotEmpty()) append('-').append(locale.script)
            if (language in REGIONAL && locale.country.isNotEmpty()) append('-').append(locale.country)
        }
    }

    /** "de" for "de", "de-AT" or "german"; the language alone, without script or region. */
    fun baseOf(tag: String?): String? = fromTag(tag)?.substringBefore('-')

    /**
     * "de" or "en" when [text]'s words say so clearly; null for short, mixed or other text -
     * words of other languages ([OTHER]) count against both.
     */
    fun fromWords(text: String): String? {
        val (german, english, other) = counts(text)
        return when {
            german >= MIN_HITS && german >= RATIO * (english + other) -> "de"
            english >= MIN_HITS && english >= RATIO * (german + other) -> "en"
            else -> null
        }
    }

    /**
     * The dictation's language for the cleanup and the phone's own rules.
     *
     * What the recognizer detected comes first, and a language other than German or English
     * that it detected stands: the German and English small words cannot tell Spanish from
     * German ("es", "des"). Then the one language the engine was set to - unless the words are
     * clearly German or English ([fromWords]), as a recognizer set to French may still have
     * written German. Between German and English the words decide first, the rule
     * [CleanupGuard] checks the reply by: a prompt asking for German on an English dictation
     * gets a translation - an engine set to "de-DE" hears English too. A German or English the
     * few words lean away from is not named ([leaning]). Null when none of them is sure, or
     * when the engine was set to several languages, none of them German or English.
     */
    fun forDictation(text: String, detected: String?, engineTag: String?): String? {
        fromTag(detected)?.let { found -> if (baseOf(found) !in GERMAN_OR_ENGLISH) return found }
        val tag = fromTag(detected) ?: fromTag(engineTag)
        val words = fromWords(text)
        if (tag != null && baseOf(tag) !in GERMAN_OR_ENGLISH) return words ?: tag
        if (tag == null && !mayBeGermanOrEnglish(engineTag)) return null
        return words ?: tag?.takeUnless { contradicts(baseOf(it)!!, leaning(text)) }
    }

    /**
     * The language for [TranscriptNormalizer]: [forDictation], else the engine's languages when
     * they rule German and English out ("fr,es"), so neither one's fillers, commas or spoken
     * marks touch the text. Null as before when German or English may be it: the normalizer
     * then goes by the words itself.
     */
    fun forNormalizer(text: String, detected: String?, engineTag: String?): String? =
        forDictation(text, detected, engineTag)
            ?: listOf(detected, engineTag).firstOrNull { tags(it).isNotEmpty() }?.takeUnless(::mayBeGermanOrEnglish)

    /**
     * The language whose small words are lower-case where the dictation lands mid-sentence
     * ([CommonWords], [TextSplicer]): [forNormalizer], else where the few words lean
     * ([leaning]) - a dictation of three words is rarely sure, and "The station." leans English.
     * Null when nothing does; then only words lower-case in both count.
     */
    fun forSeam(text: String, detected: String?, engineTag: String?): String? =
        forNormalizer(text, detected, engineTag) ?: leaning(text)

    /**
     * "de" or "en" by which marker words [text] has more of, however few: "Sounds good to me"
     * leans English. Too little for the prompt to name a language by, enough to keep it from
     * naming the other one - an engine set to German that heard a short English sentence must
     * not have the cleanup asked for German, or it translates. Null for none, a tie, or as many
     * words of other languages ([OTHER]).
     */
    fun leaning(text: String): String? {
        val (german, english, other) = counts(text)
        return when {
            german > english + other -> "de"
            english > german + other -> "en"
            else -> null
        }
    }

    private fun counts(text: String): Triple<Int, Int, Int> {
        val words = words(text)
        val german = words.count { it in GERMAN || it.any { c -> c in "äöüß" } }
        val english = words.count { it in ENGLISH }
        val other = words.count { it in OTHER }
        return Triple(german, english, other)
    }

    /** Whether [code] (German or English) is the other one than the words lean to. */
    private fun contradicts(code: String, lean: String?): Boolean =
        lean != null && code in GERMAN_OR_ENGLISH && code != lean

    /** No tags (nobody knows), or a list with German or English in it. */
    private fun mayBeGermanOrEnglish(tag: String?): Boolean {
        val all = tags(tag)
        return all.isEmpty() || all.any { baseOf(it)?.let { code -> code in GERMAN_OR_ENGLISH } ?: false }
    }

    private fun tags(tag: String?): List<String> =
        tag?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() && !it.equals("auto", ignoreCase = true) }.orEmpty()

    /**
     * The language's English name for the prompt: "German" for "de" or "de-DE", "Chinese
     * (Traditional)" for "zh-Hant", "Portuguese (Brazil)" for "pt-BR"; null for a list, nothing
     * or a code nobody knows.
     */
    fun nameOf(code: String?): String? = fromTag(code)?.let { Locale.forLanguageTag(it).getDisplayName(Locale.ENGLISH) }

    private fun words(text: String): List<String> =
        text.lowercase().split(Regex("""[^\p{L}\p{N}'’]+""")).map { it.replace('’', '\'').trim('\'') }.filter { it.isNotEmpty() }
}
