package app.amethyst.core.text

/**
 * A Whisper writes its prompt back: on silence the whole of it ([OwnWords.echoed] makes that
 * nothing heard), and on a short dictation the own-words list in front of what was said -
 * "Anna Lüdtke, Kubernetes, Amber. Ja." for "Ja." Typed into the field, the list would be
 * words nobody said.
 */
object PromptEcho {

    /**
     * [text] without the own-words list of [hint] ([OwnWords.hint]: "Anna Lüdtke, Kubernetes,
     * Amber.") written back in front of the words: the list from its first word, two words or
     * more of it, comma after comma as the prompt has them, and its full stop - then the words
     * that were said. A list the speaker said themselves rarely has that shape; one that goes
     * on as a sentence ("Anna Lüdtke, Kubernetes und Amber sind da.") stays, as does a text
     * that is nothing but the list (that is [OwnWords.echoed]'s).
     */
    fun withoutLeadingHint(text: String, hint: String?): String {
        val words = hint?.trim()?.removeSuffix(".")?.split(", ")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (words.size < 2) return text
        // Longest first: the whole list before a part of it.
        for (count in words.size downTo 2) {
            val list = words.take(count).joinToString(""",\s*""") { Regex.escape(it) }
            val echo = Regex("""^\s*$list\.\s+(?=\S)""", RegexOption.IGNORE_CASE).find(text) ?: continue
            return text.substring(echo.range.last + 1)
        }
        return text
    }
}
