package app.amethyst.core.bubble

/** A rectangle in screen pixels; right and bottom exclusive, like android.graphics.Rect. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun overlaps(other: Box): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

/**
 * Where the bubble sits along its edge. It has one place - where it was dropped - and stays
 * there: it does not follow the keyboard and does not step around the field being typed in.
 * Giving way to a growing chat input made it hop while typing; a bubble that covers a corner
 * of the text and can be dragged away is the calmer one. The one thing it never does is lie
 * on the keys. Pure, so every case is a test.
 */
object BubblePlacement {

    /**
     * The bubble's top edge: [restY], its own place - unless that is outside [screen]'s safe
     * area, closer than [gap] to the keys of a docked keyboard ([keyboardTop]) or on a
     * floating one ([keyboards], rectangles). Then the nearest place that is none of these:
     * just above the docked keys, or right above or below a floating keyboard. With no free
     * place at all it stays where the docked keyboard allows.
     */
    fun y(
        restY: Int,
        x: Int,
        size: Int,
        screen: Screen,
        gap: Int,
        keyboardTop: Int?,
        keyboards: List<Box> = emptyList(),
    ): Int {
        val top = screen.safe.top
        val bottom = screen.height - screen.safe.bottom
        val floor = if (keyboardTop != null) minOf(keyboardTop - gap, bottom) else bottom
        val start = minOf(restY, floor - size).coerceAtLeast(top)
        fun free(y: Int): Boolean {
            val bubble = Box(x, y - gap, x + size, y + size + gap)
            return keyboards.none { it.overlaps(bubble) }
        }
        if (free(start)) return start
        return keyboards
            .flatMap { listOf(it.top - gap - size, it.bottom + gap) }
            .filter { it >= top && it + size <= floor && free(it) }
            .minByOrNull { kotlin.math.abs(it - start) }
            ?: start
    }

    /**
     * The place a bubble let go with its top edge at [y] has from now on: [y], or the nearest
     * place off the keys when it was let go on them. While a keyboard leaves no room at all
     * (landscape) the bubble is only pushed aside for now - where it was let go stays its
     * place, or one drag to the other edge there would send it to the top in portrait too.
     */
    fun dropped(y: Int, x: Int, size: Int, screen: Screen, gap: Int, keyboardTop: Int?, keyboards: List<Box> = emptyList()): Int {
        val room = keyboardTop == null || keyboardTop - gap - size >= screen.safe.top
        return if (room) y(y, x, size, screen, gap, keyboardTop, keyboards) else y
    }
}
