package app.amethyst.core.bubble

/** A rectangle in screen pixels; right and bottom exclusive, like android.graphics.Rect. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun overlaps(other: Box): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

/**
 * Where the bubble goes along its edge, given its two remembered heights and what is in the
 * way: a docked keyboard (only its top edge matters), a floating keyboard and the field being
 * typed in (both as rectangles to keep clear of). Pure, so every case is a test.
 */
object BubblePlacement {

    /**
     * The bubble's top edge. Without a docked keyboard it rests at [restY]; with one it sits
     * [aboveKeyboard] pixels above the keys - a height of its own, so it clears the app's input
     * bar and its send button instead of landing on them. Never outside [screen]'s safe area,
     * never closer than [gap] to the keys. Then clear of every [avoid] box - to the nearest
     * place right above or below one of them that is free of all. A box too tall to step
     * around (a full-screen editor) is left alone rather than sending the bubble to the top of
     * the screen, and with no free place at all the bubble stays where the keyboard allows.
     */
    fun y(
        restY: Int,
        x: Int,
        size: Int,
        screen: Screen,
        gap: Int,
        keyboardTop: Int?,
        aboveKeyboard: Int,
        avoid: List<Box> = emptyList(),
    ): Int {
        val top = screen.safe.top
        val bottom = screen.height - screen.safe.bottom
        val floor = if (keyboardTop != null) minOf(keyboardTop - gap, bottom) else bottom
        val wanted = if (keyboardTop != null) keyboardTop - aboveKeyboard - size else restY
        val start = minOf(wanted, floor - size).coerceAtLeast(top)
        val obstacles = avoid.filter { it.height < (floor - gap) * MAX_AVOIDED_SHARE }
        fun free(y: Int): Boolean {
            val bubble = Box(x, y - gap, x + size, y + size + gap)
            return obstacles.none { it.overlaps(bubble) }
        }
        if (free(start)) return start
        return obstacles
            .flatMap { listOf(it.top - gap - size, it.bottom + gap) }
            .filter { it >= top && it + size <= floor && free(it) }
            .minByOrNull { kotlin.math.abs(it - start) }
            ?: start
    }

    /**
     * The height above the keyboard of a bubble dropped with its top edge at [y] while the
     * keyboard's top is at [keyboardTop]: the gap between the bubble's bottom and the keys.
     */
    fun aboveKeyboard(y: Int, size: Int, keyboardTop: Int): Int = (keyboardTop - y - size).coerceAtLeast(0)

    /** Boxes taller than this share of the free height are not stepped around. */
    const val MAX_AVOIDED_SHARE = 0.45f
}
