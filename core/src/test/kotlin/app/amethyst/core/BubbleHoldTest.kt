package app.amethyst.core

import app.amethyst.core.bubble.Box
import app.amethyst.core.bubble.BubbleHold
import app.amethyst.core.bubble.BubblePlacement
import app.amethyst.core.bubble.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BubbleHoldTest {

    // Pixel 9a: 1080 x 2424, a 56 dp bubble at the right edge, 12 dp gap, 80 dp above the keys.
    private val screen = Screen(1080, 2424)
    private val size = 147
    private val x = 933
    private val gap = 32
    private val above = 210
    private val keyboard = 1466

    /** Room for two more lines of a chat's input. */
    private val headroom = 105

    private fun hold(current: Int?, keyboardTop: Int? = keyboard, field: Box? = null) =
        BubbleHold.y(current, 1650, x, size, screen, gap, keyboardTop, above, listOfNotNull(field), headroom)

    private fun ideal(keyboardTop: Int? = keyboard, field: Box? = null) =
        BubblePlacement.y(1650, x, size, screen, gap, keyboardTop, above, listOfNotNull(field))

    /** A chat's input bar across the screen, [lines] high, right above the keys. */
    private fun bar(lines: Int) = Box(0, 1450 - 100 - (lines - 1) * 55, 1080, 1450)

    @Test
    fun `without a place to keep it goes where it belongs`() {
        assertEquals(ideal(), hold(null))
        assertEquals(ideal(field = bar(3)), hold(null, field = bar(3)), "no headroom when placed afresh")
    }

    @Test
    fun `a field growing line by line moves the bubble once, not once a line`() {
        var y = hold(null, field = bar(1))
        assertEquals(ideal(), y, "one line leaves the bubble at its own height")
        val moves = (2..4).count { lines ->
            val next = hold(y, field = bar(lines))
            (next != y).also { y = next }
        }
        assertEquals(1, moves)
        assertTrue(BubbleHold.allowed(y, x, size, screen, gap, keyboard, listOf(bar(4))))
    }

    @Test
    fun `it does not come back down when the field shrinks again`() {
        val up = hold(ideal(), field = bar(3))
        assertTrue(up < ideal())
        assertEquals(up, hold(up, field = bar(1)), "the message was sent, the bar is one line again")
        assertEquals(up, hold(up, field = null), "a lookup that found no field moves nothing")
    }

    @Test
    fun `the keyboard's edge moving a little moves nothing`() {
        val y = ideal()
        assertEquals(y, hold(y, keyboardTop = keyboard - 40), "a suggestion row: still clear of the keys")
        assertEquals(y, hold(y, keyboardTop = keyboard + 60), "a shorter keyboard: no need to follow it down")
    }

    @Test
    fun `a keyboard growing into the bubble moves it to its height above the new edge`() {
        val y = ideal()
        val taller = keyboard - 400
        assertFalse(BubbleHold.allowed(y, x, size, screen, gap, taller, emptyList()))
        assertEquals(ideal(keyboardTop = taller), hold(y, keyboardTop = taller))
    }

    @Test
    fun `a place outside the safe area is not kept`() {
        val notch = Screen(1080, 2424, app.amethyst.core.bubble.SafeArea(top = 120))
        assertFalse(BubbleHold.allowed(60, x, size, notch, gap, null, emptyList()))
        assertTrue(BubbleHold.allowed(120, x, size, notch, gap, null, emptyList()))
    }

    @Test
    fun `a field too tall to step around is no reason to move`() {
        val editor = Box(0, 200, 1080, 1450)
        val y = ideal()
        assertEquals(y, hold(y, field = editor))
    }

    @Test
    fun `a field beside the bubble's column is not in its way`() {
        val narrow = Box(0, 1100, 900, 1450)
        val y = ideal()
        assertEquals(y, hold(y, field = narrow))
    }
}
