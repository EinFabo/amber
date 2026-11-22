package app.amethyst.core

import app.amethyst.core.capture.ProxyHandover
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyHandoverTest {

    private fun handover() = ProxyHandover(lostAfterMs = 3_000)

    @Test
    fun `the proxy that arrives starts the run that waits for it`() {
        val handover = handover()
        assertTrue(handover.begin(now = 0), "nothing on its way: launch one")
        assertTrue(handover.pending)
        assertEquals(ProxyHandover.Arrival(stopped = false), handover.arrive())
        assertFalse(handover.pending)
    }

    @Test
    fun `a release during the handover reaches the run the proxy starts`() {
        val handover = handover()
        handover.begin(now = 0)
        handover.stop()
        assertEquals(ProxyHandover.Arrival(stopped = true), handover.arrive())
    }

    @Test
    fun `a proxy that arrives after a cancel starts nothing`() {
        val handover = handover()
        handover.begin(now = 0)
        handover.cancel()
        assertFalse(handover.pending)
        assertNull(handover.arrive(), "cancelled: it must not record")
    }

    @Test
    fun `a proxy nobody launched starts nothing`() {
        assertNull(handover().arrive())
    }

    @Test
    fun `start over during the handover takes the proxy on its way instead of a second one`() {
        val handover = handover()
        assertTrue(handover.begin(now = 0))
        handover.stop()
        handover.cancel()
        assertFalse(handover.begin(now = 500), "one is still on its way: no second launch")
        assertEquals(ProxyHandover.Arrival(stopped = false), handover.arrive(), "the new run starts, without the old release")
        assertNull(handover.arrive(), "and only once")
    }

    @Test
    fun `a proxy lost on its way does not block the next launch`() {
        val handover = handover()
        handover.begin(now = 0)
        handover.cancel()
        assertTrue(handover.begin(now = 3_000), "past the wait the old one counts as lost")
    }

    @Test
    fun `once the wait gave up the next start launches again`() {
        val handover = handover()
        handover.begin(now = 0)
        handover.lost()
        assertFalse(handover.pending)
        assertNull(handover.arrive(), "the run failed already")
        assertTrue(handover.begin(now = 100))
    }

    @Test
    fun `a stop with no handover pending is kept for no one`() {
        val handover = handover()
        handover.stop()
        handover.begin(now = 0)
        assertEquals(ProxyHandover.Arrival(stopped = false), handover.arrive())
    }
    @Test
    fun `an activity that waits for the run that handed over still starts it, with a release from before`() {
        val handover = handover()
        handover.begin(now = 0)
        handover.stop()
        val arrival = handover.arrive()!!
        handover.waitAgain(arrival, now = 200)
        assertTrue(handover.pending)
        assertEquals(ProxyHandover.Arrival(stopped = true), handover.arrive())
    }

    @Test
    fun `a release while the activity waits for the run is kept as its stop`() {
        val handover = handover()
        handover.begin(now = 0)
        handover.waitAgain(handover.arrive()!!, now = 200)
        handover.stop()
        assertEquals(ProxyHandover.Arrival(stopped = true), handover.arrive())
    }

    @Test
    fun `a cancel while the activity waits for the run ends it`() {
        val handover = handover()
        handover.begin(now = 0)
        handover.waitAgain(handover.arrive()!!, now = 200)
        handover.cancel()
        assertNull(handover.arrive(), "cancelled: it must not record")
    }

    @Test
    fun `a start over while the activity waits for the run takes that activity`() {
        val handover = handover()
        handover.begin(now = 0)
        handover.waitAgain(handover.arrive()!!, now = 200)
        handover.cancel()
        assertFalse(handover.begin(now = 400), "the activity is up and calls in again: no second launch")
        assertEquals(ProxyHandover.Arrival(stopped = false), handover.arrive())
    }
}
