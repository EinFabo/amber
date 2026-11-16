package app.amethyst.core.text

/**
 * The user's own words - names and terms a speech recognition does not know ("Kubernetes",
 * "Anna Lüdtke") - and what each place that uses them gets:
 *
 * - Whisper on the phone and a server that takes a prompt: [hint], the words as a short list
 *   before the text (Whisper writes what follows its prompt in the prompt's spellings).
 *   Whisper reads only the last 224 tokens of a prompt, and Groq refuses a longer one, so the
 *   list stops at [HINT_CHARS] - the first words count - and the whole [prompt], the list with
 *   the text before it, at [PROMPT_CHARS].
 * - A small Whisper writes its prompt back on silence or very short audio: a text that is
 *   nothing but the list again ([echoed]) counts as nothing heard.
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

    /**
     * Characters of a whole recognition prompt, the list and the text before together: Whisper's
     * 224 tokens at a conservative 2 characters a token, so Groq and OpenAI never refuse it -
     * a refused prompt would be retried without one, own words and all.
     */
    const val PROMPT_CHARS = 448

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
     * At most [PROMPT_CHARS]: the text before keeps its end, the list as many of its first
     * words as there is room for. Null when there is neither.
     */
    fun prompt(hint: String?, precedingText: String?): String? {
        val before = precedingText?.trim()?.takeLast(PROMPT_CHARS)?.trim().orEmpty()
        val room = PROMPT_CHARS - if (before.isEmpty()) 0 else before.length + 1
        val list = hint?.let { fitted(it.trim(), room) }.orEmpty()
        return listOf(list, before).filter { it.isNotEmpty() }.joinToString(" ").takeIf { it.isNotEmpty() }
    }

    /** A [hint] cut to its first words that fit [room] characters, with its full stop; empty when none fits. */
    private fun fitted(hint: String, room: Int): String {
        if (hint.length <= room) return hint
        val out = StringBuilder()
        for (word in words(hint)) {
            val next = if (out.isEmpty()) word else ", $word"
            if (out.length + next.length + 1 > room) break
            out.append(next)
        }
        return if (out.isEmpty()) "" else out.append('.').toString()
    }

    /** The words of a [hint] again: "Anna Lüdtke, Kubernetes." to "Anna Lüdtke" and "Kubernetes". */
    private fun words(hint: String): List<String> =
        hint.trim().removeSuffix(".").split(", ").map { it.trim() }.filter { it.isNotEmpty() }

    private val TOKEN = Regex("[\\p{L}\\p{N}]+")

    /**
     * Whether [text] is (nearly) only the [hint] written back - what a small Whisper does with
     * its prompt on silence or very short audio: at least nine in ten of its words are own
     * words, and it holds two or more of them. A dictation of a single own word stays; so does
     * one with a few words of its own around them ("Kubernetes and Amber").
     */
    fun echoed(text: String, hint: String?): Boolean {
        val own = words(hint ?: return false)
            .map { word -> TOKEN.findAll(word.lowercase()).map { it.value }.toList() }
            .filter { it.isNotEmpty() }
        val tokens = TOKEN.findAll(text.lowercase()).map { it.value }.toList()
        if (tokens.isEmpty()) return false
        val ownTokens = own.flatten().toSet()
        val inList = tokens.count { it in ownTokens }
        val present = tokens.toSet()
        val heard = own.count { word -> word.all { it in present } }
        return heard >= 2 && inList * 10 >= tokens.size * 9
    }

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
