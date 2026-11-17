package app.amethyst.core.bubble

/**
 * Which kind of field takes text right now, from every signal the service gets - and whether
 * it is another field than before. What it passes on goes to [FieldPresence] and the keyboard.
 *
 * - **From Android 13** the service's input method hears a field start and stop taking text
 *   ([inputStarted], [inputFinished]): the surest answer, and the only one used there.
 * - **Below Android 13** focus events say a field took focus ([focusEvent]), and a window
 *   change is answered by asking Android which node has input focus now ([windowChanged]).
 * - **Amber's own fields** below Android 13: its screen says when one of them has focus
 *   ([own]). Android names no node with input focus for a Compose field, so every window
 *   change - Amber's own toasts and overlays among them - would end the field again; while
 *   Amber's screen says its field has focus, that field stands. From Android 13 the input
 *   method already tells, and the screen's word is ignored, so nothing is announced twice.
 */
class FieldFocus(
    /** Android 13 and later: the service's input method says which field takes text. */
    private val inputMethodTells: Boolean,
) {

    /** What to pass on: the field's kind, and whether it is another field than before. */
    data class Signal(val kind: TextFieldKind, val fresh: Boolean)

    /** Whether focus events and window lookups are needed at all: only below Android 13. */
    val watchesFocus: Boolean get() = !inputMethodTells

    /** The field the input method is bound to (Android 13+), [TextFieldKind.NONE] without one. */
    private var input = TextFieldKind.NONE

    /** Amber's own screen says one of its fields has focus (below Android 13). */
    var ownFocused = false
        private set

    /** The field passed on last is one of Amber's own. */
    private var lastOwn = false

    /** A field started taking text (Android 13+); [restarting]: the same field again. */
    fun inputStarted(kind: TextFieldKind, restarting: Boolean): Signal {
        input = kind
        return pass(TextFieldKind.NONE, fresh = !restarting, own = false)
    }

    /** The input method's field stopped taking text (Android 13+). */
    fun inputFinished(): Signal {
        input = TextFieldKind.NONE
        return pass(TextFieldKind.NONE, fresh = false, own = false)
    }

    /**
     * Amber's screen says its field gained or lost focus - lost also when the screen is left.
     * On a loss, [lookup] says what has input focus now. Null: nothing to pass on - nothing
     * changed, or the input method tells already (Android 13+).
     */
    fun own(focused: Boolean, lookup: () -> TextFieldKind): Signal? {
        if (inputMethodTells || focused == ownFocused) return null
        ownFocused = focused
        if (!focused) return pass(lookup(), fresh = false, own = false)
        // A focus event from the same field may have come first: then it is known already.
        val known = lastOwn
        return pass(TextFieldKind.TEXT, fresh = !known, own = true)
    }

    /**
     * A view took focus (below Android 13), of [kind], in Amber's own app if [inOwnApp]. Null
     * from Android 13, where these events are not used.
     */
    fun focusEvent(kind: TextFieldKind, inOwnApp: Boolean): Signal? {
        if (inputMethodTells) return null
        // Amber's field, announced by its screen already: Compose sends its focus event a
        // moment after the screen heard of the focus. The same field, not another one.
        if (inOwnApp && ownFocused) return pass(TextFieldKind.TEXT, fresh = false, own = true)
        // A field in another app took the input: Amber's no longer has it (split screen).
        if (!inOwnApp && kind != TextFieldKind.NONE) ownFocused = false
        return pass(kind, fresh = kind != TextFieldKind.NONE, own = inOwnApp && kind != TextFieldKind.NONE)
    }

    /**
     * A window came, went or changed state (below Android 13); [lookup] says what has input
     * focus now. Null: nothing to look up - from Android 13, or a window of the keyboard,
     * which comes and goes without moving the focus. Asked anyway, Android names no node for
     * a Compose field, and the keyboard opening would end the field it opened for.
     */
    fun windowChanged(fromKeyboard: Boolean, lookup: () -> TextFieldKind): Signal? {
        if (inputMethodTells || fromKeyboard) return null
        return pass(lookup(), fresh = false, own = false)
    }

    private fun pass(kind: TextFieldKind, fresh: Boolean, own: Boolean): Signal {
        val effective = when {
            input != TextFieldKind.NONE -> input
            ownFocused -> TextFieldKind.TEXT
            else -> kind
        }
        lastOwn = (own || ownFocused) && effective != TextFieldKind.NONE
        return Signal(effective, fresh)
    }
}
