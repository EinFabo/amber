package app.amethyst.core.insert

/**
 * What a field's accessibility node says about its text, read the way the insertion needs it.
 * Android's node is behind plain values here, so each case is a test.
 */
object FieldText {

    /** The class name of an editable text field: EditText and its kin, Compose's, Chrome's inputs. */
    const val EDIT_TEXT = "android.widget.EditText"

    /**
     * The text a field holds, without its placeholder: a field showing its hint reports the
     * hint as its text, and the words would be spliced onto "Message" or "Type a note". Most
     * fields say so ([showingHint]); some only hand out text equal to their [hint]. That counts
     * as empty while the cursor stands at the start (or nowhere) - where a field with nothing
     * in it keeps it - so a field into which someone typed the very words of its hint, cursor
     * behind them, keeps its text.
     */
    fun contents(text: CharSequence?, hint: CharSequence?, showingHint: Boolean, selectionStart: Int, selectionEnd: Int): String {
        if (showingHint) return ""
        val shown = text?.toString().orEmpty()
        val placeholder = hint?.toString()
        val cursorAtStart = selectionStart <= 0 && selectionEnd <= 0
        return if (!placeholder.isNullOrEmpty() && shown == placeholder && cursorAtStart) "" else shown
    }

    /**
     * Writing the whole field back (ACTION_SET_TEXT) would flatten it: its text carries spans
     * someone would miss ([hasSpans]: links, mentions, formatting) that a plain string
     * replaces, or the node is not an EditText at all ([className]) - a rich editor of its
     * own, which may take the write as a new document. Such a field gets the words pasted at
     * the cursor instead, when the clipboard may be used ([InsertionEngine]). An unknown class
     * says nothing.
     */
    fun setTextFlattens(className: CharSequence?, hasSpans: Boolean): Boolean =
        hasSpans || (className != null && className.toString() != EDIT_TEXT)
}
