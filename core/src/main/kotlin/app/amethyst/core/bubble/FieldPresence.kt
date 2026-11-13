package app.amethyst.core.bubble

/**
 * Whether a field is there to dictate into - what "only when you type" shows the bubble for.
 *
 * Android says when a field starts taking text, but not when it stops: an app that takes its
 * field away inside the same window (YouTube after pressing Search, Spotify's search) sends
 * no end of input and no focus event, and some only hide the keyboard and leave the field in
 * focus. What does happen every time is that the keyboard closes. So a field counts as active
 * while it takes text and its keyboard has not closed: at once when the field starts - the
 * keyboard is on its way - until the keyboard closes or, on a phone that reports its keyboard,
 * never comes ([KeyboardPolicy.Decision.shown]); and again when the keyboard is back. A phone
 * that never reports a keyboard window gives no answer, and the field alone decides as before.
 */
class FieldPresence {

    /** The kind of the field taking text, [TextFieldKind.NONE] without one. */
    var kind: TextFieldKind = TextFieldKind.NONE
        private set

    /** The keyboard's last word for this field: up, closed, or nothing said yet. */
    private var keyboard: Boolean? = null

    val active: Boolean get() = kind.wantsDictation && keyboard != false

    /** In a few words, for the diagnostics. */
    val why: String
        get() = when {
            kind == TextFieldKind.NONE -> "no field"
            !kind.wantsDictation -> "a field that takes no dictation"
            keyboard == false -> "field without its keyboard"
            keyboard == true -> "field with keyboard"
            else -> "field, keyboard expected"
        }

    /**
     * A field took input, or none does any more ([TextFieldKind.NONE]). [fresh]: another field
     * than before, so its keyboard is expected anew; a restart of the input in the same field -
     * some apps do that on every keystroke, or after they hid the keyboard - keeps what the
     * keyboard said. True when [active] changed.
     */
    fun field(kind: TextFieldKind, fresh: Boolean): Boolean {
        val before = active
        if (fresh || kind == TextFieldKind.NONE || this.kind == TextFieldKind.NONE) keyboard = null
        this.kind = kind
        return active != before
    }

    /** What the keyboard's last reading says; null says nothing and changes nothing. True when [active] changed. */
    fun keyboard(shown: Boolean?): Boolean {
        val before = active
        if (shown != null) keyboard = shown
        return active != before
    }
}
