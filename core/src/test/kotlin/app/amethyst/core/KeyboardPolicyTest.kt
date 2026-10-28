package app.amethyst.core

import app.amethyst.core.bubble.KeyboardPolicy
import app.amethyst.core.bubble.KeyboardPolicy.Reading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class KeyboardPolicyTest {

    private val screen = 2400
    private val policy = KeyboardPolicy(defaultTopFraction = 0.6f)

    @Test
    fun `a focused field moves the bubble before the keyboard window exists`() {
        val decision = policy.onFocusChanged(true, now = 0, screenHeight = screen)
        assertEquals(1440, decision.top)
        assertNotNull(decision.recheckInMs)
    }

    @Test
    fun `a keyboard still sliding in is not taken for its position`() {
        policy.onFocusChanged(true, now = 0, screenHeight = screen)
        // Reported mid-animation, partly below the screen.
        val sliding = policy.onReading(Reading.Window(2362, 3245), now = 60, screenHeight = screen)
        assertEquals(1440, sliding.top, "holds the predicted position instead of dropping")
        assertNotNull(sliding.recheckInMs)

        val settled = policy.onReading(Reading.Window(1500, 2400), now = 300, screenHeight = screen)
        assertEquals(1500, settled.top)
        assertNull(settled.recheckInMs)
    }

    @Test
    fun `the next field predicts the height the keyboard really had`() {
        policy.onFocusChanged(true, now = 0, screenHeight = screen)
        policy.onReading(Reading.Window(1500, 2400), now = 300, screenHeight = screen)
        policy.onFocusChanged(false, now = 1_000, screenHeight = screen)
        policy.onReading(Reading.Absent, now = 1_300, screenHeight = screen)

        assertEquals(1500, policy.onFocusChanged(true, now = 5_000, screenHeight = screen).top)
    }

    @Test
    fun `a mid-animation reading never becomes the remembered height`() {
        policy.onFocusChanged(true, now = 0, screenHeight = screen)
        policy.onReading(Reading.Window(2165, 3048), now = 60, screenHeight = screen)
        policy.onFocusChanged(false, now = 100, screenHeight = screen)
        policy.onReading(Reading.Absent, now = 400, screenHeight = screen)

        assertEquals(1440, policy.onFocusChanged(true, now = 5_000, screenHeight = screen).top)
    }

    @Test
    fun `keyboard dismissed with back while the field keeps focus drops the bubble`() {
        policy.onFocusChanged(true, now = 0, screenHeight = screen)
        policy.onReading(Reading.Window(1500, 2400), now = 300, screenHeight = screen)

        val first = policy.onReading(Reading.Absent, now = 3_000, screenHeight = screen)
        assertEquals(1500, first.top, "one missing report is not yet a close")
        assertNull(policy.onReading(Reading.Absent, now = 3_300, screenHeight = screen).top)
    }

    @Test
    fun `a keyboard briefly missing from the window list does not drop the bubble`() {
        // Seen on a device: keyboard up at +66 ms, unreported at +156 ms, field still focused.
        policy.onFocusChanged(true, now = 0, screenHeight = screen)
        policy.onReading(Reading.Window(1242, 2400), now = 66, screenHeight = screen)

        val gap = policy.onReading(Reading.Absent, now = 156, screenHeight = screen)
        assertEquals(1242, gap.top)
        assertNotNull(gap.recheckInMs)
        assertEquals(1242, policy.onReading(Reading.Window(1242, 2400), now = 216, screenHeight = screen).top)
    }

    @Test
    fun `a keyboard sliding away takes the bubble down at once`() {
        // Pixel 9a, Android 17, back pressed with the field still focused: one reading below
        // the edge, then nothing until it is gone. The bubble used to hang ~430 ms after it.
        policy.onFocusChanged(true, now = 0, screenHeight = screen)
        policy.onReading(Reading.Window(1466, 2400), now = 300, screenHeight = screen)

        val sliding = policy.onReading(Reading.Window(1625, 2583), now = 3_000, screenHeight = screen)
        assertNull(sliding.top)
        assertNotNull(sliding.recheckInMs)
        val gone = policy.onReading(Reading.Absent, now = 3_130, screenHeight = screen)
        assertNull(gone.top)
        assertNull(gone.recheckInMs, "no waiting for a close that was seen coming")
    }

    @Test
    fun `a keyboard coming back after sliding away is followed again`() {
        policy.onFocusChanged(true, now = 0, screenHeight = screen)
        policy.onReading(Reading.Window(1466, 2400), now = 300, screenHeight = screen)
        policy.onReading(Reading.Window(1625, 2583), now = 3_000, screenHeight = screen)
        policy.onReading(Reading.Absent, now = 3_130, screenHeight = screen)

        // Same field tapped again: it slides in (below the edge) - the bubble goes up to the
        // height it had at once - and settles.
        assertEquals(1466, policy.onReading(Reading.Window(2169, 3127), now = 5_000, screenHeight = screen).top)
        assertEquals(1466, policy.onReading(Reading.Window(1466, 2400), now = 5_300, screenHeight = screen).top)
    }

    @Test
    fun `a keyboard sliding in is never taken for one sliding away`() {
        policy.onFocusChanged(true, now = 0, screenHeight = screen)
        // The prediction is up, the first reading is far below it - that is an arrival.
        assertEquals(1440, policy.onReading(Reading.Window(2169, 3127), now = 100, screenHeight = screen).top)
    }

    @Test
    fun `the height from the last run is used for the first field`() {
        val restarted = KeyboardPolicy(defaultTopFraction = 0.6f, remembered = KeyboardPolicy.Settled(1466, screen))
        assertEquals(1466, restarted.onFocusChanged(true, now = 0, screenHeight = screen).top)
        // Measured on another screen size (rotated, unfolded): the estimate instead.
        val rotated = KeyboardPolicy(defaultTopFraction = 0.6f, remembered = KeyboardPolicy.Settled(1466, 1080))
        assertEquals(1440, rotated.onFocusChanged(true, now = 0, screenHeight = screen).top)
    }

    @Test
    fun `the settled height is offered for keeping`() {
        assertNull(policy.settled)
        policy.onFocusChanged(true, now = 0, screenHeight = screen)
        policy.onReading(Reading.Window(1466, 2400), now = 300, screenHeight = screen)
        assertEquals(KeyboardPolicy.Settled(1466, screen), policy.settled)
    }

    @Test
    fun `a field that gets no keyboard lets the bubble back down`() {
        // The phone has shown a keyboard before, so its absence now means something.
        policy.onFocusChanged(true, now = 0, screenHeight = screen)
        policy.onReading(Reading.Window(1500, 2400), now = 300, screenHeight = screen)
        policy.onFocusChanged(false, now = 1_000, screenHeight = screen)
        policy.onReading(Reading.Absent, now = 1_300, screenHeight = screen)

        policy.onFocusChanged(true, now = 10_000, screenHeight = screen)
        assertEquals(1500, policy.onReading(Reading.Absent, now = 10_500, screenHeight = screen).top)
        val late = policy.onReading(Reading.Absent, now = 12_000, screenHeight = screen)
        assertNull(late.top)
        assertNull(late.recheckInMs)
    }

    @Test
    fun `a phone that never reports its keyboard keeps the estimate without polling forever`() {
        policy.onFocusChanged(true, now = 0, screenHeight = screen)
        val late = policy.onReading(Reading.Absent, now = 5_000, screenHeight = screen)
        assertEquals(1440, late.top)
        assertNull(late.recheckInMs)
    }

    @Test
    fun `a window frame over most of the screen is not the keys`() {
        policy.onFocusChanged(true, now = 0, screenHeight = screen)
        val tall = policy.onReading(Reading.Window(100, 2400), now = 300, screenHeight = screen)
        assertEquals(1440, tall.top)
    }

    @Test
    fun `a window parked below the screen is not polled forever`() {
        policy.onFocusChanged(true, now = 0, screenHeight = screen)
        assertNotNull(policy.onReading(Reading.Window(2500, 3300), now = 100, screenHeight = screen).recheckInMs)
        assertNull(policy.onReading(Reading.Window(2500, 3300), now = 2_500, screenHeight = screen).recheckInMs)
    }

    @Test
    fun `a keyboard that grows taller moves the bubble with it`() {
        policy.onFocusChanged(true, now = 0, screenHeight = screen)
        policy.onReading(Reading.Window(1500, 2400), now = 300, screenHeight = screen)
        // Emoji panel or suggestion strip: same bottom, higher top.
        assertEquals(1300, policy.onReading(Reading.Window(1300, 2400), now = 2_000, screenHeight = screen).top)
    }

    @Test
    fun `after a rotation the remembered height is not reused`() {
        policy.onFocusChanged(true, now = 0, screenHeight = screen)
        policy.onReading(Reading.Window(1500, 2400), now = 300, screenHeight = screen)
        policy.onFocusChanged(false, now = 1_000, screenHeight = screen)
        policy.onReading(Reading.Absent, now = 1_300, screenHeight = screen)

        assertEquals(648, policy.onFocusChanged(true, now = 5_000, screenHeight = 1080).top)
    }

    @Test
    fun `nonsense keyboard bounds are not polled forever`() {
        policy.onFocusChanged(true, now = 0, screenHeight = screen)
        assertNotNull(policy.onReading(Reading.Window(-50, 900), now = 60, screenHeight = screen).recheckInMs)
        assertNull(policy.onReading(Reading.Window(-50, 900), now = 60_000, screenHeight = screen).recheckInMs)
    }
}
