package app.amethyst.core

import app.amethyst.core.bubble.QuickMenuNames
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.SettingsCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickMenuNamesTest {

    @Test
    fun `the first five opens name every button, then only the lit one`() {
        val named = (0..7).map { QuickMenuNames.allNamed(it) }
        assertEquals(listOf(true, true, true, true, true, false, false, false), named)
    }

    @Test
    fun `the count stops at five, so the settings are no longer written`() {
        var opens = 0
        val written = mutableListOf<Int>()
        repeat(8) { QuickMenuNames.afterOpen(opens)?.let { opens = it; written += it } }
        assertEquals(listOf(1, 2, 3, 4, 5), written)
        assertNull(QuickMenuNames.afterOpen(QuickMenuNames.FIRST_OPENS))
        assertFalse(QuickMenuNames.allNamed(opens))
    }

    @Test
    fun `a new install and settings from before the count start at none`() {
        assertEquals(0, AmethystSettings.defaults(TEST_PHONE).bubble.quickMenuOpens)
        val old = """{"activeEngineId":"android-on-device","engines":[],"bubble":{"sizeDp":64},"schemaVersion":15}"""
        val decoded = SettingsCodec.decode(old, TEST_PHONE)
        assertEquals(0, decoded.bubble.quickMenuOpens)
        assertTrue(QuickMenuNames.allNamed(decoded.bubble.quickMenuOpens))
        val counted = decoded.copy(bubble = decoded.bubble.copy(quickMenuOpens = 3))
        assertEquals(3, SettingsCodec.decode(SettingsCodec.encode(counted), TEST_PHONE).bubble.quickMenuOpens)
    }
}
