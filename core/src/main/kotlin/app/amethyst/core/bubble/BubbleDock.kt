package app.amethyst.core.bubble

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

/** The screen edge the bubble lives on. */
@Serializable
enum class BubbleSide { LEFT, RIGHT }

/**
 * What the bubble keeps clear of on each side, in pixels: the status bar, the navigation or
 * gesture bar and the display cutout - whichever reaches further in.
 */
data class SafeArea(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

/** The whole display in screen pixels, and its [safe] area. */
data class Screen(val width: Int, val height: Int, val safe: SafeArea = SafeArea())

/** Where a released bubble comes to rest: its [side] and its top-left corner. */
data class Dock(val side: BubbleSide, val x: Int, val y: Int)

/**
 * The bubble lives on the left or right edge, never in the middle of an app's content. A
 * release goes to the nearer edge, a sideways fling to the edge it was thrown at; along the
 * edge it stays where it was let go, inside the safe area. Pure, so every case is a test.
 */
object BubbleDock {

    /**
     * A sideways fling at least this fast (px/s) picks the side it points to, wherever the
     * finger lifts - Android's own bubbles change sides at the same speed.
     */
    const val FLING_PX_PER_S = 750f

    /**
     * A fling only counts as sideways while its sideways speed is at least this share of its
     * vertical speed: a fast drag along the edge that drifts inwards stays on its side.
     */
    const val SIDEWAYS_SHARE = 0.5f

    fun side(centerX: Float, velocityX: Float, velocityY: Float, screenWidth: Int): BubbleSide {
        val sideways = abs(velocityX) >= FLING_PX_PER_S && abs(velocityX) >= abs(velocityY) * SIDEWAYS_SHARE
        return when {
            sideways && velocityX < 0 -> BubbleSide.LEFT
            sideways -> BubbleSide.RIGHT
            centerX < screenWidth / 2f -> BubbleSide.LEFT
            // The exact middle goes right, where the bubble starts.
            else -> BubbleSide.RIGHT
        }
    }

    /** The left edge of a bubble of [size] docked on [side]. */
    fun x(side: BubbleSide, size: Int, screen: Screen): Int = when (side) {
        BubbleSide.LEFT -> screen.safe.left
        BubbleSide.RIGHT -> screen.width - screen.safe.right - size
    }

    /** The highest and lowest top edge inside the safe area; the lowest is never above the highest. */
    fun yRange(size: Int, screen: Screen): IntRange {
        val top = screen.safe.top
        val bottom = screen.height - screen.safe.bottom - size
        return top..maxOf(top, bottom)
    }

    fun clampY(y: Int, size: Int, screen: Screen): Int = y.coerceIn(yRange(size, screen))

    /** Where a bubble let go with its top-left corner at ([left], [top]) and this velocity docks. */
    fun release(left: Int, top: Int, velocityX: Float, velocityY: Float, size: Int, screen: Screen): Dock {
        val side = side(left + size / 2f, velocityX, velocityY, screen.width)
        return Dock(side, x(side, size, screen), clampY(top, size, screen))
    }

    /**
     * The top edge for a height kept as a share of the safe area (0 = top, 1 = bottom) - a
     * share, so a rotation keeps the bubble at the same place along the edge.
     */
    fun yOf(fraction: Float, size: Int, screen: Screen): Int {
        val range = yRange(size, screen)
        return range.first + ((range.last - range.first) * fraction.coerceIn(0f, 1f)).roundToInt()
    }

    fun fractionOf(y: Int, size: Int, screen: Screen): Float {
        val range = yRange(size, screen)
        val span = range.last - range.first
        if (span <= 0) return 0f
        return ((y - range.first).toFloat() / span).coerceIn(0f, 1f)
    }
}
