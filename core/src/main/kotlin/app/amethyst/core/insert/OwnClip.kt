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
    const val LABEL = "Embersay"

    /** The label of a copied Diagnostics report. */
    const val DIAGNOSTICS_LABEL = "Embersay diagnostics"

    /** The extra (a boolean, true) in the description of every clip Amber writes. */
    const val MARK = "app.amethyst.extra.OWN_CLIP"

    /** The labels the app's clips had before [MARK] - when it was still called Amber. */
    private val OLD_LABELS = setOf("Amber", "Amber diagnostics")

    /** Whether the clip on the clipboard - its [label], [marked] with [MARK] - is Amber's. */
    fun isOurs(label: CharSequence?, marked: Boolean): Boolean = marked || label?.toString() in OLD_LABELS
}
