package app.amethyst.core.insert

import app.amethyst.core.text.FieldState

/**
 * A field as only its input connection shows it: some text before the cursor, the selection,
 * some text behind it - a window onto the field, not the field. It is what a keyboard reads
 * (InputConnection.getSurroundingText), and all there is of a field Android names no node for:
 * a Compose field up to 1.7, the Claude app's and Amber's own test field among them.
 *
 * Enough for the seam - the space, the capital after a sentence end - which only needs the
 * characters next to the cursor, and for checking that typed words arrived ([readBack]).
 */
data class FieldWindow(
    val text: String,
    /** Within [text], like [selectionEnd]. */
    val selectionStart: Int,
    val selectionEnd: Int = selectionStart,
    /**
     * Where [text] begins in the field. Null when the connection does not say and the window
     * is as long as was asked for: more text may stand before it.
     */
    val offset: Int? = null,
) {
    /** For the seam and the comparison: the window read as if it were the field. */
    val state: FieldState get() = FieldState(text, selectionStart, selectionEnd)

    /** What stands before the cursor, or before the selection. */
    val head: String get() = text.substring(0, minOf(selectionStart, selectionEnd))

    /** Nothing in it at all: an empty field - or one that shows nothing of itself. */
    val isEmpty: Boolean get() = text.isEmpty()

    companion object {
        /** Characters read before the cursor: far more than a seam needs, for the comparison. */
        const val BEFORE = 1_000

        /** Characters read behind the cursor; the seam needs the first. */
        const val AFTER = 100

        /** Read on top of what was typed when looking for it: a field may add to it. */
        private const val MARGIN = 32

        /** How far before the cursor to read after typing [typed] characters. */
        fun beforeAfterTyping(typed: Int): Int = BEFORE + typed.coerceAtLeast(0) + MARGIN

        /**
         * A window from what the connection handed out, or null when it is of no use: no text,
         * or a selection that is not in it - then nobody knows what stands before the cursor.
         *
         * [offset] is the connection's, negative when it does not know (the platform's default
         * for a connection that only reads before and behind the cursor - Compose's). A window
         * shorter before the cursor than the [askedBefore] characters asked for begins at the
         * field's start. Half a surrogate pair at either edge - the window cut an emoji in two -
         * is left out.
         */
        fun of(text: CharSequence?, selectionStart: Int, selectionEnd: Int, offset: Int, askedBefore: Int): FieldWindow? {
            var content = text?.toString() ?: return null
            var start = selectionStart
            var end = selectionEnd
            if (minOf(start, end) < 0 || maxOf(start, end) > content.length) return null
            var begins: Int? = when {
                offset >= 0 -> offset
                minOf(start, end) < askedBefore -> 0
                else -> null
            }
            if (minOf(start, end) > 0 && content.first().isLowSurrogate()) {
                content = content.substring(1)
                start--
                end--
                begins = begins?.plus(1)
            }
            if (maxOf(start, end) < content.length && content.last().isHighSurrogate()) {
                content = content.substring(0, content.length - 1)
            }
            return FieldWindow(content, start, end, begins)
        }

        /**
         * [readBack] of the two begins exactly where [before] did - both say where they begin,
         * or [before] began at the field's start - and is not found by looking for [before]'s
         * text, which the app may have changed meanwhile.
         */
        fun aligned(before: FieldWindow, after: FieldWindow): Boolean =
            before.offset == 0 || (before.offset != null && after.offset != null)

        /**
         * [after]'s text from where [before] begins, so that the two can be compared ([Landed]
         * counts the words before and after). The window read after typing reaches further
         * back - by what was typed, so a long dictation is in it from its first word - and
         * what it shows on top of [before] must not count: the same words standing there
         * would pass for the ones just typed, which may never have arrived.
         *
         * Where both say where they begin, that decides. Else [before]'s text before the
         * cursor is looked for in [after]: where it stands last, the window began. And where
         * it is gone - the app changed it meanwhile - only as many characters before the
         * cursor are kept as [before] had: rather a text reported as not arrived than one
         * taken as there that is not.
         */
        fun readBack(before: FieldWindow, after: FieldWindow): String {
            val from = before.offset
            val to = after.offset
            if (from != null && to != null) return after.text.substring((from - to).coerceIn(0, after.text.length))
            // [after] begins at the field's start or later, [before] at its start: nothing on top.
            if (from == 0) return after.text

            val head = before.head
            val cursor = minOf(after.selectionStart, after.selectionEnd).coerceIn(0, after.text.length)
            val latest = cursor - head.length
            val found = if (head.isEmpty() || latest < 0) -1 else after.text.lastIndexOf(head, latest)
            return after.text.substring(if (found >= 0) found else latest.coerceAtLeast(0))
        }
    }
}

/**
 * A field reached only through its input connection, for [InsertionEngine]: typing is the one
 * way in, and the field is read as a [FieldWindow]. The platform layer supplies the two calls.
 *
 * A connection that hands out nothing - it is gone, the app does not answer, the field is not
 * a text editor - leaves the field unreadable: the words are typed as they are and nothing can
 * be checked, as before there was a window. So does a field that read empty before and reads
 * empty still: it may be a terminal or a game, whose connection has no text of its own, and
 * words that arrived there must not be reported lost.
 */
abstract class WindowedTarget : EditableTarget {

    /**
     * Up to [before] characters before the cursor, the selection, up to [after] behind it.
     * Null when the connection does not answer.
     */
    protected abstract fun read(before: Int, after: Int): FieldWindow?

    /** Types [text] at the cursor, over the selection. False when there is no connection. */
    protected abstract fun type(text: String): Boolean

    /**
     * False for a field that only takes key presses (input type "none"): its connection has
     * no text to hand out, and an empty answer would be read as an empty field.
     */
    protected open val showsText: Boolean get() = true

    private var before: FieldWindow? = null
    private var typed = 0

    final override val isEditable: Boolean get() = true

    final override fun readField(): FieldState? {
        typed = 0
        before = if (showsText) read(FieldWindow.BEFORE, FieldWindow.AFTER) else null
        return before?.state
    }

    /** The window [readField] just read is the editor's own: no placeholder is ever in it. */
    final override fun editorHead(): String? = before?.head

    final override fun commitText(text: String): Boolean {
        if (!type(text)) return false
        typed = text.length
        return true
    }

    /**
     * Types [text] in place of the [count] characters right before the cursor: selects them and
     * types over them where [cursor] - the cursor's place in the field - is known, else deletes
     * them and types. False when there is no connection.
     */
    protected open fun replaceBefore(count: Int, text: String, cursor: Int?): Boolean = false

    private var aligned = false

    final override val readBackAligned: Boolean get() = aligned

    final override fun readBack(): String? {
        aligned = false
        val was = before ?: return null
        val now = read(FieldWindow.beforeAfterTyping(typed), FieldWindow.AFTER) ?: return null
        if (was.isEmpty && now.isEmpty) return null
        aligned = FieldWindow.aligned(was, now)
        return FieldWindow.readBack(was, now)
    }

    /** Read again right before, so [shown] is known to stand behind the cursor this moment. */
    final override fun retype(shown: String, text: String): Boolean {
        if (shown.isEmpty() || !showsText) return false
        val now = read(shown.length + RETYPE_MARGIN, 0) ?: return false
        if (now.selectionStart != now.selectionEnd || !now.head.endsWith(shown)) return false
        if (!replaceBefore(shown.length, text, now.offset?.plus(now.selectionStart))) return false
        typed = text.length
        return true
    }

    /** A window is not the field: writing it back would cut the field down to it. */
    final override fun setText(text: String): Boolean = false

    final override fun setSelection(cursor: Int) = Unit

    /** A position in the window is none in the field: where the cursor went is the app's to say. */
    final override fun readCursor(): FieldState? = null

    final override fun paste(): Boolean = false

    private companion object {
        /** Read before the cursor on top of the words to type again: a window may cut an emoji off its edge. */
        const val RETYPE_MARGIN = 2
    }
}
