package app.amethyst.core.commands

import app.amethyst.core.bubble.Box

/** The scroll actions a node offers (`AccessibilityNodeInfo.AccessibilityAction`). */
enum class ScrollAction { UP, DOWN, LEFT, RIGHT, FORWARD, BACKWARD }

/** A scrollable node as the executor found it: no text, only where it is and what it offers. */
data class Scrollable(val id: Int, val area: Long, val activeWindow: Boolean, val vertical: Boolean, val actions: Set<ScrollAction>)

sealed interface ScrollPick {
    data class Node(val id: Int, val action: ScrollAction) : ScrollPick

    /**
     * Nothing offers scrolling that way - a gesture may still move something. Also when a list
     * is at its end: it may be a small one inside a page that scrolls without saying so.
     */
    data object None : ScrollPick
}

/**
 * Which node a "scroll down" scrolls: one that offers it - a list offering only "forward"
 * counts along its own axis - in the window in use before others, the largest before smaller.
 */
object ScrollTarget {

    fun pick(nodes: List<Scrollable>, direction: Direction): ScrollPick {
        val vertical = direction == Direction.UP || direction == Direction.DOWN
        val wanted = direction.action()
        val generic = if (direction == Direction.DOWN || direction == Direction.RIGHT) ScrollAction.FORWARD else ScrollAction.BACKWARD
        val best = nodes
            .mapNotNull { node ->
                when {
                    wanted in node.actions -> node to wanted
                    generic in node.actions && node.vertical == vertical -> node to generic
                    else -> null
                }
            }
            .sortedWith(compareByDescending<Pair<Scrollable, ScrollAction>> { it.first.activeWindow }.thenByDescending { it.first.area })
            .firstOrNull()
        return if (best != null) ScrollPick.Node(best.first.id, best.second) else ScrollPick.None
    }

    /**
     * A node's axis: from its directional actions where they tell, else from its class (a
     * horizontal scroll view, a pager) or a grid of one row.
     */
    fun isVertical(className: String?, rows: Int, columns: Int, actions: Set<ScrollAction>): Boolean {
        val up = ScrollAction.UP in actions || ScrollAction.DOWN in actions
        val side = ScrollAction.LEFT in actions || ScrollAction.RIGHT in actions
        if (up != side) return up
        val name = className.orEmpty()
        if ("Horizontal" in name || "Pager" in name) return false
        return !(rows == 1 && columns > 1)
    }

    private fun Direction.action(): ScrollAction = when (this) {
        Direction.UP -> ScrollAction.UP
        Direction.DOWN -> ScrollAction.DOWN
        Direction.LEFT -> ScrollAction.LEFT
        Direction.RIGHT -> ScrollAction.RIGHT
    }
}

/** One finger stroke in screen pixels. */
data class Stroke(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float, val durationMs: Long)

/**
 * Where a dispatched stroke goes: across the middle of the screen, never from an edge (that is
 * the system's back gesture), and never over the bubble - a stroke starting on it would move
 * the bubble instead of the app.
 */
object GesturePath {

    private const val SWIPE_MS = 250L
    private const val SCROLL_MS = 350L
    private const val CLEARANCE = 8
    private val LANES = listOf(0.5f, 0.4f, 0.6f, 0.3f, 0.7f)

    /** The finger moves towards [direction]: "swipe left" pages on in most apps. */
    fun swipe(direction: Direction, width: Int, height: Int, avoid: Box?): Stroke =
        stroke(direction, width, height, avoid, SWIPE_MS)

    /** The content moves to show what lies towards [direction], so the finger goes the other way. */
    fun scroll(direction: Direction, width: Int, height: Int, avoid: Box?): Stroke {
        val finger = when (direction) {
            Direction.UP -> Direction.DOWN
            Direction.DOWN -> Direction.UP
            Direction.LEFT -> Direction.RIGHT
            Direction.RIGHT -> Direction.LEFT
        }
        return stroke(finger, width, height, avoid, SCROLL_MS)
    }

    private fun stroke(finger: Direction, width: Int, height: Int, avoid: Box?, durationMs: Long): Stroke {
        val horizontal = finger == Direction.LEFT || finger == Direction.RIGHT
        val length = if (horizontal) width else height
        val (from, to) = when (finger) {
            Direction.LEFT, Direction.UP -> 0.75f * length to 0.25f * length
            Direction.RIGHT, Direction.DOWN -> 0.25f * length to 0.75f * length
        }
        val across = if (horizontal) height else width
        val blocked: (Float) -> Boolean = { lane ->
            avoid != null && run {
                val (alongLo, alongHi) = if (horizontal) avoid.left to avoid.right else avoid.top to avoid.bottom
                val (acrossLo, acrossHi) = if (horizontal) avoid.top to avoid.bottom else avoid.left to avoid.right
                alongLo < maxOf(from, to) && minOf(from, to) < alongHi &&
                    lane.toInt() in acrossLo - CLEARANCE..acrossHi + CLEARANCE
            }
        }
        val lane = LANES.map { it * across }.firstOrNull { !blocked(it) } ?: (across / 2f)
        return if (horizontal) Stroke(from, lane, to, lane, durationMs) else Stroke(lane, from, lane, to, durationMs)
    }
}
