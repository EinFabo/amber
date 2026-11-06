package app.amethyst.core.bubble

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * The quick menu's fan: [SLOTS] small round buttons on a half circle around the bubble,
 * facing into the screen - [SLOT_OFFSETS_DEG] from straight inward, slot 0 at the top on both
 * edges. When a button would leave the usable area (status bar, keyboard, screen edges), the
 * whole fan turns in [TILT_STEP_DEG] steps until all of them fit. Where no turn is enough - a
 * bubble dragged down to the keys, a short landscape screen - the fan first gets smaller, then
 * closes up (less spread, on a circle just wide enough that the buttons never overlap); as a
 * last resort the buttons are pushed inside the area.
 *
 * A finger picks the slot whose direction from the bubble's centre is nearest its own, within
 * [PICK_LIMIT_DEG]; inside the dead zone nothing is picked. Only the direction counts, never
 * the distance - overshooting never turns into something else.
 *
 * Angles are screen angles in degrees: 0 points right, 90 down (y grows downward).
 */
object QuickMenuGeometry {

    const val SLOTS = 4

    /** Each slot's direction from straight inward, top to bottom; negative is up. */
    val SLOT_OFFSETS_DEG = listOf(-67.5, -22.5, 22.5, 67.5)

    /** The fan turns in steps of this much, alternating either way, until every button fits. */
    const val TILT_STEP_DEG = 5.0
    const val MAX_TILT_DEG = 80.0

    /** A finger further than this from every slot's direction picks nothing. */
    const val PICK_LIMIT_DEG = 32.0

    data class Point(val x: Float, val y: Float)

    /** Where the buttons may go, in screen pixels: below the status bar, above the keyboard. */
    data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * An open fan around a bubble centred at [cx], [cy]: each slot's direction ([anglesDeg])
     * and the centre of its button ([orbs]), turned by [tiltDeg], on a circle of [radius].
     */
    data class Fan(
        val cx: Float,
        val cy: Float,
        val anglesDeg: List<Double>,
        val orbs: List<Point>,
        val tiltDeg: Double,
        val radius: Float,
    ) {
        /**
         * The slot a finger at [x], [y] picks: the one whose direction is nearest, if within
         * [PICK_LIMIT_DEG] and outside the [deadZone] radius - null otherwise, and null when
         * that nearest slot is not [selectable] (an empty one). A direction exactly between
         * two slots picks the upper one.
         */
        fun slotAt(x: Float, y: Float, deadZone: Float, selectable: (Int) -> Boolean = { true }): Int? {
            val dx = x - cx
            val dy = y - cy
            if (hypot(dx, dy) < deadZone) return null
            val finger = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
            var best = -1
            var bestDiff = Double.MAX_VALUE
            anglesDeg.forEachIndexed { slot, angle ->
                val diff = abs(difference(finger, angle))
                if (diff < bestDiff - EPSILON) {
                    best = slot
                    bestDiff = diff
                }
            }
            return best.takeIf { it >= 0 && bestDiff <= PICK_LIMIT_DEG && selectable(it) }
        }
    }

    /**
     * The fan for a bubble docked on [side] and centred at [cx], [cy]: buttons of [orbRadius]
     * on a circle of [radius], each kept [margin] plus its own radius inside [area]. Tries no
     * tilt first, then 5, -5, 10, -10 ... up to [MAX_TILT_DEG] - for the full fan, then for a
     * smaller one, then for a closed-up one. When nothing fits, the shape and tilt that leave
     * the least outside, with the buttons pushed in.
     */
    fun fan(
        side: BubbleSide,
        cx: Float,
        cy: Float,
        radius: Float,
        orbRadius: Float,
        margin: Float,
        area: Area,
    ): Fan {
        val inset = orbRadius + margin
        val inner = Area(area.left + inset, area.top + inset, area.right - inset, area.bottom - inset)
        var best: Fan? = null
        var bestOverflow = Double.MAX_VALUE
        for ((spread, r) in shapes(radius, orbRadius, margin)) {
            for (tilt in tilts()) {
                val angles = SLOT_OFFSETS_DEG.map { angle(side, it * spread, tilt) }
                val orbs = angles.map { at(cx, cy, r, it) }
                val overflow = orbs.sumOf { overflow(it, inner) }
                val fan = Fan(cx, cy, angles, orbs, tilt, r)
                if (overflow <= EPSILON) return fan
                if (overflow < bestOverflow - EPSILON) {
                    bestOverflow = overflow
                    best = fan
                }
            }
        }
        // Nothing fits: the buttons are pushed in, and a finger picks by where they are drawn.
        val fan = checkNotNull(best)
        val orbs = fan.orbs.map { Point(clamp(it.x, inner.left, inner.right), clamp(it.y, inner.top, inner.bottom)) }
        val angles = orbs.map { Math.toDegrees(atan2((it.y - cy).toDouble(), (it.x - cx).toDouble())) }
        return fan.copy(orbs = orbs, anglesDeg = angles)
    }

    /**
     * The fan's shapes, best first, as (share of the full spread, radius): the full fan; the
     * same fan smaller, down to where neighbours would touch; then closed up to 2/3 of its
     * spread, on a circle just wide enough that they do not.
     */
    private fun shapes(radius: Float, orbRadius: Float, margin: Float): List<Pair<Double, Float>> {
        val step = SLOT_OFFSETS_DEG[1] - SLOT_OFFSETS_DEG[0]
        // The radius at which neighbours [degrees] apart keep [margin] between them.
        fun apart(degrees: Double): Float = ((orbRadius + margin / 2) / sin(Math.toRadians(degrees / 2))).toFloat()
        return buildList {
            add(1.0 to radius)
            for (share in listOf(0.9f, 0.8f)) {
                val r = max(radius * share, apart(step))
                if (r < radius - 0.5f && none { it.second == r }) add(1.0 to r)
            }
            for (spread in listOf(8.0 / 9, 7.0 / 9, 6.0 / 9)) add(spread to max(radius, apart(step * spread)))
        }
    }

    /**
     * A slot [offset] degrees from straight inward (negative is up), turned by [tilt]: straight
     * inward is 180 degrees on the right edge and 0 on the left, mirrored so slot 0 is at the top
     * on both.
     */
    private fun angle(side: BubbleSide, offset: Double, tilt: Double): Double = when (side) {
        BubbleSide.RIGHT -> 180.0 - offset + tilt
        BubbleSide.LEFT -> offset + tilt
    }

    /** 0, then +5, -5, +10, -10 ... */
    private fun tilts(): Sequence<Double> = sequence {
        yield(0.0)
        var tilt = TILT_STEP_DEG
        while (tilt <= MAX_TILT_DEG + EPSILON) {
            yield(tilt)
            yield(-tilt)
            tilt += TILT_STEP_DEG
        }
    }

    private fun at(cx: Float, cy: Float, radius: Float, angleDeg: Double): Point {
        val a = Math.toRadians(angleDeg)
        return Point(cx + (radius * cos(a)).toFloat(), cy + (radius * sin(a)).toFloat())
    }

    /** How far [point] lies outside [area], in pixels along both axes. */
    private fun overflow(point: Point, area: Area): Double =
        (max(0f, area.left - point.x) + max(0f, point.x - area.right) + max(0f, area.top - point.y) + max(0f, point.y - area.bottom)).toDouble()

    /** [a] - [b] folded into -180..180. */
    private fun difference(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    private const val EPSILON = 1e-6

    /** Where the old label menu put slot [slot]'s label; replaced by [fan]. */
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
        val angle = Math.toRadians(SLOT_OFFSETS_DEG[slot])
        val inward = if (side == BubbleSide.RIGHT) -1f else 1f
        val x = cx + inward * radius * cos(angle).toFloat()
        val y = cy + radius * sin(angle).toFloat()
        return Point(clamp(x, halfWidth, width - halfWidth), clamp(y, halfHeight, height - halfHeight))
    }

    /** Where the old label menu put the lit slot's name; replaced by the pill of [fan]. */
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
        val x = cx + inward * radius * 0.6f
        val above = cy - radius - halfHeight * 2
        val y = if (above - halfHeight >= 0f) above else cy + radius + halfHeight * 2
        return Point(clamp(x, halfWidth, width - halfWidth), clamp(y, halfHeight, height - halfHeight))
    }

    /** How far the old menu leaned the bubble toward the finger. */
    fun rubberBand(dx: Float, dy: Float, limit: Float): Point {
        val distance = hypot(dx, dy)
        if (distance == 0f || limit <= 0f) return Point(0f, 0f)
        val reach = limit * (1 - exp(-distance / limit))
        return Point(dx / distance * reach, dy / distance * reach)
    }

    private fun clamp(value: Float, low: Float, high: Float): Float =
        if (high < low) (low + high) / 2 else value.coerceIn(low, high)
}
