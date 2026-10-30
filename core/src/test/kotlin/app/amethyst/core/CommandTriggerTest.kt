package app.amethyst.core

import app.amethyst.core.bubble.BubbleVisibility
import app.amethyst.core.capture.TriggerSource
import app.amethyst.core.commands.CommandTrigger
import app.amethyst.core.commands.RunMode
import app.amethyst.core.settings.BubbleSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandTriggerTest {

    @Test
    fun `a bubble tap without a text field is a command, only with commands on`() {
        assertEquals(RunMode.COMMAND, CommandTrigger.modeAtStart(TriggerSource.BUBBLE, commandsOn = true, textFieldFocused = false))
        assertEquals(RunMode.DICTATION, CommandTrigger.modeAtStart(TriggerSource.BUBBLE, commandsOn = true, textFieldFocused = true))
        assertEquals(RunMode.DICTATION, CommandTrigger.modeAtStart(TriggerSource.BUBBLE, commandsOn = false, textFieldFocused = false))
    }

    @Test
    fun `dictating inside Amber is never a command`() {
        assertEquals(RunMode.DICTATION, CommandTrigger.modeAtStart(TriggerSource.IN_APP, commandsOn = true, textFieldFocused = false))
    }

    @Test
    fun `a second tap soon after a tap that started recording is a double tap`() {
        assertTrue(CommandTrigger.isDoubleTap(previousTapUpAt = 1_000, previousTapStarted = true, downAt = 1_250, commandsOn = true))
    }

    @Test
    fun `too late, after a stop or with commands off it is a plain tap`() {
        assertFalse(CommandTrigger.isDoubleTap(previousTapUpAt = 1_000, previousTapStarted = true, downAt = 1_400, commandsOn = true))
        assertFalse(CommandTrigger.isDoubleTap(previousTapUpAt = 1_000, previousTapStarted = false, downAt = 1_100, commandsOn = true))
        assertFalse(CommandTrigger.isDoubleTap(previousTapUpAt = 1_000, previousTapStarted = true, downAt = 1_100, commandsOn = false))
        assertFalse(CommandTrigger.isDoubleTap(previousTapUpAt = null, previousTapStarted = true, downAt = 1_100, commandsOn = true))
    }
}

class BubbleVisibilityTest {

    private val onlyWithField = BubbleSettings(onlyWithTextField = true, hiddenIn = listOf("com.game"))

    private fun wanted(dictating: Boolean = false, app: String? = "com.whatsapp", field: Boolean = false, commands: Boolean = false, settings: BubbleSettings = onlyWithField) =
        BubbleVisibility.wanted(dictating, app, settings, field, commands)

    @Test
    fun `only with a text field, unless voice commands are on`() {
        assertFalse(wanted())
        assertTrue(wanted(field = true))
        assertTrue(wanted(commands = true))
    }

    @Test
    fun `an app on the hidden list stays hidden even with commands on`() {
        assertFalse(wanted(app = "com.game", field = true, commands = true))
    }

    @Test
    fun `a running dictation keeps its bubble anywhere`() {
        assertTrue(wanted(dictating = true, app = "com.game"))
    }

    @Test
    fun `shown everywhere means everywhere`() {
        assertTrue(wanted(settings = onlyWithField.copy(onlyWithTextField = false)))
    }
}
