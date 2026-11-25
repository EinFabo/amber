package app.amethyst.core.insert

/**
 * How Amber knows its own clip on the clipboard - a dictation copied (Copy last, the clipboard
 * fallback) or a Diagnostics report - so forgetting what was dictated
 * ([app.amethyst.core.dictation.DictationMemory.wipe]) clears the clipboard only while that clip
 * is still there, never something the user copied since.
 *
 * Every clip Amber writes carries [MARK] in its description's extras; clips from versions before
 * the mark are known by their exact label.
 */
object OwnClip {

    /** The label of a dictation's clip; English, a name for the system. */
    const val LABEL = "sayEmber"

    /** The label of a copied Diagnostics report. */
    const val DIAGNOSTICS_LABEL = "sayEmber diagnostics"

    /** The extra (a boolean, true) in the description of every clip Amber writes. */
    const val MARK = "app.amethyst.extra.OWN_CLIP"

    /** The labels the app's clips had before [MARK] - when it was still called Amber. */
    private val OLD_LABELS = setOf("Amber", "Amber diagnostics")

    /** Whether the clip on the clipboard - its [label], [marked] with [MARK] - is Amber's. */
    fun isOurs(label: CharSequence?, marked: Boolean): Boolean = marked || label?.toString() in OLD_LABELS

    /**
     * Whether the clip written [writtenAgoMs] ago to paste a dictation may be cleared now that
     * the words are in the field. [ours] is what the clipboard says of the clip on it now
     * ([isOurs]), or null when Android would not say: since Android 10 only the app in front
     * may look at the clipboard, and after a paste into another app that app is in front. Then
     * the clip is taken to be still the one written for this paste only within
     * [PASTE_WINDOW_MS] - the paste and its check take a few seconds at most, and nobody copies
     * something else by hand in that time. Later, or when another clip is there, it stays.
     */
    fun clearAfterPaste(ours: Boolean?, writtenAgoMs: Long): Boolean = when (ours) {
        true -> true
        false -> false
        null -> writtenAgoMs in 0..PASTE_WINDOW_MS
    }

    /** How long after its writing a clip whose owner cannot be read still counts as the paste's. */
    const val PASTE_WINDOW_MS = 10_000L
}
