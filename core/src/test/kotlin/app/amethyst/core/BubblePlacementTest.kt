package app.amethyst.core

import app.amethyst.core.bubble.BubblePlacement
import app.amethyst.core.bubble.Box
import kotlin.test.Test
import kotlin.test.assertEquals

class BubblePlacementTest {

    // Pixel 9a: 1080 x 2424, a 56 dp bubble at the right edge.
    private val screen = 2424
    private val size = 147
    private val x = 933
    private val gap = 31

    private fun y(rest: Int, keyboardTop: Int? = null, avoid: List<Box> = emptyList()) =
        BubblePlacement.y(rest, x, size, screen, gap, keyboardTop, avoid)

    @Test
    fun `with nothing in the way it stays where it was left`() {
        assertEquals(1650, y(1650))
    }

    @Test
    fun `a docked keyboard lifts it above the keys, and only then`() {
        assertEquals(1466 - gap - size, y(1650, keyboardTop = 1466))
        assertEquals(1050, y(1050, keyboardTop = 1466), "already above: no move")
    }

    @Test
    fun `it steps over the field being typed in`() {
        // A chat input just above the keyboard, spanning the width.
        val field = Box(30, 1180, 1050, 1290)
        assertEquals(1180 - gap - size, y(1650, keyboardTop = 1466, avoid = listOf(field)))
    }

    @Test
    fun `it moves to the nearer free side of a field`() {
        val field = Box(0, 600, 1080, 760)
        // Reaching into the field from above: up is nearer.
        assertEquals(600 - gap - size, y(500, avoid = listOf(field)))
        // Resting near its bottom: below is nearer.
        assertEquals(760 + gap, y(740, avoid = listOf(field)))
    }

    @Test
    fun `a field on the other side of the screen is no obstacle`() {
        assertEquals(1650, y(1650, avoid = listOf(Box(0, 1600, 500, 1750))))
    }

    @Test
    fun `a full-screen editor is not stepped around`() {
        val editor = Box(0, 300, 1080, 1400)
        assertEquals(1466 - gap - size, y(1650, keyboardTop = 1466, avoid = listOf(editor)))
    }

    @Test
    fun `a floating keyboard is avoided only where it is`() {
        val floating = Box(500, 1500, 1060, 1900)
        // The bubble rests in it: out to the nearer side, here below the panel.
        assertEquals(1900 + gap, y(1650, avoid = listOf(floating)))
        // Resting elsewhere, the floating keyboard does not move it at all.
        assertEquals(800, y(800, avoid = listOf(floating)))
    }

    @Test
    fun `two obstacles on top of each other are both cleared`() {
        // A field right above the keyboard and a floating toolbar right above the field:
        // stepping over the field alone would land in the toolbar.
        val field = Box(0, 1300, 1080, 1400)
        val floating = Box(600, 1100, 1080, 1290)
        assertEquals(1100 - gap - size, y(1330, keyboardTop = 1500, avoid = listOf(field, floating)))
    }

    @Test
    fun `with no room on either side it stays put rather than leave the screen`() {
        val field = Box(0, 20, 1080, 600)
        assertEquals(100, y(100, keyboardTop = 700, avoid = listOf(field)))
    }
}
