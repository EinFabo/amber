package app.amethyst.core

import app.amethyst.core.capture.ProxyHandover
import app.amethyst.core.commands.RunMode
import app.amethyst.core.dictation.BubbleTap
import app.amethyst.core.dictation.BubbleTap.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BubbleTapTest {

    private fun tap(
        working: Boolean = false,
        starting: Boolean = false,
        listening: Boolean = false,
        mode: RunMode = RunMode.DICTATION,
        secondTapOfStop: Boolean = false,
    ) = BubbleTap.onTap(working, starting, listening, mode, secondTapOfStop)

    @Test
    fun `a tap on the resting bubble starts a dictation`() {
        assertEquals(Action.START, tap())
    }

    @Test
    fun `a tap while it listens ends the recording`() {
        assertEquals(Action.STOP_RECORDING, tap(working = true, listening = true))
    }

    @Test
    fun `a second tap while the microphone is still being opened is the stop, not another start`() {
        // Handed to the invisible activity: the bubble shows starting, nothing works on it yet.
        assertEquals(Action.STOP_RECORDING, tap(working = false, starting = true))
    }

    @Test
    fun `a tap while the run starts with its work under way is a stop too`() {
        assertEquals(Action.STOP_RECORDING, tap(working = true, starting = true))
    }

    @Test
    fun `that stop reaches the recording when the activity arrives`() {
        val handover = ProxyHandover(lostAfterMs = 3_000)
        assertTrue(handover.begin(now = 0))
        assertEquals(Action.STOP_RECORDING, tap(starting = true))
        handover.stop()
        val arrival = assertNotNull(handover.arrive())
        assertTrue(arrival.stopped, "the recording it starts ends at once")
    }

    @Test
    fun `a tap while a dictation is being written does not throw its words away, it says how to cancel`() {
        assertEquals(Action.STILL_WORKING, tap(working = true))
        // Still recording: the tap is the stop, as always.
        assertEquals(Action.STOP_RECORDING, tap(working = true, listening = true))
        assertEquals(Action.STOP_RECORDING, tap(working = true, starting = true))
    }

    @Test
    fun `a tap stops a command past its recording at once`() {
        assertEquals(Action.CANCEL_COMMAND, tap(working = true, mode = RunMode.COMMAND))
    }

    @Test
    fun `a tap while a command is still being recorded or started ends the recording`() {
        assertEquals(Action.STOP_RECORDING, tap(working = true, listening = true, mode = RunMode.COMMAND))
        assertEquals(Action.STOP_RECORDING, tap(starting = true, mode = RunMode.COMMAND))
    }

    @Test
    fun `the second tap of the double tap that stopped a command starts nothing`() {
        assertEquals(Action.NOTHING, tap(secondTapOfStop = true))
    }

    @Test
    fun `during a run the second tap of a stop is a tap like any other`() {
        assertEquals(Action.STOP_RECORDING, tap(working = true, listening = true, secondTapOfStop = true))
    }
}
