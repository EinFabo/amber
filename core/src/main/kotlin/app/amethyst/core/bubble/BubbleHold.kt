package app.amethyst.core.bubble

/**
 * Keeps the bubble where it is while that is allowed. [BubblePlacement] says where the bubble
 * belongs from scratch; asked again on every change it made the bubble hop - up a line each
 * time a chat's input grew by one, up and down with every pixel the keyboard's edge moved.
 * While the same keyboard stays up for the same field, the bubble only moves once its place
 * has become wrong, and then far enough that the next line does not move it again. It does
 * not come back down by itself: its own height returns when the keyboard opens or closes,
 * another field starts or it is dragged - then [current] is null.
 */
object BubbleHold {

    /**
     * The bubble's top edge: [current] while a bubble there is [allowed]; else where
     * [BubblePlacement] puts it - with [headroom] kept free above every box in the way when it
     * has to give way, room for the lines a field may still grow.
     */
    fun y(
        current: Int?,
        restY: Int,
        x: Int,
        size: Int,
        screen: Screen,
        gap: Int,
        keyboardTop: Int?,
        aboveKeyboard: Int,
        avoid: List<Box> = emptyList(),
        headroom: Int = 0,
    ): Int {
        if (current == null) return BubblePlacement.y(restY, x, size, screen, gap, keyboardTop, aboveKeyboard, avoid)
        if (allowed(current, x, size, screen, gap, keyboardTop, avoid)) return current
        val roomy = avoid.map { it.copy(top = (it.top - headroom).coerceAtLeast(0)) }
        return BubblePlacement.y(restY, x, size, screen, gap, keyboardTop, aboveKeyboard, roomy)
    }

    /**
     * Whether a bubble with its top edge at [y] is in nobody's way: inside the safe area, at
     * least [gap] above the keys, and clear of every box [BubblePlacement] would step around.
     */
    fun allowed(y: Int, x: Int, size: Int, screen: Screen, gap: Int, keyboardTop: Int?, avoid: List<Box>): Boolean {
        val top = screen.safe.top
        val bottom = screen.height - screen.safe.bottom
        val floor = if (keyboardTop != null) minOf(keyboardTop - gap, bottom) else bottom
        if (y < top || y + size > floor) return false
        val bubble = Box(x, y - gap, x + size, y + size + gap)
        return avoid.filter { it.height < (floor - gap) * BubblePlacement.MAX_AVOIDED_SHARE }.none { it.overlaps(bubble) }
    }
}
