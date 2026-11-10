package app.amethyst.core.dictation

import app.amethyst.core.stats.countWords
import app.amethyst.core.text.LanguageGuess

/**
 * What Diagnostics say of a dictation's words instead of the words: how long, how many words
 * and lines, and the language when the words say it clearly ([LanguageGuess.fromWords]). Enough
 * to tell a cut-off or a lost line break apart, nothing of what was said.
 */
data class TextShape(val characters: Int, val words: Int, val lines: Int, val language: String?) {

    /** English, for the copy: "412 characters, 68 words, 3 lines, de". */
    fun describe(): String = buildList {
        add(count(characters, "character"))
        add(count(words, "word"))
        add(count(lines, "line"))
        language?.let(::add)
    }.joinToString(", ")

    companion object {
        /** [text]'s shape; its outer blank lines and spaces are not counted. */
        fun of(text: String): TextShape {
            val trimmed = text.trim()
            return TextShape(
                characters = trimmed.codePointCount(0, trimmed.length),
                words = countWords(trimmed),
                lines = if (trimmed.isEmpty()) 0 else LINE_BREAK.findAll(trimmed).count() + 1,
                language = LanguageGuess.fromWords(trimmed),
            )
        }

        private val LINE_BREAK = Regex("\\r\\n|\\r|\\n")

        private fun count(n: Int, what: String) = "$n $what" + if (n == 1) "" else "s"
    }
}
