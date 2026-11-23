package app.amethyst.core

import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.Features
import app.amethyst.core.settings.SettingsCodec
import app.amethyst.core.settings.SettingsGroupKind
import app.amethyst.core.settings.SettingsItem
import app.amethyst.core.settings.SettingsLayout
import app.amethyst.core.settings.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Settings page's rows, all of them shown - no "Advanced settings" switch any more. */
class SettingsLayoutTest {

    @Test
    fun `settings saved with the old advanced switch still read, and it changes nothing`() {
        for (advanced in listOf(true, false)) {
            val old = """{"activeEngineId":"android-on-device","engines":[],"ui":{"advanced":$advanced,"theme":"DARK"},"schemaVersion":16}"""
            assertEquals(ThemeMode.DARK, SettingsCodec.decode(old, TEST_PHONE).ui.theme)
        }
    }

    @Test
    fun `settings from before light and dark follow the phone`() {
        val old = """{"activeEngineId":"android-on-device","engines":[],"ui":{"advanced":false},"schemaVersion":14}"""
        assertEquals(ThemeMode.SYSTEM, SettingsCodec.decode(old, TEST_PHONE).ui.theme)
        // ... and those from before the "ui" section too, as does a new install.
        val older = """{"activeEngineId":"android-on-device","engines":[],"schemaVersion":10}"""
        assertEquals(ThemeMode.SYSTEM, SettingsCodec.decode(older, TEST_PHONE).ui.theme)
        assertEquals(ThemeMode.SYSTEM, AmethystSettings.defaults(TEST_PHONE).ui.theme)
    }

    @Test
    fun `the chosen theme survives a save`() {
        for (theme in ThemeMode.entries) {
            val settings = AmethystSettings.defaults(TEST_PHONE).let { it.copy(ui = it.ui.copy(theme = theme)) }
            assertEquals(theme, SettingsCodec.decode(SettingsCodec.encode(settings), TEST_PHONE).ui.theme)
        }
    }

    @Test
    fun `light and dark are as chosen, and as the phone is set otherwise`() {
        assertTrue(ThemeMode.SYSTEM.dark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.dark(systemDark = false))
        assertFalse(ThemeMode.LIGHT.dark(systemDark = true))
        assertTrue(ThemeMode.DARK.dark(systemDark = false))
    }

    @Test
    fun `every page is a row of its own, in the order people look for them`() {
        assertEquals(
            listOf(
                SettingsGroupKind.MAIN to listOf(
                    SettingsItem.RECOGNITION, SettingsItem.CLEANUP, SettingsItem.OWN_WORDS, SettingsItem.INSERTING,
                    SettingsItem.RECORDING, SettingsItem.BUBBLE, SettingsItem.PRIVACY,
                ),
                SettingsGroupKind.PER_APP to listOf(SettingsItem.APP_STYLES),
                SettingsGroupKind.AMBER to listOf(SettingsItem.RELIABILITY, SettingsItem.APPEARANCE, SettingsItem.ABOUT),
            ),
            SettingsLayout.groups(voiceCommands = false).map { it.kind to it.items },
        )
    }

    @Test
    fun `voice commands show only with the feature, last in the main group`() {
        assertFalse(SettingsLayout.shows(SettingsItem.VOICE_COMMANDS, voiceCommands = false))
        assertTrue(SettingsLayout.shows(SettingsItem.VOICE_COMMANDS, voiceCommands = true))
        val main = SettingsLayout.groups(voiceCommands = true).first { it.kind == SettingsGroupKind.MAIN }
        assertEquals(SettingsItem.VOICE_COMMANDS, main.items.last())
        assertEquals(Features.VOICE_COMMANDS, SettingsLayout.shows(SettingsItem.VOICE_COMMANDS))
    }

    @Test
    fun `every row is listed once, the page about Amber last`() {
        val listed = SettingsLayout.groups(voiceCommands = true).flatMap { it.items }
        assertEquals(SettingsItem.entries.toList(), listed.sortedBy { it.ordinal })
        assertEquals(listed.distinct(), listed)
        assertEquals(SettingsItem.ABOUT, listed.last())
    }

    @Test
    fun `a group of one row has no heading over it`() {
        val groups = SettingsLayout.groups(voiceCommands = false).associateBy { it.kind }
        assertFalse(groups.getValue(SettingsGroupKind.PER_APP).labeled)
        assertTrue(groups.getValue(SettingsGroupKind.AMBER).labeled)
    }
}
