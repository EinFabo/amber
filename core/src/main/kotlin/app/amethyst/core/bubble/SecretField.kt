package app.amethyst.core.bubble

/**
 * Whether the field taking text holds a secret, from the places that may say so: the input
 * type the field gives the keyboard (EditorInfo), the node Android names as having input
 * focus, and - where it names none - the focused editable node found by searching the windows.
 *
 * The search is there for Compose up to 1.7: without TalkBack Android's focus lookup names no
 * node for its fields, and a password field there may ask for a plain text keyboard
 * (PasswordVisualTransformation with the default keyboard type). Only its node's own password
 * flag then says what it is.
 */
object SecretField {

    /** What a node says of itself: its input type and its password flag. */
    data class Node(val inputType: Int, val isPassword: Boolean)

    /**
     * Whether to search the windows for the field's node. [editorType] is the input type the
     * keyboard was given, null when no input has started; [focusFound] whether Android's focus
     * lookup named a node - then that node is asked and nothing is searched.
     *
     * Not when the keyboard's side says "secret" already, and not without a started input:
     * nothing is typed without one. [forBubble]: asked when an input starts, where the answer
     * only decides whether the bubble shows - so only for a field the bubble would show for.
     * The search walks the app's window, on every start of an input that is too much to pay
     * for a number field.
     */
    fun searchNeeded(editorType: Int?, focusFound: Boolean, forBubble: Boolean = false): Boolean {
        if (focusFound || editorType == null) return false
        val kind = TextFieldKind.of(editorType)
        if (kind == TextFieldKind.SECRET) return false
        return !forBubble || kind.wantsDictation
    }

    /** How long the bubble's and the tap's search may walk the windows. */
    const val SEARCH_BUDGET_MS = 50L

    /** What the bubble does for a field that just started taking text. */
    enum class Gate {
        /** The keyboard's input type settles it: the bubble may come now. */
        SHOW,

        /** The keyboard says "secret": no bubble, and no search. */
        NEVER,

        /** The input type says nothing against it: no bubble until the search has answered. */
        WAIT,
    }

    /**
     * The bubble's gate for a field that started with [editorType] and no focused node named by
     * Android ([focusFound] false). A password the keyboard names never gets the bubble, not
     * even for a moment; a text field Compose may have made a password is waited for.
     */
    fun gate(editorType: Int, focusFound: Boolean): Gate = when {
        TextFieldKind.of(editorType) == TextFieldKind.SECRET -> Gate.NEVER
        searchNeeded(editorType, focusFound, forBubble = true) -> Gate.WAIT
        else -> Gate.SHOW
    }

    /** Secret when any of them says so; what is not known says nothing. */
    fun isSecret(editorType: Int?, node: Node?): Boolean =
        (editorType != null && TextFieldKind.of(editorType) == TextFieldKind.SECRET) ||
            (node != null && TextFieldKind.of(node.inputType, node.isPassword) == TextFieldKind.SECRET)
}
