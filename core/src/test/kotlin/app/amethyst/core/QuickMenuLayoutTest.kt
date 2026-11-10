package app.amethyst.core

import app.amethyst.core.bubble.QuickMenuItem
import app.amethyst.core.bubble.QuickMenuLayout
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.SettingsCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuickMenuLayoutTest {

    @Test
    fun `nothing stored is today's menu`() {
        assertEquals(
            listOf(QuickMenuItem.INSERT_AGAIN, QuickMenuItem.UNDO_LAST, QuickMenuItem.CLEANUP_HERE, QuickMenuItem.COPY_LAST),
            QuickMenuLayout.idle(null),
        )
        assertNull(AmethystSettings.defaults(TEST_PHONE).bubble.quickMenuIdle)
    }

    @Test
    fun `a stored menu reads place by place, empty places kept`() {
        assertEquals(
            listOf(QuickMenuItem.OPEN_AMBER, null, QuickMenuItem.STYLE_HERE, QuickMenuItem.HIDE_HERE),
            QuickMenuLayout.idle(listOf("open_amber", null, "style_here", "hide_here")),
        )
        assertEquals(listOf<QuickMenuItem?>(null, null, null, null), QuickMenuLayout.idle(listOf(null, null, null, null)))
    }

    @Test
    fun `an id from a newer Amber is an empty place`() {
        assertEquals(
            listOf(QuickMenuItem.COPY_LAST, null, QuickMenuItem.APP_PAGE, null),
            QuickMenuLayout.idle(listOf("copy_last", "summon_unicorn", "app_page", "")),
        )
    }

    @Test
    fun `an item twice keeps only its first place`() {
        assertEquals(
            listOf(QuickMenuItem.NEXT_STYLE, null, QuickMenuItem.COPY_LAST, null),
            QuickMenuLayout.idle(listOf("next_style", "next_style", "copy_last", "next_style")),
        )
    }

    @Test
    fun `always four places`() {
        assertEquals(listOf(QuickMenuItem.COPY_LAST, null, null, null), QuickMenuLayout.idle(listOf("copy_last")))
        assertEquals(4, QuickMenuLayout.idle(listOf("copy_last", "undo_last", "open_amber", "app_page", "hide_here")).size)
        assertEquals(listOf<QuickMenuItem?>(null, null, null, null), QuickMenuLayout.idle(emptyList()))
    }

    @Test
    fun `every id reads back as its item`() {
        for (item in QuickMenuItem.entries) assertEquals(item, QuickMenuItem.byId(item.id))
        assertEquals(QuickMenuItem.entries.size, QuickMenuItem.entries.map { it.id }.toSet().size)
    }

    @Test
    fun `today's menu is stored as nothing, any other as its ids`() {
        assertNull(QuickMenuLayout.store(QuickMenuLayout.DEFAULT_IDLE))
        val own = listOf(QuickMenuItem.OPEN_AMBER, null, QuickMenuItem.COPY_LAST, QuickMenuItem.HIDE_HERE)
        assertEquals(listOf("open_amber", null, "copy_last", "hide_here"), QuickMenuLayout.store(own))
        assertEquals(own, QuickMenuLayout.idle(QuickMenuLayout.store(own)))
    }

    @Test
    fun `choosing puts an item into its place`() {
        val chosen = QuickMenuLayout.choose(QuickMenuLayout.DEFAULT_IDLE, 1, QuickMenuItem.OPEN_AMBER)
        assertEquals(listOf(QuickMenuItem.INSERT_AGAIN, QuickMenuItem.OPEN_AMBER, QuickMenuItem.CLEANUP_HERE, QuickMenuItem.COPY_LAST), chosen)
    }

    @Test
    fun `choosing one used elsewhere swaps the two places`() {
        val swapped = QuickMenuLayout.choose(QuickMenuLayout.DEFAULT_IDLE, 0, QuickMenuItem.COPY_LAST)
        assertEquals(listOf(QuickMenuItem.COPY_LAST, QuickMenuItem.UNDO_LAST, QuickMenuItem.CLEANUP_HERE, QuickMenuItem.INSERT_AGAIN), swapped)
        // Into an empty place: the old place is left empty.
        val fromEmpty = QuickMenuLayout.choose(listOf(null, QuickMenuItem.COPY_LAST, null, null), 3, QuickMenuItem.COPY_LAST)
        assertEquals(listOf<QuickMenuItem?>(null, null, null, QuickMenuItem.COPY_LAST), fromEmpty)
    }

    @Test
    fun `choosing empty clears the place, choosing what is there changes nothing`() {
        assertEquals(
            listOf(QuickMenuItem.INSERT_AGAIN, null, QuickMenuItem.CLEANUP_HERE, QuickMenuItem.COPY_LAST),
            QuickMenuLayout.choose(QuickMenuLayout.DEFAULT_IDLE, 1, null),
        )
        assertEquals(QuickMenuLayout.DEFAULT_IDLE, QuickMenuLayout.choose(QuickMenuLayout.DEFAULT_IDLE, 2, QuickMenuItem.CLEANUP_HERE))
    }

    @Test
    fun `the place of an item`() {
        assertEquals(3, QuickMenuLayout.placeOf(QuickMenuLayout.DEFAULT_IDLE, QuickMenuItem.COPY_LAST))
        assertNull(QuickMenuLayout.placeOf(QuickMenuLayout.DEFAULT_IDLE, QuickMenuItem.OPEN_AMBER))
    }

    @Test
    fun `a stored menu survives a save, and settings from before it read as today's`() {
        val base = AmethystSettings.defaults(TEST_PHONE)
        val own = base.copy(bubble = base.bubble.copy(quickMenuIdle = listOf("open_amber", null, "next_style", "future_action")))
        assertEquals(own.bubble.quickMenuIdle, SettingsCodec.decode(SettingsCodec.encode(own), TEST_PHONE).bubble.quickMenuIdle)
        assertNull(SettingsCodec.decode(SettingsCodec.encode(base), TEST_PHONE).bubble.quickMenuIdle)
    }
}
