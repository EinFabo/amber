package app.amethyst.core.insert

import app.amethyst.core.text.FieldState

/**
 * "Undo last" from the bubble's quick menu: the last dictation taken out of the field again -
 * only while it still stands right before the cursor as it was put there. Anything typed,
 * moved or changed since, and nothing is touched: deleting characters that are no longer the
 * dictation would delete the user's own words.
 */
object UndoLast {

    /** The last insertion that can be undone: in which app, and exactly what went in. */
    data class Insertion(val packageName: String, val fragment: String)

    /**
     * How many characters before the cursor to delete, or null when [beforeCursor] no longer
     * ends with [insertion]'s text. Letter case may differ: a field may capitalize on its own.
     */
    fun charsToDelete(beforeCursor: String, insertion: Insertion): Int? {
        val fragment = insertion.fragment
        if (fragment.isEmpty() || beforeCursor.length < fragment.length) return null
        return if (beforeCursor.endsWith(fragment, ignoreCase = true)) fragment.length else null
    }

    /**
     * What a whole-field write ([after], its cursor at [cursor]) added before the cursor to
     * [before]: the text in between, when everything else stayed as it was. Null when the
     * splice also changed what was there before (a pulled-back punctuation mark, say).
     */
    fun added(before: FieldState, after: String, cursor: Int): String? {
        val start = minOf(before.selectionStart, before.selectionEnd).coerceIn(0, before.text.length)
        val end = maxOf(before.selectionStart, before.selectionEnd).coerceIn(0, before.text.length)
        val head = before.text.substring(0, start)
        val tail = before.text.substring(end)
        if (cursor !in head.length..after.length || !after.startsWith(head) || !after.substring(cursor).equals(tail)) return null
        return after.substring(head.length, cursor).takeIf { it.isNotEmpty() }
    }
}
