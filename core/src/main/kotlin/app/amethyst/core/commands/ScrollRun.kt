package app.amethyst.core.commands

import app.amethyst.core.bubble.Box

/** What one step of a repeated scroll or page does. */
sealed interface ScrollMove {
    /** The node's own scroll or page action. */
    data class Node(val id: Int, val action: ScrollAction) : ScrollMove

    /** Nothing on that axis says it scrolls: a finger stroke is the only way. */
    data object Gesture : ScrollMove

    /** What scrolls that way says it cannot go further: the end of the run. */
    data object End : ScrollMove
}

/**
 * Where a scrolled node and what lies inside it are: its bounds, then each node below it with
 * its bounds and, in a list, its row and column - so a step that moved nothing shows. Never
 * what anything shows, and nothing outside that node. A list nested in it (a carousel, a
 * banner) counts by its place only, so one that advances by itself is not taken for the outer
 * list moving.
 */
data class ScrollSnapshot(val bounds: Box, val items: List<Item>) {

    /** A node inside the scrolled one; [row] and [column] -1 outside a list. */
    data class Item(val bounds: Box, val row: Int = -1, val column: Int = -1)

    companion object {
        /** Enough for every row on a screen and what is in them; cheap on a huge one. */
        const val MAX_ITEMS = 500

        /** Breadth-first below [node], at most [limit] nodes, not into nested scrolling nodes. */
        fun <N> of(
            node: N,
            limit: Int = MAX_ITEMS,
            bounds: (N) -> Box,
            place: (N) -> Pair<Int, Int>?,
            scrolls: (N) -> Boolean,
            children: (N) -> List<N>,
        ): ScrollSnapshot {
            val items = mutableListOf<Item>()
            val queue = ArrayDeque(children(node))
            while (queue.isNotEmpty() && items.size < limit) {
                val inside = queue.removeFirst()
                val (row, column) = place(inside) ?: (-1 to -1)
                items += Item(bounds(inside), row, column)
                if (!scrolls(inside)) queue.addAll(children(inside))
            }
            return ScrollSnapshot(bounds(node), items)
        }
    }
}

/**
 * Repeated scrolling - "zehnmal runter", "ganz nach oben" - step by step. The node that scrolls
 * says where its end is: once it offers the way no more, or refuses it, or nothing in it moved,
 * the run ends. A finger stroke is never the fallback there - at the top of a list it would pull
 * it to refresh, a reload nobody asked for.
 */
object ScrollRun {

    /**
     * The next step. A node that offers the way goes first. Without one it is the end once the
     * run has scrolled a node, or when something on that axis can scroll but not that way (a
     * list at its top). Only where nothing on that axis says it scrolls is a stroke the way.
     */
    fun move(nodes: List<Scrollable>, direction: Direction, pages: Boolean, scrolledNode: Boolean): ScrollMove {
        val pick = if (pages) ScrollTarget.pickPage(nodes, direction) else ScrollTarget.pick(nodes, direction)
        if (pick is ScrollPick.Node) return ScrollMove.Node(pick.id, pick.action)
        val vertical = direction == Direction.UP || direction == Direction.DOWN
        return if (scrolledNode || nodes.any { it.vertical == vertical }) ScrollMove.End else ScrollMove.Gesture
    }

    /**
     * How a node's step went: done when Android took the action and the node, looked at again
     * after the pause, is not where it was. A node that refused or is gone ends the run.
     */
    fun result(took: Boolean, before: ScrollSnapshot, after: ScrollSnapshot?): Step =
        if (took && after != null && after != before) Step.Done else Step.Unchanged
}
