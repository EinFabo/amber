package app.amethyst.core.bubble

/**
 * What kind of field has focus, from its Android input type (EditorInfo.inputType, the same
 * number AccessibilityNodeInfo.getInputType() reports). Decides whether the bubble offers
 * itself: nobody dictates a password, a PIN or a phone number, and a bubble over a banking
 * app's PIN pad is the last thing it should be.
 *
 * The constants are EditorInfo's, copied so this stays plain JVM and testable.
 */
enum class TextFieldKind {
    /** Prose: messages, notes, search, mail, addresses. */
    TEXT,

    /** Passwords and numeric PINs; the bubble stays away. */
    SECRET,

    /** Numbers, phone numbers, dates and times. */
    NUMERIC,

    /** Takes no text at all (a view that only wants key events). */
    NONE;

    val wantsDictation: Boolean get() = this == TEXT

    companion object {
        private const val TYPE_MASK_CLASS = 0x0000000f
        private const val TYPE_MASK_VARIATION = 0x00000ff0
        private const val TYPE_NULL = 0
        private const val TYPE_CLASS_TEXT = 1
        private const val TYPE_CLASS_NUMBER = 2
        private const val TYPE_CLASS_PHONE = 3
        private const val TYPE_CLASS_DATETIME = 4

        private const val TYPE_TEXT_VARIATION_PASSWORD = 0x80
        private const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x90
        private const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0xe0
        private const val TYPE_NUMBER_VARIATION_PASSWORD = 0x10

        /** [isPassword] is the node's own flag, which web forms set without an input type. */
        fun of(inputType: Int, isPassword: Boolean = false): TextFieldKind {
            if (isPassword) return SECRET
            if (inputType == TYPE_NULL) return NONE
            val variation = inputType and TYPE_MASK_VARIATION
            return when (inputType and TYPE_MASK_CLASS) {
                TYPE_CLASS_TEXT -> when (variation) {
                    TYPE_TEXT_VARIATION_PASSWORD,
                    TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    TYPE_TEXT_VARIATION_WEB_PASSWORD,
                    -> SECRET
                    else -> TEXT
                }
                TYPE_CLASS_NUMBER -> if (variation == TYPE_NUMBER_VARIATION_PASSWORD) SECRET else NUMERIC
                TYPE_CLASS_PHONE, TYPE_CLASS_DATETIME -> NUMERIC
                // A class Android may add later: rather offer the bubble than hide it.
                else -> TEXT
            }
        }
    }
}
