package app.amethyst.core

import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.Features
import app.amethyst.core.settings.SettingsCodec
import app.amethyst.core.settings.SettingsGroupKind
import app.amethyst.core.settings.SettingsItem
import app.amethyst.core.settings.SettingsLayout
import app.amethyst.core.settings.UiSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The "Advanced settings" switch and its table (roadmap P3). */
class SettingsLayoutTest {

    @Test
    fun `a new install starts with the simple settings`() {
        assertFalse(AmethystSettings.defaults(TEST_PHONE).ui.advanced)
        assertFalse(SettingsCodec.decode("", TEST_PHONE).ui.advanced)
    }

    @Test
    fun `an upgrade from before the switch shows every setting`() {
        val old = """{"activeEngineId":"android-on-device","engines":[],"schemaVersion":10}"""
        assertTrue(SettingsCodec.decode(old, TEST_PHONE).ui.advanced)
    }

    @Test
    fun `the switch survives a save`() {
        for (advanced in listOf(true, false)) {
            val settings = AmethystSettings.defaults(TEST_PHONE).copy(ui = UiSettings(advanced = advanced))
            assertEquals(advanced, SettingsCodec.decode(SettingsCodec.encode(settings), TEST_PHONE).ui.advanced)
        }
    }

    @Test
    fun `the simple view is recognition, cleanup, bubble and app styles, plus Amber itself`() {
        val simple = SettingsLayout.groups(advanced = false, voiceCommands = false)
        assertEquals(
            listOf(
                SettingsGroupKind.MAIN to listOf(SettingsItem.RECOGNITION, SettingsItem.CLEANUP, SettingsItem.BUBBLE),
                SettingsGroupKind.PER_APP to listOf(SettingsItem.APP_STYLES),
                SettingsGroupKind.AMBER to listOf(SettingsItem.UPDATES, SettingsItem.LICENSES, SettingsItem.INTRODUCTION),
            ),
            simple.map { it.kind to it.items },
        )
    }

    @Test
    fun `the advanced view adds every other setting`() {
        val all = SettingsLayout.groups(advanced = true, voiceCommands = false)
        assertEquals(
            listOf(
                SettingsGroupKind.MAIN to listOf(SettingsItem.RECOGNITION, SettingsItem.CLEANUP, SettingsItem.BUBBLE),
                SettingsGroupKind.PER_APP to listOf(SettingsItem.APP_STYLES),
                SettingsGroupKind.ADVANCED to listOf(SettingsItem.FALLBACK, SettingsItem.RECORDING, SettingsItem.INSERTING, SettingsItem.RELIABILITY),
                SettingsGroupKind.AMBER to listOf(
                    SettingsItem.STATISTICS, SettingsItem.DIAGNOSTICS, SettingsItem.UPDATES, SettingsItem.LICENSES, SettingsItem.INTRODUCTION,
                ),
            ),
            all.map { it.kind to it.items },
        )
    }

    @Test
    fun `voice commands show only with the feature, and only in the advanced view`() {
        assertFalse(SettingsLayout.shows(SettingsItem.VOICE_COMMANDS, advanced = true, voiceCommands = false))
        assertFalse(SettingsLayout.shows(SettingsItem.VOICE_COMMANDS, advanced = false, voiceCommands = true))
        assertTrue(SettingsLayout.shows(SettingsItem.VOICE_COMMANDS, advanced = true, voiceCommands = true))
        val main = SettingsLayout.groups(advanced = true, voiceCommands = true).first { it.kind == SettingsGroupKind.MAIN }
        assertEquals(SettingsItem.VOICE_COMMANDS, main.items.last())
    }

    @Test
    fun `the feature switch decides by default`() {
        assertEquals(Features.VOICE_COMMANDS, SettingsLayout.shows(SettingsItem.VOICE_COMMANDS, advanced = true))
    }

    @Test
    fun `the cleanup's own addition and system prompt are advanced`() {
        assertFalse(SettingsLayout.shows(SettingsItem.CLEANUP_INSTRUCTION, advanced = false))
        assertTrue(SettingsLayout.shows(SettingsItem.CLEANUP_INSTRUCTION, advanced = true))
        assertTrue(SettingsLayout.groups(advanced = true, voiceCommands = true).none { SettingsItem.CLEANUP_INSTRUCTION in it.items })
    }

    @Test
    fun `the simple view says how many rows the switch hides`() {
        assertEquals(6, SettingsLayout.hiddenRows(voiceCommands = false))
        assertEquals(7, SettingsLayout.hiddenRows(voiceCommands = true))
    }

    @Test
    fun `every settings row has its place in the table`() {
        val listed = SettingsLayout.groups(advanced = true, voiceCommands = true).flatMap { it.items }
        assertEquals(SettingsItem.entries.filter { it.group != null }.toSet(), listed.toSet())
        assertEquals(listed.size, listed.toSet().size)
    }
}
