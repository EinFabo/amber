package app.amethyst.core

import app.amethyst.core.bubble.BubbleDock
import app.amethyst.core.bubble.BubbleSide
import app.amethyst.core.bubble.SafeArea
import app.amethyst.core.bubble.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BubbleDockTest {

    // Pixel 9a: 1080 x 2424, status bar 145 px, gesture bar 63 px, a 56 dp bubble (147 px).
    private val portrait = Screen(1080, 2424, SafeArea(top = 145, bottom = 63))

    // The same phone turned left: the cutout on the left, the gesture bar at the bottom.
    private val landscape = Screen(2424, 1080, SafeArea(left = 145, top = 0, bottom = 63))
    private val size = 147

    @Test
    fun `a release without a fling goes to the nearer edge`() {
        assertEquals(BubbleSide.LEFT, BubbleDock.side(centerX = 300f, velocityX = 0f, velocityY = 0f, screenWidth = 1080))
        assertEquals(BubbleSide.RIGHT, BubbleDock.side(centerX = 800f, velocityX = 0f, velocityY = 0f, screenWidth = 1080))
    }

    @Test
    fun `the middle of the screen goes right, where the bubble starts`() {
        assertEquals(BubbleSide.RIGHT, BubbleDock.side(centerX = 540f, velocityX = 0f, velocityY = 0f, screenWidth = 1080))
    }

    @Test
    fun `a sideways fling over the threshold throws it to the other side`() {
        assertEquals(BubbleSide.LEFT, BubbleDock.side(centerX = 900f, velocityX = -800f, velocityY = 0f, screenWidth = 1080))
        assertEquals(BubbleSide.RIGHT, BubbleDock.side(centerX = 100f, velocityX = 800f, velocityY = 0f, screenWidth = 1080))
    }

    @Test
    fun `a fling under the threshold does not`() {
        assertEquals(BubbleSide.RIGHT, BubbleDock.side(centerX = 900f, velocityX = -700f, velocityY = 0f, screenWidth = 1080))
        assertEquals(BubbleSide.LEFT, BubbleDock.side(centerX = 100f, velocityX = 700f, velocityY = 0f, screenWidth = 1080))
    }

    @Test
    fun `a fast drag along the edge is not a sideways fling`() {
        // Mostly downwards, drifting left: it stays on its side.
        assertEquals(BubbleSide.RIGHT, BubbleDock.side(centerX = 900f, velocityX = -900f, velocityY = 3000f, screenWidth = 1080))
    }

    @Test
    fun `the edge x keeps clear of a cutout or bar on that side`() {
        assertEquals(0, BubbleDock.x(BubbleSide.LEFT, size, portrait))
        assertEquals(1080 - size, BubbleDock.x(BubbleSide.RIGHT, size, portrait))
        assertEquals(145, BubbleDock.x(BubbleSide.LEFT, size, landscape))
        assertEquals(2424 - size, BubbleDock.x(BubbleSide.RIGHT, size, landscape))
    }

    @Test
    fun `y stays inside the status bar and the gesture bar`() {
        assertEquals(145, BubbleDock.clampY(20, size, portrait))
        assertEquals(2424 - 63 - size, BubbleDock.clampY(2400, size, portrait))
        assertEquals(1200, BubbleDock.clampY(1200, size, portrait))
    }

    @Test
    fun `a screen too short for the bubble puts it at the top of the safe area`() {
        val tiny = Screen(400, 200, SafeArea(top = 100, bottom = 50))
        assertEquals(100, BubbleDock.clampY(150, size, tiny))
    }

    @Test
    fun `release docks at the edge with y clamped`() {
        val dock = BubbleDock.release(left = 400, top = 30, velocityX = 0f, velocityY = 0f, size = size, screen = portrait)
        assertEquals(BubbleSide.LEFT, dock.side)
        assertEquals(0, dock.x)
        assertEquals(145, dock.y)
    }

    @Test
    fun `release with a fling to the right lands on the right edge`() {
        val dock = BubbleDock.release(left = 100, top = 1000, velocityX = 2000f, velocityY = 100f, size = size, screen = portrait)
        assertEquals(BubbleSide.RIGHT, dock.side)
        assertEquals(1080 - size, dock.x)
        assertEquals(1000, dock.y)
    }

    @Test
    fun `the height along the edge round-trips as a fraction`() {
        val y = BubbleDock.yOf(0.72f, size, portrait)
        assertEquals(0.72f, BubbleDock.fractionOf(y, size, portrait), 0.001f)
        assertEquals(145, BubbleDock.yOf(0f, size, portrait))
        assertEquals(2424 - 63 - size, BubbleDock.yOf(1f, size, portrait))
    }

    @Test
    fun `rotation keeps the side and the share of the height`() {
        val y = BubbleDock.yOf(0.5f, size, portrait)
        val fraction = BubbleDock.fractionOf(y, size, portrait)
        val turned = BubbleDock.yOf(fraction, size, landscape)
        val middle = (landscape.safe.top + (landscape.height - landscape.safe.bottom - size)) / 2
        assertTrue(kotlin.math.abs(turned - middle) <= 1, "turned $turned, middle $middle")
        assertEquals(2424 - size, BubbleDock.x(BubbleSide.RIGHT, size, landscape))
    }

    @Test
    fun `fractions out of range are clamped`() {
        assertEquals(BubbleDock.yOf(1f, size, portrait), BubbleDock.yOf(3f, size, portrait))
        assertEquals(BubbleDock.yOf(0f, size, portrait), BubbleDock.yOf(-1f, size, portrait))
    }
}
