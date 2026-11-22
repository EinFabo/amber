package app.amethyst.core

import app.amethyst.core.engine.ListenStop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListenStopTest {

    @Test
    fun `a stop while the recognizer listens is passed on at once`() {
        val stop = ListenStop()
        assertFalse(stop.listening(), "nothing asked for yet")
        assertTrue(stop.stop())
    }

    @Test
    fun `a stop before the recognizer listens is kept and due the moment it does`() {
        val stop = ListenStop()
        assertFalse(stop.stop(), "the recognizer would drop it")
        assertTrue(stop.listening())
    }

    @Test
    fun `a second stop is not passed on again`() {
        val stop = ListenStop()
        stop.listening()
        assertTrue(stop.stop())
        assertFalse(stop.stop())
    }

    @Test
    fun `a stop kept from before is not passed on twice either`() {
        val stop = ListenStop()
        stop.stop()
        assertTrue(stop.listening())
        assertFalse(stop.stop())
    }

    @Test
    fun `the first to settle wins - a result after the wait ran out changes nothing`() {
        val stop = ListenStop()
        stop.listening()
        stop.stop()
        assertTrue(stop.settle(), "the wait ran out")
        assertFalse(stop.settle(), "the late result")
        assertFalse(stop.settle(), "and the error after it")
    }

    @Test
    fun `a stop after the answer asks nothing of the recognizer`() {
        val stop = ListenStop()
        stop.listening()
        stop.settle()
        assertFalse(stop.stop())
    }

    @Test
    fun `a listening cancelled before it began passes no stop on`() {
        val stop = ListenStop()
        stop.stop()
        stop.settle()
        assertFalse(stop.listening())
    }

    @Test
    fun `the last interim text stands in for the result that never came`() {
        val stop = ListenStop()
        stop.listening()
        stop.partial("ich komme")
        stop.partial("ich komme um acht ")
        assertEquals("ich komme um acht", stop.heard)
    }

    @Test
    fun `an empty interim text does not wipe what was heard before`() {
        val stop = ListenStop()
        stop.partial("ich komme um acht")
        stop.partial("")
        stop.partial(null)
        stop.partial("   ")
        assertEquals("ich komme um acht", stop.heard)
    }

    @Test
    fun `without an interim text nothing was heard`() {
        assertNull(ListenStop().heard)
    }

    @Test
    fun `a recognizer gets about five seconds for its answer`() {
        assertEquals(5_000L, ListenStop.ANSWER_WITHIN_MS)
    }

    @Test
    fun `asked to stop, an empty answer or an error gives way to what was heard`() {
        val stop = ListenStop()
        stop.listening()
        stop.partial("ich komme um acht")
        stop.stop()
        assertEquals("ich komme um acht", stop.heardOnStop)
    }

    @Test
    fun `not asked to stop, an empty answer or an error stays what it is`() {
        val stop = ListenStop()
        stop.listening()
        stop.partial("ich komme um acht")
        assertNull(stop.heardOnStop, "the recognizer ended by itself")
        stop.stop()
        val nothing = ListenStop()
        nothing.listening()
        nothing.stop()
        assertNull(nothing.heardOnStop, "nothing heard before the stop")
    }
}
