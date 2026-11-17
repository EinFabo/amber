package app.amethyst.core.bubble

/**
 * The quick menu's buttons are glyphs; a name shows on the one a finger slides toward. For the
 * first [FIRST_OPENS] opens every button carries its name beside it, so nobody has to slide over
 * each one to find "Copy last". The opens are counted in the settings
 * ([app.amethyst.core.settings.BubbleSettings.quickMenuOpens]), up to [FIRST_OPENS] and no
 * further, so the settings are not written on every long press after that.
 */
object QuickMenuNames {

    const val FIRST_OPENS = 5

    /** Whether a menu opened after [opens] earlier ones names every button. */
    fun allNamed(opens: Int): Boolean = opens < FIRST_OPENS

    /** The count to store after one more open, or null when it stays as it is. */
    fun afterOpen(opens: Int): Int? = if (opens < FIRST_OPENS) (opens + 1).coerceAtLeast(1) else null
}
