package app.amethyst.core.text

/**
 * The user's own words - names and terms a speech recognition does not know ("Kubernetes",
 * "Anna Lüdtke") - and what each place that uses them gets:
 *
 * - Whisper on the phone and a server that takes a prompt: [hint], the words as a short list
 *   before the text (Whisper writes what follows its prompt in the prompt's spellings).
 *   Whisper reads only the last 224 tokens of a prompt, and Groq refuses a longer one, so the
 *   list stops at [HINT_CHARS] - the first words count.
 * - The cleanup: [cleanupRule], every word, as spellings to keep.
 *
 * Bounded: [MAX_WORDS] words of up to [MAX_CHARS] characters. They live in the settings, on the
 * phone, and are never logged.
 */
object OwnWords {

    const val MAX_WORDS = 100
    const val MAX_CHARS = 40

    /**
     * Characters of the list a recognition prompt gets: about 100 tokens even for names that
     * split badly (2-3 characters a token), which leaves room within Whisper's 224 for the text
     * before a chunk (`ChunkedTranscription.PROMPT_CHARS`).
     */
    const val HINT_CHARS = 300

    /** What separates words when several are typed or pasted at once. */
    private val SEPARATORS = Regex("[\\n\\r,;]+")

    /**
     * Controls, invisible characters and what could open or close a tag in the cleanup's prompt.
     * A word is a name, not markup.
     */
    private val UNWANTED = Regex("[\\u0000-\\u001F\\u007F-\\u009F\\u00AD\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF<>]")

    private val SPACES = Regex("\\s+")

    /** One word as it is kept: no markup, single spaces, at most [MAX_CHARS]; empty for nothing. */
    fun clean(word: String): String =
        word.replace(UNWANTED, " ").replace(SPACES, " ").trim().take(MAX_CHARS).trim()

    /**
     * [current] with what was typed in [input] added at the end: split at line breaks, commas
     * and semicolons, each [clean]ed, none twice (whatever the case), at most [MAX_WORDS].
     */
    fun add(current: List<String>, input: String): List<String> =
        bounded(current + input.split(SEPARATORS))

    /** [current] without [word]. */
    fun remove(current: List<String>, word: String): List<String> = current.filterNot { it == word }

    /** A stored list as it is used: each word [clean]ed, none twice, at most [MAX_WORDS] - whatever was stored. */
    fun bounded(words: List<String>): List<String> {
        val seen = HashSet<String>()
        return words.asSequence()
            .map(::clean)
            .filter { it.isNotEmpty() && seen.add(it.lowercase()) }
            .take(MAX_WORDS)
            .toList()
    }

    /**
     * The list for a recognition prompt: "Anna Lüdtke, Kubernetes, Amber." - as many words, in
     * order, as fit [HINT_CHARS]. Null without words: the prompt stays what it was.
     */
    fun hint(words: List<String>): String? {
        val out = StringBuilder()
        for (word in bounded(words)) {
            val next = if (out.isEmpty()) word else ", $word"
            if (out.length + next.length + 1 > HINT_CHARS) break
            out.append(next)
        }
        return if (out.isEmpty()) null else out.append('.').toString()
    }

    /**
     * A recognition's prompt: the [hint] first, then the text just before this audio - Whisper
     * and OpenAI keep the end of a prompt that is too long, and the text before matters more.
     * Null when there is neither.
     */
    fun prompt(hint: String?, precedingText: String?): String? =
        listOfNotNull(hint, precedingText)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .takeIf { it.isNotEmpty() }

    /** The cleanup's tag around the words; a word can never hold it ([clean] drops < and >). */
    const val TAG = "own_words"

    /**
     * The cleanup's rule for the words, or null without any: their spellings are kept where the
     * dictation means them, and they are never put in where it does not.
     */
    fun cleanupRule(words: List<String>): String? {
        val kept = bounded(words).takeIf { it.isNotEmpty() } ?: return null
        return "Spellings: the user's own names and terms. Where the dictation means one of them - " +
            "even heard a little differently - write it exactly as listed. Never add one that was not said.\n" +
            "<$TAG>\n${kept.joinToString("\n")}\n</$TAG>"
    }
}
