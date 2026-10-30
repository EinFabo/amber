package app.amethyst.core

import app.amethyst.core.bubble.Box
import app.amethyst.core.commands.Direction
import app.amethyst.core.commands.GesturePath
import app.amethyst.core.commands.ScrollAction
import app.amethyst.core.commands.ScrollPick
import app.amethyst.core.commands.ScrollTarget
import app.amethyst.core.commands.Scrollable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScrollTargetTest {

    private fun node(id: Int, area: Long, vararg actions: ScrollAction, vertical: Boolean = true, active: Boolean = true) =
        Scrollable(id, area, active, vertical, actions.toSet())

    @Test
    fun `scrolling down picks the largest list that can`() {
        val nodes = listOf(node(1, 100, ScrollAction.DOWN), node(2, 900, ScrollAction.DOWN, ScrollAction.UP), node(3, 2_000, ScrollAction.UP))
        assertEquals(ScrollPick.Node(2, ScrollAction.DOWN), ScrollTarget.pick(nodes, Direction.DOWN))
    }

    @Test
    fun `forward and backward follow the list's axis`() {
        val list = node(1, 500, ScrollAction.FORWARD)
        val pager = node(2, 900, ScrollAction.FORWARD, vertical = false)
        assertEquals(ScrollPick.Node(1, ScrollAction.FORWARD), ScrollTarget.pick(listOf(list, pager), Direction.DOWN))
        assertEquals(ScrollPick.Node(2, ScrollAction.FORWARD), ScrollTarget.pick(listOf(list, pager), Direction.RIGHT))
        assertEquals(ScrollPick.Node(2, ScrollAction.BACKWARD), ScrollTarget.pick(listOf(node(2, 900, ScrollAction.BACKWARD, vertical = false)), Direction.LEFT))
    }

    @Test
    fun `the directional action beats forward on the same node`() {
        assertEquals(ScrollPick.Node(1, ScrollAction.DOWN), ScrollTarget.pick(listOf(node(1, 500, ScrollAction.FORWARD, ScrollAction.DOWN)), Direction.DOWN))
    }

    @Test
    fun `the window in use beats a larger one behind it`() {
        val behind = node(1, 5_000, ScrollAction.DOWN, active = false)
        val front = node(2, 800, ScrollAction.DOWN)
        assertEquals(ScrollPick.Node(2, ScrollAction.DOWN), ScrollTarget.pick(listOf(behind, front), Direction.DOWN))
    }

    @Test
    fun `a list at its end leaves it to a gesture - the page may still scroll`() {
        assertEquals(ScrollPick.None, ScrollTarget.pick(listOf(node(1, 900, ScrollAction.UP, ScrollAction.BACKWARD)), Direction.DOWN))
    }

    @Test
    fun `nothing scrolls on that axis`() {
        assertEquals(ScrollPick.None, ScrollTarget.pick(emptyList(), Direction.DOWN))
        assertEquals(ScrollPick.None, ScrollTarget.pick(listOf(node(1, 900, ScrollAction.DOWN)), Direction.LEFT))
    }

    @Test
    fun `the axis comes from the actions, the class or the grid`() {
        assertTrue(ScrollTarget.isVertical("androidx.recyclerview.widget.RecyclerView", rows = 20, columns = 1, actions = setOf(ScrollAction.FORWARD)))
        assertFalse(ScrollTarget.isVertical("android.widget.HorizontalScrollView", 0, 0, setOf(ScrollAction.FORWARD)))
        assertFalse(ScrollTarget.isVertical("androidx.viewpager.widget.ViewPager", 0, 0, setOf(ScrollAction.FORWARD)))
        assertFalse(ScrollTarget.isVertical("androidx.recyclerview.widget.RecyclerView", rows = 1, columns = 8, actions = setOf(ScrollAction.FORWARD)))
        assertFalse(ScrollTarget.isVertical("android.view.View", 0, 0, setOf(ScrollAction.LEFT, ScrollAction.FORWARD)))
        assertTrue(ScrollTarget.isVertical("android.view.View", 0, 0, setOf(ScrollAction.DOWN)))
    }
}

class GesturePathTest {

    private val w = 1080
    private val h = 2400

    @Test
    fun `a swipe left moves the finger from right to left, away from the edges`() {
        val stroke = GesturePath.swipe(Direction.LEFT, w, h, avoid = null)
        assertTrue(stroke.fromX > stroke.toX)
        assertEquals(stroke.fromY, stroke.toY)
        listOf(stroke.fromX, stroke.toX).forEach { assertTrue(it in w * 0.2f..w * 0.8f, "x=$it") }
    }

    @Test
    fun `scrolling down moves the finger up`() {
        val stroke = GesturePath.scroll(Direction.DOWN, w, h, avoid = null)
        assertTrue(stroke.fromY > stroke.toY)
        assertEquals(stroke.fromX, stroke.toX)
        listOf(stroke.fromY, stroke.toY).forEach { assertTrue(it in h * 0.2f..h * 0.8f, "y=$it") }
    }

    @Test
    fun `a stroke never runs over the bubble`() {
        val bubble = Box(480, 1140, 600, 1260)
        val horizontal = GesturePath.swipe(Direction.RIGHT, w, h, avoid = bubble)
        assertFalse(horizontal.fromY.toInt() in bubble.top - 8..bubble.bottom + 8, "y=${horizontal.fromY}")
        val vertical = GesturePath.scroll(Direction.UP, w, h, avoid = Box(480, 900, 600, 1500))
        assertFalse(vertical.fromX.toInt() in 480 - 8..600 + 8, "x=${vertical.fromX}")
    }

    @Test
    fun `a bubble off the path changes nothing`() {
        val corner = Box(950, 2200, 1070, 2320)
        assertEquals(GesturePath.swipe(Direction.LEFT, w, h, avoid = null), GesturePath.swipe(Direction.LEFT, w, h, avoid = corner))
    }
}
