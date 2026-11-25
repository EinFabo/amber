package app.amethyst.core.bubble

/**
 * How a finished dictation looks on the bubble: words in the field are done, words that only
 * went onto the clipboard still need the user to paste them - not the same, so not the same look.
 */
enum class DeliveryLook {
    INSERTED,
    ON_CLIPBOARD,
    ;

    companion object {
        fun of(insertedIntoField: Boolean, copiedToClipboard: Boolean): DeliveryLook =
            if (!insertedIntoField && copiedToClipboard) ON_CLIPBOARD else INSERTED
    }
}
