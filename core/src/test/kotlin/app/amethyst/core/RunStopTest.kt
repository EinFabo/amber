package app.amethyst.core

import app.amethyst.core.dictation.RunStop
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RunStopTest {

    @Test
    fun `a tap stops the run in progress, and its end clears the stop`() {
        val stop = RunStop()
        val run = stop.begin()
        assertFalse(stop.requested)
        stop.request()
        assertTrue(stop.requested)
        assertTrue(stop.end(run))
        assertFalse(stop.requested, "the next run starts unstopped")
    }

    @Test
    fun `a cancelled run that ends late does not take the next run's stop away`() {
        val stop = RunStop()
        // Cancelled during a recognition on the phone that cannot be aborted: still unwinding.
        val cancelled = stop.begin()
        val next = stop.begin()
        stop.request()
        assertFalse(stop.end(cancelled), "no longer its run")
        assertTrue(stop.requested, "the tap that stopped the new dictation still stands")
        assertTrue(stop.end(next))
        assertFalse(stop.requested)
    }

    @Test
    fun `a late run ending after the next one finished changes nothing either`() {
        val stop = RunStop()
        val cancelled = stop.begin()
        val next = stop.begin()
        stop.end(next)
        val third = stop.begin()
        stop.request()
        assertFalse(stop.end(cancelled))
        assertFalse(stop.end(next), "ended twice")
        assertTrue(stop.requested)
        assertTrue(stop.end(third))
    }

    @Test
    fun `a new run does not inherit the stop of the one before`() {
        val stop = RunStop()
        stop.begin()
        stop.request()
        stop.begin()
        assertFalse(stop.requested)
    }

    @Test
    fun `a run stopped while its microphone was being opened begins stopped`() {
        val stop = RunStop()
        val run = stop.begin(stoppedBefore = true)
        assertTrue(stop.requested)
        stop.end(run)
        assertFalse(stop.requested)
    }

    @Test
    fun `every run has its own token`() {
        val stop = RunStop()
        assertNotEquals(stop.begin(), stop.begin())
    }
}
