package app.amethyst.core

import app.amethyst.core.bubble.Box
import app.amethyst.core.commands.Direction
import app.amethyst.core.commands.ScrollAction
import app.amethyst.core.commands.ScrollMove
import app.amethyst.core.commands.ScrollRun
import app.amethyst.core.commands.ScrollSnapshot
import app.amethyst.core.commands.Scrollable
import app.amethyst.core.commands.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScrollRunTest {

    private fun node(id: Int, vararg actions: ScrollAction, vertical: Boolean = true) =
        Scrollable(id, area = 900, activeWindow = true, vertical = vertical, actions = actions.toSet())

    // ---- Which way the next step goes ----

    @Test
    fun `a node that offers the way scrolls`() {
        assertEquals(
            ScrollMove.Node(1, ScrollAction.UP),
            ScrollRun.move(listOf(node(1, ScrollAction.UP, ScrollAction.DOWN)), Direction.UP, pages = false, scrolledNode = false),
        )
        assertEquals(
            ScrollMove.Node(2, ScrollAction.PAGE_DOWN),
            ScrollRun.move(listOf(node(2, ScrollAction.PAGE_DOWN, ScrollAction.DOWN)), Direction.DOWN, pages = true, scrolledNode = false),
        )
    }

    @Test
    fun `a list at its top is the end - no stroke that would pull it to refresh`() {
        // Gmail's inbox at the top offers only "down": "ganz nach oben" must stop, not pull.
        val inboxAtTop = node(1, ScrollAction.DOWN, ScrollAction.FORWARD)
        assertEquals(ScrollMove.End, ScrollRun.move(listOf(inboxAtTop), Direction.UP, pages = false, scrolledNode = false))
    }

    @Test
    fun `once the run has scrolled a node, a node that cannot go on is the end - never a stroke`() {
        assertEquals(ScrollMove.End, ScrollRun.move(listOf(node(1, ScrollAction.DOWN)), Direction.UP, pages = false, scrolledNode = true))
        // Not even when nothing scrollable is found any more.
        assertEquals(ScrollMove.End, ScrollRun.move(emptyList(), Direction.UP, pages = false, scrolledNode = true))
    }

    @Test
    fun `only where nothing on that axis says it scrolls is a stroke the way`() {
        assertEquals(ScrollMove.Gesture, ScrollRun.move(emptyList(), Direction.DOWN, pages = false, scrolledNode = false))
        // A carousel scrolls sideways; it says nothing about the page going down.
        val carousel = node(3, ScrollAction.RIGHT, vertical = false)
        assertEquals(ScrollMove.Gesture, ScrollRun.move(listOf(carousel), Direction.DOWN, pages = false, scrolledNode = false))
    }

    // ---- Whether a step moved the list ----

    private val list = Box(0, 200, 1080, 2200)
    private fun row(top: Int, index: Int) = ScrollSnapshot.Item(Box(0, top, 1080, top + 200), row = index, column = 0)
    private val atRow0 = ScrollSnapshot(list, listOf(row(200, 0), row(400, 1), row(600, 2)))

    @Test
    fun `the same places and the same rows - nothing moved, the end`() {
        assertEquals(Step.Unchanged, ScrollRun.result(took = true, before = atRow0, after = atRow0.copy()))
    }

    @Test
    fun `items at other places, or other rows at the same places, moved`() {
        val shifted = ScrollSnapshot(list, listOf(row(120, 0), row(320, 1), row(520, 2)))
        assertEquals(Step.Done, ScrollRun.result(took = true, before = atRow0, after = shifted))
        // Scrolled by exactly one row: every place is taken again, by the next row.
        val nextRows = ScrollSnapshot(list, listOf(row(200, 1), row(400, 2), row(600, 3)))
        assertEquals(Step.Done, ScrollRun.result(took = true, before = atRow0, after = nextRows))
    }

    @Test
    fun `a node that refuses, or is gone afterwards, is the end - not a reason to stroke`() {
        assertEquals(Step.Unchanged, ScrollRun.result(took = false, before = atRow0, after = null))
        assertEquals(Step.Unchanged, ScrollRun.result(took = true, before = atRow0, after = null))
    }

    // ---- What the snapshot reads ----

    /** A made-up node tree: only places, list positions and whether a node scrolls. */
    private class N(val bounds: Box, val place: Pair<Int, Int>? = null, val scrolls: Boolean = false, val children: List<N> = emptyList())

    private fun snapshot(root: N, limit: Int = ScrollSnapshot.MAX_ITEMS) =
        ScrollSnapshot.of(root, limit, bounds = { it.bounds }, place = { it.place }, scrolls = { it.scrolls }, children = { it.children })

    @Test
    fun `the snapshot holds the scrolled node and what lies inside it, nothing else`() {
        val text = N(Box(10, 300, 500, 340))
        val card = N(Box(0, 250, 1080, 450), place = 4 to 0, children = listOf(text))
        val snap = snapshot(N(list, scrolls = true, children = listOf(card)))
        assertEquals(list, snap.bounds)
        assertEquals(
            listOf(ScrollSnapshot.Item(Box(0, 250, 1080, 450), 4, 0), ScrollSnapshot.Item(Box(10, 300, 500, 340))),
            snap.items,
        )
    }

    @Test
    fun `a list nested in it counts by its place - an advancing banner is no movement`() {
        fun page(bannerShows: Int) = N(
            list,
            scrolls = true,
            children = listOf(
                N(Box(0, 200, 1080, 600), scrolls = true, children = listOf(N(Box(-1080 * bannerShows, 200, 1080 - 1080 * bannerShows, 600)))),
                N(Box(0, 600, 1080, 800), place = 0 to 0),
            ),
        )
        val before = snapshot(page(bannerShows = 0))
        val after = snapshot(page(bannerShows = 1))
        assertEquals(before, after)
        assertFalse(ScrollRun.result(took = true, before = before, after = after) == Step.Done)
    }

    @Test
    fun `a huge subtree is read only up to the limit`() {
        val many = N(list, scrolls = true, children = List(50) { N(Box(0, it, 10, it + 1)) })
        assertEquals(10, snapshot(many, limit = 10).items.size)
        assertTrue(snapshot(many).items.size == 50)
    }
}
