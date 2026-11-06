package app.amethyst.core

import app.amethyst.core.bubble.BubbleSide
import app.amethyst.core.bubble.QuickMenuGeometry
import app.amethyst.core.bubble.QuickMenuGeometry.Area
import app.amethyst.core.bubble.QuickMenuGeometry.Fan
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickMenuGeometryTest {

    // A 1080 x 2400 phone at 3x: a 56 dp bubble, buttons of 46 dp on a circle of 84 dp, 6 dp margin.
    private val radius = 252f
    private val orbRadius = 69f
    private val margin = 18f
    private val deadZone = 78f
    private val inset = orbRadius + margin
    private val portrait = Area(left = 0f, top = 72f, right = 1080f, bottom = 2310f)

    private fun fan(side: BubbleSide, cx: Float, cy: Float, area: Area = portrait): Fan =
        QuickMenuGeometry.fan(side, cx, cy, radius, orbRadius, margin, area)

    private fun Fan.fits(area: Area) = orbs.all {
        it.x >= area.left + inset - 0.5f && it.x <= area.right - inset + 0.5f &&
            it.y >= area.top + inset - 0.5f && it.y <= area.bottom - inset + 0.5f
    }

    @Test
    fun `on the right edge the buttons open to the left, slot 0 at the top`() {
        val fan = fan(BubbleSide.RIGHT, cx = 996f, cy = 1400f)
        assertEquals(0.0, fan.tiltDeg)
        assertTrue(fan.orbs.all { it.x < 996f }, "all inward")
        assertEquals(fan.orbs.sortedBy { it.y }, fan.orbs, "top to bottom")
        fan.orbs.forEach { assertEquals(radius, hypot(it.x - 996f, it.y - 1400f), 0.5f) }
        // -67.5, -22.5, 22.5, 67.5 degrees from straight left.
        assertEquals(996f - radius * 0.3827f, fan.orbs[0].x, 1f)
        assertEquals(1400f - radius * 0.9239f, fan.orbs[0].y, 1f)
        assertEquals(996f - radius * 0.9239f, fan.orbs[1].x, 1f)
    }

    @Test
    fun `the left edge keeps the same order from top to bottom`() {
        val fan = fan(BubbleSide.LEFT, cx = 84f, cy = 1400f)
        assertTrue(fan.orbs.all { it.x > 84f }, "all inward")
        assertEquals(fan.orbs.sortedBy { it.y }, fan.orbs, "slot 0 at the top, as on the right edge")
        val right = fan(BubbleSide.RIGHT, cx = 996f, cy = 1400f)
        fan.orbs.zip(right.orbs).forEach { (l, r) -> assertEquals(l.y, r.y, 0.5f) }
    }

    @Test
    fun `near the top the fan turns down until every button is below the status bar`() {
        val fan = fan(BubbleSide.RIGHT, cx = 996f, cy = 360f)
        assertTrue(fan.tiltDeg != 0.0)
        assertTrue(abs(fan.tiltDeg) <= QuickMenuGeometry.MAX_TILT_DEG)
        assertTrue(fan.fits(portrait), "${fan.orbs}")
        assertTrue(fan.orbs.minOf { it.y } >= 72f + inset - 0.5f)
        assertEquals(fan.orbs.sortedBy { it.y }, fan.orbs, "still top to bottom")
        assertEquals(0.0, abs(fan.tiltDeg % QuickMenuGeometry.TILT_STEP_DEG))
        assertEquals(radius, fan.radius, "a turn is enough, the fan keeps its size")
        // Turned down: slot 0 has moved from up-and-inward toward straight inward.
        assertTrue(fan.anglesDeg[0] < 247.5)
    }

    @Test
    fun `the left edge turns the other way near the top, also down`() {
        val right = fan(BubbleSide.RIGHT, cx = 996f, cy = 360f)
        val left = fan(BubbleSide.LEFT, cx = 84f, cy = 360f)
        assertTrue(left.fits(portrait))
        assertEquals(-right.tiltDeg, left.tiltDeg)
    }

    @Test
    fun `right under the status bar, where no turn is enough, the fan closes up and fits`() {
        val fan = fan(BubbleSide.RIGHT, cx = 996f, cy = 260f)
        assertTrue(fan.fits(portrait), "${fan.orbs}")
        assertEquals(fan.orbs.sortedBy { it.y }, fan.orbs)
        fan.orbs.zipWithNext().forEach { (a, b) -> assertTrue(hypot(a.x - b.x, a.y - b.y) >= 2 * orbRadius) }
    }

    @Test
    fun `at its usual height above the keyboard the whole fan fits`() {
        // The keys from 1500 down; the bubble 80 dp above them, its centre 108 dp up.
        val keyboard = portrait.copy(bottom = 1500f)
        val fan = fan(BubbleSide.RIGHT, cx = 996f, cy = 1500f - 324f, area = keyboard)
        assertEquals(0.0, fan.tiltDeg)
        assertTrue(fan.fits(keyboard), "${fan.orbs}")
    }

    @Test
    fun `a little lower the fan turns up, clear of the keys`() {
        val keyboard = portrait.copy(bottom = 1500f)
        val fan = fan(BubbleSide.RIGHT, cx = 996f, cy = 1500f - 290f, area = keyboard)
        assertTrue(fan.tiltDeg > 0.0, "up, away from the keys: ${fan.tiltDeg}")
        assertTrue(fan.fits(keyboard), "${fan.orbs}")
    }

    @Test
    fun `dragged down to the keys the fan closes up above them, its buttons apart`() {
        // 12 dp between bubble and keys: no turn of the full fan stays on the screen and off the keys.
        val keyboard = portrait.copy(bottom = 1500f)
        val fan = fan(BubbleSide.RIGHT, cx = 996f, cy = 1500f - 120f, area = keyboard)
        assertTrue(fan.fits(keyboard), "${fan.orbs}")
        assertEquals(fan.orbs.sortedBy { it.y }, fan.orbs, "still top to bottom")
        fan.orbs.zipWithNext().forEach { (a, b) ->
            assertTrue(hypot(a.x - b.x, a.y - b.y) >= 2 * orbRadius, "neighbours do not overlap: $a $b")
        }
        fan.orbs.forEachIndexed { slot, orb -> assertEquals(slot, fan.slotAt(orb.x, orb.y, deadZone)) }
    }

    @Test
    fun `with room all around the fan is not turned`() {
        assertEquals(0.0, fan(BubbleSide.LEFT, cx = 84f, cy = 1200f).tiltDeg)
    }

    @Test
    fun `on a short landscape screen with the keyboard up the fan gets smaller and fits`() {
        // 2400 x 1080, the keys from 640: the full fan needs more height than there is.
        val short = Area(left = 0f, top = 72f, right = 2400f, bottom = 640f)
        val fan = fan(BubbleSide.RIGHT, cx = 2316f, cy = 640f - 324f, area = short)
        assertTrue(fan.radius < radius, "smaller: ${fan.radius}")
        assertTrue(fan.fits(short), "${fan.orbs}")
        assertTrue(fan.orbs.all { it.x < 2316f }, "still inward")
        fan.orbs.zipWithNext().forEach { (a, b) -> assertTrue(hypot(a.x - b.x, a.y - b.y) >= 2 * orbRadius) }
    }

    @Test
    fun `where nothing fits the buttons are pushed onto the screen and still picked where they are`() {
        val tiny = Area(left = 0f, top = 72f, right = 2400f, bottom = 420f)
        val fan = fan(BubbleSide.RIGHT, cx = 2316f, cy = 300f, area = tiny)
        assertTrue(fan.fits(tiny), "${fan.orbs}")
        fan.orbs.forEachIndexed { slot, orb ->
            if (hypot(orb.x - 2316f, orb.y - 300f) >= deadZone) assertEquals(slot, fan.slotAt(orb.x, orb.y, deadZone))
        }
    }

    @Test
    fun `a landscape screen with room keeps the plain fan`() {
        val landscape = Area(left = 0f, top = 72f, right = 2400f, bottom = 1080f)
        val fan = fan(BubbleSide.LEFT, cx = 84f, cy = 540f, area = landscape)
        assertEquals(0.0, fan.tiltDeg)
        assertTrue(fan.fits(landscape))
    }

    @Test
    fun `a finger picks the slot whose direction is nearest`() {
        val fan = fan(BubbleSide.RIGHT, cx = 996f, cy = 1400f)
        fan.orbs.forEachIndexed { slot, orb -> assertEquals(slot, fan.slotAt(orb.x, orb.y, deadZone)) }
        // Far past a button, the direction still counts.
        val far = fan.orbs[1]
        assertEquals(1, fan.slotAt(996f + (far.x - 996f) * 3, 1400f + (far.y - 1400f) * 3, deadZone))
    }

    @Test
    fun `the left edge picks with the mirrored fan`() {
        val fan = fan(BubbleSide.LEFT, cx = 84f, cy = 1400f)
        assertEquals(0, fan.slotAt(84f + 60f, 1400f - 200f, deadZone))
        assertEquals(2, fan.slotAt(84f + 200f, 1400f + 70f, deadZone))
    }

    @Test
    fun `inside the dead zone nothing is picked`() {
        val fan = fan(BubbleSide.RIGHT, cx = 996f, cy = 1400f)
        assertNull(fan.slotAt(996f - 50f, 1400f - 50f, deadZone))
        assertNull(fan.slotAt(996f, 1400f, deadZone))
    }

    @Test
    fun `more than 32 degrees from every slot picks nothing`() {
        val fan = fan(BubbleSide.RIGHT, cx = 996f, cy = 1400f)
        // Straight right, out of the screen: 112.5 degrees from the nearest slot.
        assertNull(fan.slotAt(996f + 200f, 1400f, deadZone))
        // Straight up is 22.5 degrees past slot 0: still slot 0.
        assertEquals(0, fan.slotAt(996f, 1400f - 300f, deadZone))
        // Up and a little outward, 40 degrees past slot 0: nothing.
        val a = Math.toRadians(247.5 + 40)
        assertNull(fan.slotAt(996f + 300f * Math.cos(a).toFloat(), 1400f + 300f * Math.sin(a).toFloat(), deadZone))
    }

    @Test
    fun `an empty slot is never picked, not even the nearest one`() {
        val fan = fan(BubbleSide.RIGHT, cx = 996f, cy = 1400f)
        val empty = listOf(true, true, false, false)
        val orb = fan.orbs[2]
        assertNull(fan.slotAt(orb.x, orb.y, deadZone) { empty[it] })
        assertEquals(1, fan.slotAt(fan.orbs[1].x, fan.orbs[1].y, deadZone) { empty[it] })
    }

    @Test
    fun `a direction exactly between two slots always gives the upper one`() {
        val fan = fan(BubbleSide.RIGHT, cx = 996f, cy = 1400f)
        // Straight left lies between slots 1 and 2.
        assertEquals(1, fan.slotAt(996f - 200f, 1400f, deadZone))
    }

    @Test
    fun `a turned fan picks by its turned directions`() {
        val fan = fan(BubbleSide.RIGHT, cx = 996f, cy = 360f)
        fan.orbs.forEachIndexed { slot, orb -> assertEquals(slot, fan.slotAt(orb.x, orb.y, deadZone)) }
    }
}
