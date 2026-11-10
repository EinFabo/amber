package app.amethyst.core.bubble

/**
 * What a place of the idle quick menu can hold, as the editor lists it. [id] is what the
 * settings store ([app.amethyst.core.settings.BubbleSettings.quickMenuIdle]) - never rename
 * one: a stored menu would lose it. The cleanup switch is one entry; the menu shows it as on
 * or off for the app in front ([QuickActions.slots]).
 */
enum class QuickMenuItem(val id: String) {
    INSERT_AGAIN("insert_again"),
    UNDO_LAST("undo_last"),
    CLEANUP_HERE("cleanup_here"),
    COPY_LAST("copy_last"),
    HIDE_HERE("hide_here"),
    OPEN_AMBER("open_amber"),
    STYLE_HERE("style_here"),
    NEXT_STYLE("next_style"),
    APP_PAGE("app_page"),
    ;

    companion object {
        fun byId(id: String?): QuickMenuItem? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The idle quick menu as the user put it together (Settings -> Bubble -> Quick menu): four
 * places, top to bottom on both edges, each an item or empty. Listening and working stay fixed,
 * so Cancel keeps the top place under the thumb.
 */
object QuickMenuLayout {

    /**
     * Today's menu, for everyone who never opens the editor. Hiding the bubble in an app is a
     * drag onto the X; the menu keeps what only it does.
     */
    val DEFAULT_IDLE: List<QuickMenuItem?> = listOf(
        QuickMenuItem.INSERT_AGAIN,
        QuickMenuItem.UNDO_LAST,
        QuickMenuItem.CLEANUP_HERE,
        QuickMenuItem.COPY_LAST,
    )

    /**
     * The four places from what is stored: null is [DEFAULT_IDLE]. An id this Amber does not
     * know (written by a newer one) and an item already above are empty places, and a short or
     * long list is cut or filled to four - a place never moves.
     */
    fun idle(stored: List<String?>?): List<QuickMenuItem?> {
        if (stored == null) return DEFAULT_IDLE
        val seen = mutableSetOf<QuickMenuItem>()
        return List(QuickMenuGeometry.SLOTS) { i ->
            QuickMenuItem.byId(stored.getOrNull(i))?.takeIf { seen.add(it) }
        }
    }

    /** What to store for [layout]: null while it is today's menu, so it follows the default. */
    fun store(layout: List<QuickMenuItem?>): List<String?>? =
        if (layout == DEFAULT_IDLE) null else List(QuickMenuGeometry.SLOTS) { i -> layout.getOrNull(i)?.id }

    /**
     * [item] (null: empty) put into [place]. An item already in another place swaps with what
     * was in [place], so every item is in one place at most.
     */
    fun choose(layout: List<QuickMenuItem?>, place: Int, item: QuickMenuItem?): List<QuickMenuItem?> {
        require(place in 0 until QuickMenuGeometry.SLOTS) { "place $place" }
        val places = List(QuickMenuGeometry.SLOTS) { layout.getOrNull(it) }.toMutableList()
        val other = item?.let { places.indexOf(it) }?.takeIf { it >= 0 && it != place }
        if (other != null) places[other] = places[place]
        places[place] = item
        return places
    }

    /** The place [item] is in, or null. */
    fun placeOf(layout: List<QuickMenuItem?>, item: QuickMenuItem): Int? = layout.indexOf(item).takeIf { it >= 0 }
}
