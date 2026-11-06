package app.amethyst.core

import app.amethyst.core.bubble.BubbleVisibility
import app.amethyst.core.dictation.DiagnosticsReport
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.BubbleSettings
import app.amethyst.core.settings.CommandSettings
import app.amethyst.core.settings.Features
import app.amethyst.core.settings.SettingsCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Voice commands are out of the build (roadmap P3): stored, kept, never effective. */
class VoiceCommandsHiddenTest {

    private val on = CommandSettings(enabled = true)

    @Test
    fun `voice commands are not part of this build`() {
        assertFalse(Features.VOICE_COMMANDS)
    }

    @Test
    fun `commands switched on work only while the feature is in`() {
        assertTrue(on.effective(featureOn = true))
        assertFalse(on.effective(featureOn = false))
        assertFalse(CommandSettings(enabled = false).effective(featureOn = true))
    }

    @Test
    fun `an upgrade with commands on keeps the stored value, but commands stay off`() {
        val old = """{"activeEngineId":"android-on-device","engines":[],"commands":{"enabled":true},"schemaVersion":10}"""
        val settings = SettingsCodec.decode(old, TEST_PHONE)
        assertTrue(settings.commands.enabled, "the stored switch is kept for when the feature returns")
        assertFalse(settings.commandsEnabled)
    }

    @Test
    fun `without voice commands diagnostics need no filter`() {
        assertEquals(emptyList(), DiagnosticsReport.filters(voiceCommands = false))
        assertEquals(DiagnosticsReport.Filter.entries, DiagnosticsReport.filters(voiceCommands = true))
    }

    @Test
    fun `the bubble never stays for commands while they are hidden`() {
        val forCommands = BubbleSettings(onlyWithTextField = true, everywhereForCommands = true)
        val settings = AmethystSettings.defaults(TEST_PHONE).copy(commands = on, bubble = forCommands)
        assertFalse(
            BubbleVisibility.wanted(
                dictating = false, currentApp = null, settings = settings.bubble, textFieldFocused = false,
                commandsOn = settings.commandsEnabled,
            ),
        )
    }
}
