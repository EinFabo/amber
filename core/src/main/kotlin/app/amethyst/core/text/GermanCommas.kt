package app.amethyst.core.text

/**
 * Adds the commas German requires and speech recognition often leaves out: before a
 * subordinate clause ("..., dass", "..., weil", "..., wenn"), before an infinitive group
 * ("..., um ... zu"), before "sondern" and a clause-joining "aber".
 *
 * Rules, not a model: no download, no wait, and nothing it can invent. Each rule fires only
 * where the comma is nearly certain - checked against 4,200 sentences of written German
 * (FLEURS, commas removed and restored): 97 % of the commas it sets were in the original,
 * and most of the rest were commas the original itself had missed ("starb, nachdem").
 * Commas that need grammar ("Wenn du kommst, bring Kuchen mit") are left alone.
 */
object GermanCommas {

    /** Conjunctions that always open a subordinate clause. */
    private val SUBORDINATORS = setOf(
        "dass", "weil", "obwohl", "obgleich", "obschon", "falls", "sofern", "sodass", "nachdem",
        "sobald", "solange", "indem", "wobei", "wodurch", "weshalb", "weswegen", "wohingegen",
        "wenngleich", "ob", "wenn", "bevor",
    )

    /** Words that belong to the conjunction after them, so the comma goes before them ("..., auch wenn"). */
    private val LEADS = setOf(
        "so", "ohne", "anstatt", "statt", "außer", "als", "bis", "nur", "kaum", "auch", "selbst",
        "erst", "gerade", "kurz", "lange", "wie", "je", "besonders", "insbesondere", "aber", "doch", "sondern",
    )

    /** After these a comma is wrong: "und dass", "oder weil" - and "aber wenn" takes it before "aber". */
    private val JOINERS = setOf(
        "und", "oder", "sowie", "bzw", "beziehungsweise", "weder", "noch", "entweder", "aber", "doch", "denn", "sondern",
    )

    /** "rund um die Stadt", "gegen um acht": "um" as a preposition, whatever follows. */
    private val BEFORE_PREPOSITIONAL_UM = setOf("rund", "etwa", "ungefähr", "gegen", "genau", "bis")

    private val PRONOUNS = setOf(
        "ich", "du", "er", "sie", "es", "wir", "ihr", "man", "mir", "mich", "dir", "dich", "uns", "euch",
        "ihm", "ihn", "ihnen", "sich",
    )

    private val ARTICLES = setOf(
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "eines",
        "kein", "keine", "mein", "meine", "dein", "deine", "sein", "seine", "unser", "unsere", "euer",
        "eure", "dieser", "diese", "dieses", "jeder", "jede", "alle",
    )

    /** "aber" joins two clauses when one of these follows it; otherwise it is a particle ("ist aber schön"). */
    private val CLAUSE_AFTER_ABER = PRONOUNS + ARTICLES + setOf("nicht", "auch", "dann", "jetzt", "heute", "noch")

    /** Before these, "aber" is a particle or inside a relative clause ("sich aber", "was aber"). */
    private val NO_CLAUSE_BEFORE_ABER = PRONOUNS + setOf("was", "wer", "der", "die", "das", "diese", "dieser", "dieses")

    /** Already punctuated on this side: no comma added. */
    private const val CLOSING = ",.;:!?–-)\"“»"
    private const val OPENING = "(\"„«»“'‚"
    private const val SENTENCE_END = ".!?"

    private val INFINITIVE = Regex("\\p{Ll}+zu\\p{Ll}+en")
    private val TIME = Regex("\\d.*|uhr")

    /** Small words frequent in German and rare in English, to tell the language of a transcript. */
    private val GERMAN_MARKERS = setOf(
        "und", "ich", "nicht", "ist", "das", "die", "der", "es", "wir", "mit", "auf", "für", "dass", "ein",
        "eine", "zu", "den", "auch", "sich", "von", "bin", "du", "haben", "wie", "noch", "aber", "wenn", "schon",
        "ja", "nein", "bitte", "danke", "mal", "heute", "morgen", "kann", "muss", "habe", "hab", "sind", "war",
    )

    /** And the English ones, so a sentence is weighed rather than just counted. */
    private val ENGLISH_MARKERS = setOf(
        "the", "and", "i", "you", "is", "to", "of", "it", "that", "we", "this", "what", "are", "was", "have",
        "with", "for", "not", "be", "do", "my", "your", "he", "she", "they", "should", "will", "can",
    )

    /**
     * Whether [text] is German: by the language the engine detected or was set to ([language],
     * e.g. "de", "de-DE", "german", "de,en"), and when that does not settle it, by its words.
     */
    fun isGerman(language: String?, text: String): Boolean {
        val tags = language?.lowercase()?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (tags.size == 1) return tags.single().let { it.startsWith("de") || it == "german" }
        if (tags.isNotEmpty() && tags.none { it.startsWith("de") || it == "german" }) return false
        val words = text.lowercase().split(' ').map { core(it) }.filter { it.isNotEmpty() }
        if (words.size < 2) return false
        val german = words.count { it in GERMAN_MARKERS || it.any { c -> c in "äöüß" } }
        val english = words.count { it in ENGLISH_MARKERS }
        return german >= 1 && german > english
    }

    /** [text] with single spaces between words, as TranscriptNormalizer leaves it. */
    fun apply(text: String): String {
        val tokens = text.split(' ')
        if (tokens.size < 3 || tokens.any { it.isEmpty() }) return text
        val words = tokens.map(::core)
        val lower = words.map { it.lowercase() }
        val before = BooleanArray(tokens.size)

        fun sentenceStart(k: Int) = k == 0 || tokens[k - 1].last() in SENTENCE_END
        fun sentenceEnd(k: Int) = k == tokens.lastIndex || tokens[k].last() in SENTENCE_END

        for (i in 1 until tokens.size) {
            val word = lower[i]
            if (word.isEmpty() || words[i] != word) continue // capitalised mid-sentence: a name ("Victoria Falls")
            if (tokens[i - 1].last() in CLOSING || tokens[i].first() in OPENING) continue
            if (sentenceEnd(i)) continue // nothing follows to make a clause
            val next = lower[i + 1]

            val leadable: Boolean = when {
                word in SUBORDINATORS -> !(word == "nachdem" && lower[i - 1] == "je")
                word == "während" -> next in PRONOUNS
                word == "sondern" -> true
                word == "aber" -> lower[i - 1] !in NO_CLAUSE_BEFORE_ABER && next in CLAUSE_AFTER_ABER
                // "ohne Zucker", "ohne einen Passagier": a capitalised noun or an article
                // after it makes "ohne" a preposition.
                word == "um" && lower[i - 1] !in BEFORE_PREPOSITIONAL_UM ||
                    (word == "ohne" || word == "anstatt") && next !in ARTICLES && words[i + 1] == next ->
                    !TIME.matches(next) && opensInfinitive(i, tokens, words)
                else -> continue
            }
            if (!leadable) continue

            var at = i
            val leads = word in SUBORDINATORS || word == "um" || word == "ohne" || word == "anstatt"
            if (leads && lower[i - 1] in LEADS) {
                if (sentenceStart(i - 1)) continue // "Auch wenn ..." opens the sentence
                if (tokens[i - 2].last() in CLOSING || tokens[i - 1].first() in OPENING) continue
                at = i - 1
            }
            if (lower[at - 1] in JOINERS) continue
            before[at] = true
        }

        return tokens.indices.joinToString(" ") { k ->
            if (k + 1 < tokens.size && before[k + 1]) tokens[k] + "," else tokens[k]
        }
    }

    /** "um ... zu gehen" or "um ... einzukaufen" within the same sentence, a few words on. */
    private fun opensInfinitive(i: Int, tokens: List<String>, words: List<String>): Boolean {
        for (k in i + 1..minOf(i + 8, tokens.lastIndex)) {
            val last = k == tokens.lastIndex || tokens[k].last() in SENTENCE_END
            if (words[k] == "zu" && !last) return true
            // Lower case only: nouns are capitalised, so "Bezugsquellen" is no infinitive.
            if (INFINITIVE.matches(words[k])) return true
            if (last) return false
        }
        return false
    }

    /** The bare word: no quotes, brackets or punctuation around it. */
    private fun core(token: String): String = token.trim('"', '„', '“', '«', '»', '‚', '‘', '\'', '(', ')', ',', '.', ';', ':', '!', '?')
}
