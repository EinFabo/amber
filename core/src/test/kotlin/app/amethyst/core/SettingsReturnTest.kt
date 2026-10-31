package app.amethyst.core

import app.amethyst.core.onboarding.SettingsReturn
import app.amethyst.core.onboarding.SettingsTrip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsReturnTest {

    private val minute = 60_000L

    @Test
    fun `nothing sent, nothing to come back for`() {
        val back = SettingsReturn()
        assertFalse(back.pending(SettingsTrip.ACCESSIBILITY, 1_000))
        assertFalse(back.take(SettingsTrip.ACCESSIBILITY, 1_000))
    }

    @Test
    fun `the service connecting soon after the trip brings Amber back once`() {
        val back = SettingsReturn()
        back.sent(SettingsTrip.ACCESSIBILITY, nowMs = 10_000)
        assertTrue(back.pending(SettingsTrip.ACCESSIBILITY, 40_000))
        assertTrue(back.take(SettingsTrip.ACCESSIBILITY, 40_000))
        // A rebind a moment later must not pull Amber to the front again.
        assertFalse(back.take(SettingsTrip.ACCESSIBILITY, 41_000))
        assertFalse(back.pending(SettingsTrip.ACCESSIBILITY, 41_000))
    }

    @Test
    fun `the accessibility trip is due for five minutes`() {
        val back = SettingsReturn()
        back.sent(SettingsTrip.ACCESSIBILITY, nowMs = 0)
        assertTrue(back.pending(SettingsTrip.ACCESSIBILITY, 5 * minute - 1))
        assertFalse(back.pending(SettingsTrip.ACCESSIBILITY, 5 * minute))
        assertEquals(5 * minute, SettingsTrip.ACCESSIBILITY.windowMs)
    }

    @Test
    fun `a late connect is not a return and uses the request up`() {
        val back = SettingsReturn()
        back.sent(SettingsTrip.ACCESSIBILITY, nowMs = 0)
        assertFalse(back.take(SettingsTrip.ACCESSIBILITY, 6 * minute))
        assertFalse(back.pending(SettingsTrip.ACCESSIBILITY, 0))
    }

    @Test
    fun `the battery trip is watched for two minutes`() {
        val back = SettingsReturn()
        back.sent(SettingsTrip.BATTERY, nowMs = 0)
        assertTrue(back.pending(SettingsTrip.BATTERY, 2 * minute - 1))
        assertFalse(back.take(SettingsTrip.BATTERY, 2 * minute))
    }

    @Test
    fun `another switch does not use up the trip`() {
        val back = SettingsReturn()
        back.sent(SettingsTrip.BATTERY, nowMs = 0)
        assertFalse(back.take(SettingsTrip.ACCESSIBILITY, 1_000))
        assertTrue(back.take(SettingsTrip.BATTERY, 2_000))
    }

    @Test
    fun `only the latest trip counts`() {
        val back = SettingsReturn()
        back.sent(SettingsTrip.ACCESSIBILITY, nowMs = 0)
        back.sent(SettingsTrip.BATTERY, nowMs = 1_000)
        assertFalse(back.take(SettingsTrip.ACCESSIBILITY, 2_000))
        assertTrue(back.pending(SettingsTrip.BATTERY, 2_000))
    }

    @Test
    fun `sending again starts the window over`() {
        val back = SettingsReturn()
        back.sent(SettingsTrip.ACCESSIBILITY, nowMs = 0)
        back.sent(SettingsTrip.ACCESSIBILITY, nowMs = 4 * minute)
        assertTrue(back.take(SettingsTrip.ACCESSIBILITY, 8 * minute))
    }

    @Test
    fun `coming back by hand cancels the trip`() {
        val back = SettingsReturn()
        back.sent(SettingsTrip.ACCESSIBILITY, nowMs = 0)
        back.cancel()
        assertFalse(back.take(SettingsTrip.ACCESSIBILITY, 1_000))
    }

    @Test
    fun `a clock before the trip is not a return`() {
        val back = SettingsReturn()
        back.sent(SettingsTrip.ACCESSIBILITY, nowMs = 10_000)
        assertFalse(back.pending(SettingsTrip.ACCESSIBILITY, 9_000))
    }
}
