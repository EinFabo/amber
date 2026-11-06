package app.amethyst.core.bubble

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The quick menu's fan: [SLOTS] sectors of 45 degrees over the half of the circle that points
 * into the screen from the bubble's edge - top to bottom: up, up-inward, down-inward, down.
 * Measured from the edge, not the screen, so "inward" is left on the right edge and right on
 * the left edge: the same thumb move picks the same slot on both sides. Only the direction
 * counts, never the distance - a slot reaches to the screen's edge, and overshooting never
 * turns into something else.
 */
object QuickMenuGeometry {

    const val SLOTS = 4
    const val SECTOR_DEG = 45.0

    /**
     * How far past straight up or down (toward the edge) a direction still picks the outer
     * slot: a thumb going up along the edge drifts outward.
     */
    const val OUTWARD_TOLERANCE_DEG = 22.5

    data class Point(val x: Float, val y: Float)

    /** Slot [slot]'s middle, in degrees from straight inward; negative is up. */
    fun slotAngle(slot: Int): Double = (slot - (SLOTS - 1) / 2.0) * SECTOR_DEG

    /**
     * The slot a finger [dx], [dy] away from where it pressed picks, or null: inside the
     * [deadZone] radius, or pointing out of the screen. A direction on a boundary between two
     * slots always picks the lower one.
     */
    fun slotAt(side: BubbleSide, dx: Float, dy: Float, deadZone: Float): Int? {
        if (hypot(dx, dy) < deadZone) return null
        val inward = if (side == BubbleSide.RIGHT) -dx else dx
        val angle = Math.toDegrees(atan2(dy.toDouble(), inward.toDouble()))
        val half = SLOTS * SECTOR_DEG / 2
        if (angle < -half - OUTWARD_TOLERANCE_DEG || angle > half + OUTWARD_TOLERANCE_DEG) return null
        return floor((angle + half) / SECTOR_DEG).toInt().coerceIn(0, SLOTS - 1)
    }

    /**
     * Where slot [slot]'s label goes for a bubble centred at [cx], [cy]: [radius] out along the
     * slot's direction, moved just enough to keep a label of [halfWidth] x [halfHeight] on the
     * screen. Moving a label never changes what a direction picks.
     */
    fun labelCentre(
        side: BubbleSide,
        slot: Int,
        cx: Float,
        cy: Float,
        radius: Float,
        width: Int,
        height: Int,
        halfWidth: Float,
        halfHeight: Float,
    ): Point {
        val angle = Math.toRadians(slotAngle(slot))
        val inward = if (side == BubbleSide.RIGHT) -1f else 1f
        val x = cx + inward * radius * cos(angle).toFloat()
        val y = cy + radius * sin(angle).toFloat()
        return Point(clamp(x, halfWidth, width - halfWidth), clamp(y, halfHeight, height - halfHeight))
    }

    /**
     * Where the chip with the lit slot's name goes: above the fan, where the thumb never
     * covers it - below the fan when there is no room above.
     */
    fun chipCentre(
        side: BubbleSide,
        cx: Float,
        cy: Float,
        radius: Float,
        width: Int,
        height: Int,
        halfWidth: Float,
        halfHeight: Float,
    ): Point {
        val inward = if (side == BubbleSide.RIGHT) -1f else 1f
        val x = cx + inward * radius * CHIP_INWARD
        val above = cy - radius - halfHeight * 2
        val y = if (above - halfHeight >= 0f) above else cy + radius + halfHeight * 2
        return Point(clamp(x, halfWidth, width - halfWidth), clamp(y, halfHeight, height - halfHeight))
    }

    /**
     * How far the bubble leans toward the finger while the menu is open: along with it at
     * first, then ever less, never past [limit] - it springs back when the finger lifts.
     */
    fun rubberBand(dx: Float, dy: Float, limit: Float): Point {
        val distance = hypot(dx, dy)
        if (distance == 0f || limit <= 0f) return Point(0f, 0f)
        val reach = limit * (1 - exp(-distance / limit))
        return Point(dx / distance * reach, dy / distance * reach)
    }

    private fun clamp(value: Float, low: Float, high: Float): Float =
        if (high < low) (low + high) / 2 else value.coerceIn(low, high)

    /** The chip's sideways offset into the screen, as a share of the fan's radius. */
    private const val CHIP_INWARD = 0.6f
}
