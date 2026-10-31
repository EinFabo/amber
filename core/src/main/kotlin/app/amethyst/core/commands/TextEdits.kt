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

    /**
     * Where an input connection types a new line. It sees only the text [around] the cursor,
     * starting at [offset] in the field - -1 where the field does not say. Behind a selection
     * the cursor first moves to the selection's end, a place in the whole field; without the
     * offset that place is unknown, and a break typed anyway would land somewhere else.
     */
    fun lineBreakPlan(around: FieldState, offset: Int): LineBreakPlan {
        if (around.selectionStart == around.selectionEnd) return LineBreakPlan.AtCursor
        if (offset < 0) return LineBreakPlan.Unknown
        return LineBreakPlan.After(offset + maxOf(around.selectionStart, around.selectionEnd))
    }

    /**
     * Whether a typed line break arrived where it belongs: the text before the cursor [after]
     * is what lay before the cursor - behind any selection - [before], plus the break. Both
     * are windows of capped length ending at the cursor, so one only has to end the other. A
     * cursor that merely moved is no new line.
     */
    fun lineBreakLanded(before: FieldState, after: FieldState): Boolean {
        val (text, _, end) = clamped(before)
        val expected = text.substring(0, end) + "\n"
        val (afterText, afterStart) = clamped(after)
        val actual = afterText.substring(0, afterStart)
        return actual.endsWith("\n") && (expected.endsWith(actual) || actual.endsWith(expected))
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

/** How a new line goes in through an input connection ([TextEdits.lineBreakPlan]). */
sealed interface LineBreakPlan {
    /** At the cursor as it is. */
    data object AtCursor : LineBreakPlan

    /** The cursor moves to [position] in the field first: behind the selection. */
    data class After(val position: Int) : LineBreakPlan

    /** Behind a selection whose place in the field is not known: not through the connection. */
    data object Unknown : LineBreakPlan
}
