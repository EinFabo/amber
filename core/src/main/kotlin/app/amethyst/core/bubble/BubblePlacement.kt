package app.amethyst.core.bubble

/** A rectangle in screen pixels; right and bottom exclusive, like android.graphics.Rect. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun overlaps(other: Box): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

/**
 * Where the bubble goes, given where the user left it and what is in the way: a docked
 * keyboard (only its top edge matters), a floating keyboard and the field being typed in
 * (both as rectangles to keep clear of). Pure, so every case is a test.
 */
object BubblePlacement {

    /**
     * The bubble's top edge. It rests at [restY] and only moves when it has to: above a
     * docked keyboard, then clear of every [avoid] box - to the nearest place right above or
     * below one of them that is free of all. A box too tall to step around (a full-screen
     * editor) is left alone rather than sending the bubble to the top of the screen, and
     * with no free place at all the bubble stays where the keyboard allows.
     */
    fun y(
        restY: Int,
        x: Int,
        size: Int,
        screenHeight: Int,
        gap: Int,
        keyboardTop: Int?,
        avoid: List<Box> = emptyList(),
    ): Int {
        val floor = (keyboardTop ?: screenHeight) - gap
        val start = minOf(restY, floor - size).coerceAtLeast(0)
        val obstacles = avoid.filter { it.height < (floor - gap) * MAX_AVOIDED_SHARE }
        fun free(y: Int): Boolean {
            val bubble = Box(x, y - gap, x + size, y + size + gap)
            return obstacles.none { it.overlaps(bubble) }
        }
        if (free(start)) return start
        return obstacles
            .flatMap { listOf(it.top - gap - size, it.bottom + gap) }
            .filter { it >= 0 && it + size <= floor && free(it) }
            .minByOrNull { kotlin.math.abs(it - start) }
            ?: start
    }

    /** Boxes taller than this share of the free height are not stepped around. */
    const val MAX_AVOIDED_SHARE = 0.45f
}
