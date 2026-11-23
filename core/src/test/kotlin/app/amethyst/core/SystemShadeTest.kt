package app.amethyst.core

import app.amethyst.core.bubble.Box
import app.amethyst.core.bubble.BubbleVisibility
import app.amethyst.core.bubble.SafeArea
import app.amethyst.core.bubble.Screen
import app.amethyst.core.bubble.SystemShade
import app.amethyst.core.settings.BubbleSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemShadeTest {

    // Pixel 9a: 1080 x 2424, status bar 145 px, gesture bar 63 px.
    private val screen = Screen(1080, 2424, SafeArea(top = 145, bottom = 63))
    private val whole = Box(0, 0, 1080, 2424)
    private val statusBar = Box(0, 0, 1080, 145)
    private val app = Box(0, 0, 1080, 2424)

    private fun window(bounds: Box, system: Boolean = true, inUse: Boolean = true, owner: String? = SystemShade.SYSTEM_UI) =
        SystemShade.Window(system, bounds, inUse) { owner }

    @Test
    fun `the pulled-down shade is a focused SystemUI window over the whole screen`() {
        assertTrue(SystemShade.open(listOf(window(whole), window(app, system = false, inUse = false, owner = "com.whatsapp")), screen))
    }

    @Test
    fun `the collapsed status bar is only a strip at the top`() {
        assertFalse(SystemShade.open(listOf(window(statusBar), window(app, system = false, owner = "com.whatsapp")), screen))
    }

    @Test
    fun `a big SystemUI window nobody is in is not the shade - Android's chat bubbles`() {
        assertFalse(SystemShade.open(listOf(window(whole, inUse = false)), screen))
    }

    @Test
    fun `another app's system window is not the shade`() {
        assertFalse(SystemShade.open(listOf(window(whole, owner = "com.example.overlay")), screen))
    }

    @Test
    fun `an app window over the whole screen is not the shade`() {
        assertFalse(SystemShade.open(listOf(window(app, system = false)), screen))
    }

    @Test
    fun `a dialog of a quick settings tile or the volume panel covers neither the top nor half the screen`() {
        val dialog = Box(40, 900, 1040, 1800)
        val volume = Box(900, 600, 1060, 1500)
        assertFalse(SystemShade.open(listOf(window(dialog), window(volume)), screen))
    }

    @Test
    fun `a shade below the status bar still counts - the status bar stays above it on some phones`() {
        assertTrue(SystemShade.open(listOf(window(Box(0, 145, 1080, 2424))), screen))
    }

    @Test
    fun `the owner is only asked of a window that could be the shade`() {
        var asked = 0
        val strip = SystemShade.Window(true, statusBar, true) { asked++; SystemShade.SYSTEM_UI }
        val idle = SystemShade.Window(true, whole, false) { asked++; SystemShade.SYSTEM_UI }
        SystemShade.open(listOf(strip, idle), screen)
        assertEquals(0, asked)
    }

    @Test
    fun `the open shade hides the bubble, even during a dictation and in a text field`() {
        val settings = BubbleSettings()
        assertFalse(
            BubbleVisibility.wanted(
                dictating = true, currentApp = "com.whatsapp", settings = settings, textFieldFocused = true,
                commandsOn = false, shadeOpen = true,
            ),
        )
        assertTrue(
            BubbleVisibility.wanted(
                dictating = true, currentApp = "com.whatsapp", settings = settings, textFieldFocused = true,
                commandsOn = false, shadeOpen = false,
            ),
        )
    }
}
