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

    /** What the text before the cursor says about the last insertion. */
    sealed interface Match {
        /** It ends with the dictation: [chars] characters to delete, seam included. */
        data class Found(val chars: Int) : Match

        /**
         * It ends like the dictation but stops before the dictation's start: the field holds
         * less than that, or the read handed back only part of the text before the cursor.
         */
        data object TooShort : Match

        /** It no longer ends with the dictation (or there was nothing to look for). */
        data object Different : Match
    }

    /**
     * How many characters before the cursor to delete, or null when [beforeCursor] no longer
     * ends with [insertion]'s text ([match]).
     */
    fun charsToDelete(beforeCursor: String, insertion: Insertion): Int? =
        (match(beforeCursor, insertion) as? Match.Found)?.chars

    /**
     * Whether [beforeCursor] still ends with [insertion]'s text, read from the cursor back.
     * Letter case may differ: a field may capitalize on its own. Spacing may too, as it may
     * when the insertion is checked ([Landed]): a field may turn a space into a line break or
     * a no-break space, or two spaces into one - and a long dictation, several chunks joined,
     * is where that happens. So a run of whitespace in the dictation matches any run in the
     * field, but never none: words run together are a change. At the dictation's two ends a
     * field may have dropped the whitespace (a search field trimming) - at its start only where
     * the field begins, since elsewhere the seam to the user's own text is gone - and never
     * more is taken there than the dictation had, so the user's own spaces stay.
     * [fieldStart] says [beforeCursor] begins where the field does; a read that began inside it
     * cannot show a seam it stops at, so a dictation reaching its very start is [Match.TooShort].
     */
    fun match(beforeCursor: String, insertion: Insertion, fieldStart: Boolean = true): Match {
        val fragment = insertion.fragment
        if (fragment.isBlank()) return Match.Different
        val coreEnd = fragment.indexOfLast { !it.isWhitespace() } + 1
        val coreStart = fragment.indexOfFirst { !it.isWhitespace() }
        var j = beforeCursor.length
        // The dictation's trailing whitespace: up to as much as it had, none is fine.
        j = skipWhitespace(beforeCursor, j, limit = fragment.length - coreEnd)
        var i = coreEnd
        while (i > coreStart) {
            val c = fragment[i - 1]
            if (c.isWhitespace()) {
                while (i > coreStart && fragment[i - 1].isWhitespace()) i--
                val from = j
                j = skipWhitespace(beforeCursor, j, limit = Int.MAX_VALUE)
                if (j == 0 && from == 0) return Match.TooShort
                if (j == from) return Match.Different
                continue
            }
            if (j == 0) return Match.TooShort
            if (!c.equals(beforeCursor[j - 1], ignoreCase = true)) return Match.Different
            i--
            j--
        }
        // A dictation that came without a seam went in where a word could not go on: at the
        // field's start or after a space or a mark. A word before it now is another text -
        // "Ja." is not the end of "Naja.".
        if (coreStart == 0 && j > 0 && fragment[0].isLetterOrDigit() && beforeCursor[j - 1].isLetterOrDigit()) return Match.Different
        // The seam before the dictation: where the field begins it may be gone.
        if (coreStart > 0 && j == 0 && !fieldStart) return Match.TooShort
        if (coreStart > 0 && j > 0) {
            val from = j
            j = skipWhitespace(beforeCursor, j, limit = coreStart)
            if (j == from) return Match.Different
        }
        return Match.Found(beforeCursor.length - j)
    }

    /**
     * Whether a read of the text before the cursor ([read], [asked] characters asked for)
     * stopped before the field's start, so the field may hold more than it shows: it began
     * inside the field ([offset] past 0), or - where it began is unknown ([offset] below 0) -
     * it handed back all that was asked. One that began at the field's start shows all there
     * is, and an empty one shows nothing: neither says the dictation lies beyond it.
     */
    fun cutShort(read: String, offset: Int, asked: Int): Boolean =
        read.isNotEmpty() && (offset > 0 || offset < 0 && read.length >= asked)

    /** [end] moved back over at most [limit] whitespace characters of [text]. */
    private fun skipWhitespace(text: String, end: Int, limit: Int): Int {
        var j = end
        while (j > 0 && end - j < limit && text[j - 1].isWhitespace()) j--
        return j
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
