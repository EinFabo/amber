package app.amethyst.core.commands

import app.amethyst.core.text.FieldState

/**
 * The text changes behind the text commands, worked out on the field's contents before
 * anything is written. Words are what lies between whitespace, punctuation included, and they
 * go as Ctrl+Backspace takes them: the spaces right before the cursor, then the word, the space
 * in front of it staying. A selection is never deleted - "select all", then "delete the last
 * word" must not empty the field - and never typed over by a new line.
 */
object TextEdits {

    /** [field] without the [count] words before its cursor (a selection's start). */
    fun deleteWords(field: FieldState, count: Int): FieldState {
        val (text, start, end) = clamped(field)
        val from = wordStart(text, start, count)
        val removed = start - from
        return FieldState(text.removeRange(from, start), start - removed, end - removed)
    }

    /** How many characters before the cursor [deleteWords] takes, for an input connection's delete. */
    fun charsBeforeCursor(field: FieldState, count: Int): Int {
        val (text, start) = clamped(field)
        return start - wordStart(text, start, count)
    }

    /** A line break at the cursor - after a selection, which stays as it is - the cursor behind it. */
    fun newLine(field: FieldState): FieldState {
        val (text, _, end) = clamped(field)
        return FieldState(text.substring(0, end) + "\n" + text.substring(end), end + 1)
    }

    private fun wordStart(text: String, cursor: Int, count: Int): Int {
        var i = cursor
        repeat(count.coerceAtLeast(0)) {
            while (i > 0 && text[i - 1].isWhitespace()) i--
            while (i > 0 && !text[i - 1].isWhitespace()) i--
        }
        return i
    }

    /** The text, then the selection ordered and held to it. */
    private fun clamped(field: FieldState): Triple<String, Int, Int> {
        val text = field.text
        val a = field.selectionStart.coerceIn(0, text.length)
        val b = field.selectionEnd.coerceIn(0, text.length)
        return Triple(text, minOf(a, b), maxOf(a, b))
    }
}
