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

    private fun y(
        rest: Int,
        keyboardTop: Int? = null,
        keyboards: List<Box> = emptyList(),
        screen: Screen = bare,
    ) = BubblePlacement.y(rest, x, size, screen, gap, keyboardTop, keyboards)

    @Test
    fun `with nothing in the way it stays where it was left`() {
        assertEquals(1650, y(1650))
    }

    @Test
    fun `a keyboard that opens below it does not move it`() {
        assertEquals(777, y(777, keyboardTop = 1466))
        assertEquals(777, y(777, keyboardTop = null))
    }

    @Test
    fun `it stays at the last place that is off the keys`() {
        val lowest = 1466 - gap - size
        assertEquals(lowest, y(lowest, keyboardTop = 1466))
        assertEquals(lowest, y(lowest + 1, keyboardTop = 1466))
    }

    @Test
    fun `a place where the keyboard opens is left for the one just above the keys, and taken again`() {
        assertEquals(1466 - gap - size, y(1650, keyboardTop = 1466))
        assertEquals(1650, y(1650, keyboardTop = null))
    }

    @Test
    fun `a taller keyboard lifts it only as far as the keys reach`() {
        // Sitting right above the letters; an emoji panel 200 px taller comes up.
        val place = 1466 - gap - size
        assertEquals(1266 - gap - size, y(place, keyboardTop = 1266))
        assertEquals(place, y(place, keyboardTop = 1466))
    }

    @Test
    fun `a short screen clamps it to the safe area`() {
        // Landscape with the keyboard up: little room above it.
        val landscape = Screen(2424, 1080, SafeArea(top = 0, bottom = 63))
        assertEquals(300 - gap - size, y(500, keyboardTop = 300, screen = landscape))
        // And none at all: the top of the screen rather than off it.
        assertEquals(0, y(500, keyboardTop = 120, screen = landscape))
        // Without the keyboard it stays above the gesture bar.
        assertEquals(1080 - 63 - size, y(2000, screen = landscape))
    }

    @Test
    fun `the status bar is never covered`() {
        val withBars = Screen(1080, 2424, SafeArea(top = 145, bottom = 63))
        assertEquals(145, y(10, screen = withBars))
        assertEquals(2424 - 63 - size, y(2400, screen = withBars))
    }

    @Test
    fun `a floating keyboard is avoided only where it is`() {
        val floating = Box(500, 1500, 1060, 1900)
        // The bubble's place is in it: out to the nearer side, here below the panel.
        assertEquals(1900 + gap, y(1650, keyboards = listOf(floating)))
        // Above it: up is nearer.
        assertEquals(1500 - gap - size, y(1400, keyboards = listOf(floating)))
        // Elsewhere, the floating keyboard does not move it at all.
        assertEquals(800, y(800, keyboards = listOf(floating)))
    }

    @Test
    fun `a floating keyboard on the other side of the screen is no obstacle`() {
        assertEquals(1650, y(1650, keyboards = listOf(Box(0, 1600, 500, 1750))))
    }

    @Test
    fun `a floating keyboard as tall as the screen's half is still kept clear of`() {
        val tall = Box(400, 300, 1080, 1700)
        assertEquals(300 - gap - size, y(600, keyboards = listOf(tall)))
    }

    @Test
    fun `with no room on either side it stays put rather than leave the screen`() {
        val floating = Box(0, 20, 1080, 600)
        assertEquals(100, y(100, keyboardTop = 700, keyboards = listOf(floating)))
    }

    @Test
    fun `a place it was put at is one it stays at`() {
        val floating = Box(500, 1500, 1060, 1900)
        for (rest in listOf(0, 400, 1300, 1400, 1650, 1880, 2400)) {
            val docked = y(rest, keyboardTop = 1466)
            assertEquals(docked, y(docked, keyboardTop = 1466), "docked keyboard, from $rest")
            val beside = y(rest, keyboards = listOf(floating))
            assertEquals(beside, y(beside, keyboards = listOf(floating)), "floating keyboard, from $rest")
        }
    }

    @Test
    fun `between the status bar and a docked keyboard both edges hold`() {
        val withBars = Screen(1080, 2424, SafeArea(top = 145, bottom = 63))
        assertEquals(145, y(10, keyboardTop = 1466, screen = withBars))
        assertEquals(1466 - gap - size, y(2000, keyboardTop = 1466, screen = withBars))
    }

    @Test
    fun `below a floating keyboard is no place when the docked keys or the screen's end are there`() {
        // Nearer below, but that is on the docked keys: up instead.
        val floating = Box(500, 1000, 1060, 1350)
        assertEquals(1000 - gap - size, y(1300, keyboardTop = 1466, keyboards = listOf(floating)))
        // And at the bottom of the screen: up as well.
        val low = Box(500, 2000, 1060, 2300)
        assertEquals(2000 - gap - size, y(2250, keyboards = listOf(low)))
    }

    @Test
    fun `let go off the keys, that is its place`() {
        assertEquals(900, BubblePlacement.dropped(900, x, size, bare, gap, keyboardTop = 1466))
        assertEquals(1900, BubblePlacement.dropped(1900, x, size, bare, gap, keyboardTop = null))
    }

    @Test
    fun `let go on the keys, the nearest place off them is its place`() {
        assertEquals(1466 - gap - size, BubblePlacement.dropped(1700, x, size, bare, gap, keyboardTop = 1466))
        val floating = Box(500, 1500, 1060, 1900)
        assertEquals(1900 + gap, BubblePlacement.dropped(1800, x, size, bare, gap, null, listOf(floating)))
    }

    @Test
    fun `let go while a keyboard leaves no room, where it was let go stays its place`() {
        val landscape = Screen(2424, 1080, SafeArea(top = 0, bottom = 63))
        assertEquals(500, BubblePlacement.dropped(500, x, size, landscape, gap, keyboardTop = 120))
        // Shown at the top for now.
        assertEquals(0, y(500, keyboardTop = 120, screen = landscape))
    }
}
