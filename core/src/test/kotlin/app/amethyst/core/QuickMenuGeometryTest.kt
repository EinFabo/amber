package app.amethyst.core

import app.amethyst.core.bubble.BubbleSide
import app.amethyst.core.bubble.QuickMenuGeometry
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickMenuGeometryTest {

    private val dead = 74f

    private fun slot(side: BubbleSide, dx: Float, dy: Float) = QuickMenuGeometry.slotAt(side, dx, dy, dead)

    @Test
    fun `on the right edge the fan opens to the left, top to bottom`() {
        assertEquals(0, slot(BubbleSide.RIGHT, -30f, -200f), "up")
        assertEquals(1, slot(BubbleSide.RIGHT, -200f, -60f), "up and inward")
        assertEquals(2, slot(BubbleSide.RIGHT, -200f, 60f), "down and inward")
        assertEquals(3, slot(BubbleSide.RIGHT, -30f, 200f), "down")
    }

    @Test
    fun `the left edge mirrors it, so the same thumb move picks the same slot`() {
        assertEquals(0, slot(BubbleSide.LEFT, 30f, -200f))
        assertEquals(1, slot(BubbleSide.LEFT, 200f, -60f))
        assertEquals(2, slot(BubbleSide.LEFT, 200f, 60f))
        assertEquals(3, slot(BubbleSide.LEFT, 30f, 200f))
    }

    @Test
    fun `inside the dead zone nothing is picked`() {
        assertNull(slot(BubbleSide.RIGHT, -40f, -40f))
        assertNull(slot(BubbleSide.LEFT, 0f, 0f))
    }

    @Test
    fun `distance does not count, only the direction`() {
        assertEquals(1, slot(BubbleSide.RIGHT, -900f, -300f))
    }

    @Test
    fun `straight up or down along the edge, a little outward too, picks the outer slots`() {
        assertEquals(0, slot(BubbleSide.RIGHT, 0f, -200f))
        assertEquals(0, slot(BubbleSide.RIGHT, 30f, -200f), "drifting toward the edge")
        assertEquals(3, slot(BubbleSide.LEFT, -30f, 200f), "drifting toward the edge")
    }

    @Test
    fun `pointing outward picks nothing`() {
        assertNull(slot(BubbleSide.RIGHT, 200f, 0f))
        assertNull(slot(BubbleSide.LEFT, -200f, 30f))
    }

    @Test
    fun `a boundary always gives the same slot`() {
        // Straight inward lies between the two inner slots: always the lower one.
        assertEquals(2, slot(BubbleSide.RIGHT, -200f, 0f))
        assertEquals(2, slot(BubbleSide.LEFT, 200f, 0f))
    }

    @Test
    fun `labels sit inward of the bubble, in slot order from top to bottom`() {
        val labels = (0 until QuickMenuGeometry.SLOTS).map {
            QuickMenuGeometry.labelCentre(BubbleSide.RIGHT, it, cx = 1006f, cy = 1500f, radius = 300f, width = 1080, height = 2424, halfWidth = 120f, halfHeight = 40f)
        }
        assertTrue(labels.all { it.x < 1006f })
        assertEquals(labels.sortedBy { it.y }, labels)
        val left = QuickMenuGeometry.labelCentre(BubbleSide.LEFT, 1, cx = 74f, cy = 1500f, radius = 300f, width = 1080, height = 2424, halfWidth = 120f, halfHeight = 40f)
        assertTrue(left.x > 74f)
    }

    @Test
    fun `labels stay on the screen near a corner`() {
        val top = QuickMenuGeometry.labelCentre(BubbleSide.RIGHT, 0, cx = 1006f, cy = 120f, radius = 300f, width = 1080, height = 2424, halfWidth = 120f, halfHeight = 40f)
        assertTrue(top.y >= 40f, "y ${top.y}")
        assertTrue(top.x <= 1080f - 120f, "x ${top.x}")
        val bottom = QuickMenuGeometry.labelCentre(BubbleSide.LEFT, 3, cx = 74f, cy = 2380f, radius = 300f, width = 1080, height = 2424, halfWidth = 120f, halfHeight = 40f)
        assertTrue(bottom.y <= 2424f - 40f, "y ${bottom.y}")
        assertTrue(bottom.x >= 120f, "x ${bottom.x}")
    }

    @Test
    fun `the name chip sits above the fan and on the screen`() {
        val chip = QuickMenuGeometry.chipCentre(BubbleSide.RIGHT, cx = 1006f, cy = 1500f, radius = 300f, width = 1080, height = 2424, halfWidth = 200f, halfHeight = 50f)
        assertTrue(chip.y < 1500f - 300f)
        assertTrue(chip.x in 200f..880f)
        val high = QuickMenuGeometry.chipCentre(BubbleSide.LEFT, cx = 74f, cy = 100f, radius = 300f, width = 1080, height = 2424, halfWidth = 200f, halfHeight = 50f)
        assertTrue(high.y >= 50f, "no room above: it goes below the fan, y ${high.y}")
        assertTrue(high.y > 100f + 300f)
    }

    @Test
    fun `the rubber band follows the finger but never further than its limit`() {
        val near = QuickMenuGeometry.rubberBand(-20f, 0f, limit = 60f)
        assertTrue(near.x < 0f && near.x > -20f)
        val far = QuickMenuGeometry.rubberBand(-900f, -900f, limit = 60f)
        assertTrue(hypot(far.x, far.y) <= 60f + 0.01f)
        assertTrue(far.x < 0f && far.y < 0f)
        assertEquals(0f, QuickMenuGeometry.rubberBand(0f, 0f, limit = 60f).x)
    }
}
