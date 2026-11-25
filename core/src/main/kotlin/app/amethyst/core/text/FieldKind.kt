package app.amethyst.core.text

/**
 * What the words are typed into, as far as it changes how they are written: a search box, a web
 * address or an e-mail address gets no sentence capital and no full stop - "pizza near me", not
 * "Pizza near me." - as the keyboard would not give them one either ([TextSplicer]).
 *
 * From the field's input type and IME options (EditorInfo.inputType and imeOptions, the input
 * type also AccessibilityNodeInfo.getInputType()); the constants are EditorInfo's, copied so this
 * stays plain JVM and testable.
 */
enum class FieldKind {
    /** Prose: messages, notes, mails, anything not below. */
    TEXT,

    /** A search box: its text variation says so, or the keyboard's action key is Search. */
    SEARCH,

    /** A web address. */
    URI,

    /** An e-mail address. */
    EMAIL;

    companion object {
        private const val TYPE_MASK_CLASS = 0x0000000f
        private const val TYPE_MASK_VARIATION = 0x00000ff0
        private const val TYPE_CLASS_TEXT = 1
        private const val TYPE_TEXT_VARIATION_URI = 0x10
        private const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS = 0x20
        private const val TYPE_TEXT_VARIATION_FILTER = 0xb0
        private const val TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS = 0xd0
        private const val IME_MASK_ACTION = 0xff
        private const val IME_ACTION_SEARCH = 3

        /** [imeOptions] 0 where they are not known (a field reached only through its accessibility node). */
        fun of(inputType: Int, imeOptions: Int = 0): FieldKind {
            if ((inputType and TYPE_MASK_CLASS) == TYPE_CLASS_TEXT) {
                when (inputType and TYPE_MASK_VARIATION) {
                    TYPE_TEXT_VARIATION_URI -> return URI
                    TYPE_TEXT_VARIATION_EMAIL_ADDRESS, TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> return EMAIL
                    TYPE_TEXT_VARIATION_FILTER -> return SEARCH
                }
            }
            return if ((imeOptions and IME_MASK_ACTION) == IME_ACTION_SEARCH) SEARCH else TEXT
        }
    }
}
