package app.amethyst.core

import app.amethyst.core.bubble.BubblePlacement
import app.amethyst.core.bubble.Box
import app.amethyst.core.bubble.SafeArea
import app.amethyst.core.bubble.Screen
import kotlin.test.Test
import kotlin.test.assertEquals

class BubblePlacementTest {

    // Pixel 9a: 1080 x 2424, a 56 dp bubble at the right edge.
    private val bare = Screen(1080, 2424)
    private val size = 147
    private val x = 933
    private val gap = 31

    /** 80 dp: clear of a chat app's input bar and its send button. */
    private val above = 210

    private fun y(
        rest: Int,
        keyboardTop: Int? = null,
        avoid: List<Box> = emptyList(),
        aboveKeyboard: Int = above,
        screen: Screen = bare,
    ) = BubblePlacement.y(rest, x, size, screen, gap, keyboardTop, aboveKeyboard, avoid)

    @Test
    fun `with nothing in the way it stays where it was left`() {
        assertEquals(1650, y(1650))
    }

    @Test
    fun `with a docked keyboard it sits at its own height above the keys`() {
        assertEquals(1466 - above - size, y(1650, keyboardTop = 1466))
    }

    @Test
    fun `the height above the keyboard is its own, whatever the resting height`() {
        // Resting high up: the keyboard brings it down to its keyboard height, and it goes
        // back up when the keyboard leaves.
        assertEquals(1466 - above - size, y(400, keyboardTop = 1466))
        assertEquals(400, y(400, keyboardTop = null))
    }

    @Test
    fun `the default keyboard height is well above the input bar, not on the send button`() {
        // WhatsApp's input bar on a Pixel 9a: about 1300..1450 above a keyboard at 1466.
        val bubbleBottom = y(1650, keyboardTop = 1466) + size
        assertEquals(true, bubbleBottom <= 1300, "bubble bottom $bubbleBottom")
    }

    @Test
    fun `a height below the gap is clamped to the gap above the keys`() {
        assertEquals(1466 - gap - size, y(1650, keyboardTop = 1466, aboveKeyboard = 0))
    }

    @Test
    fun `a short screen clamps both heights to the safe area`() {
        // Landscape with the keyboard up: little room above it.
        val landscape = Screen(2424, 1080, SafeArea(top = 0, bottom = 63))
        assertEquals(0, y(500, keyboardTop = 300, screen = landscape))
        // Without the keyboard the resting height stays above the gesture bar.
        assertEquals(1080 - 63 - size, y(2000, screen = landscape))
    }

    @Test
    fun `the status bar is never covered`() {
        val withBars = Screen(1080, 2424, SafeArea(top = 145, bottom = 63))
        assertEquals(145, y(10, screen = withBars))
        assertEquals(2424 - 63 - size, y(2400, screen = withBars))
    }

    @Test
    fun `the keyboard height that a drag sets is measured from the bubble's bottom`() {
        assertEquals(1466 - (1000 + size), BubblePlacement.aboveKeyboard(y = 1000, size = size, keyboardTop = 1466))
        assertEquals(0, BubblePlacement.aboveKeyboard(y = 1400, size = size, keyboardTop = 1466), "dropped on the keys")
    }

    @Test
    fun `it steps over the field being typed in`() {
        // A tall field right above the keyboard, spanning the width.
        val field = Box(30, 1000, 1050, 1290)
        assertEquals(1000 - gap - size, y(1650, keyboardTop = 1466, avoid = listOf(field)))
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
        assertEquals(1466 - above - size, y(1650, keyboardTop = 1466, avoid = listOf(editor)))
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
        // A field near the keyboard and a floating toolbar right above the field: stepping
        // over the field alone would land in the toolbar.
        val field = Box(0, 1150, 1080, 1300)
        val floating = Box(600, 950, 1080, 1140)
        assertEquals(950 - gap - size, y(1330, keyboardTop = 1500, avoid = listOf(field, floating)))
    }

    @Test
    fun `with no room on either side it stays put rather than leave the screen`() {
        val field = Box(0, 20, 1080, 600)
        assertEquals(100, y(100, keyboardTop = 700, aboveKeyboard = 453, avoid = listOf(field)))
    }
}
