package app.amethyst.core

import app.amethyst.core.commands.RunMode
import app.amethyst.core.dictation.ScreenOff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenOffTest {

    @Test
    fun `the power button ends a dictation's recording as a tap would`() {
        assertEquals(
            ScreenOff.Action.STOP_RECORDING,
            ScreenOff.onScreenOff(recording = true, begun = true, mode = RunMode.DICTATION),
        )
    }

    @Test
    fun `a recording that has not begun is dropped - nothing was said yet`() {
        assertEquals(
            ScreenOff.Action.CANCEL,
            ScreenOff.onScreenOff(recording = true, begun = false, mode = RunMode.DICTATION),
        )
    }

    @Test
    fun `a voice command is not carried out on a dark screen`() {
        assertEquals(
            ScreenOff.Action.CANCEL,
            ScreenOff.onScreenOff(recording = true, begun = true, mode = RunMode.COMMAND),
        )
    }

    @Test
    fun `a run past its recording goes on - its text is kept when it is ready`() {
        for (mode in RunMode.entries) {
            assertEquals(ScreenOff.Action.NOTHING, ScreenOff.onScreenOff(recording = false, begun = true, mode = mode))
            assertEquals(ScreenOff.Action.NOTHING, ScreenOff.onScreenOff(recording = false, begun = false, mode = mode))
        }
    }

    @Test
    fun `text goes in only with the screen on and the phone unlocked`() {
        assertTrue(ScreenOff.mayInsert(screenOn = true, locked = false))
        assertFalse(ScreenOff.mayInsert(screenOn = false, locked = false), "the lock may follow a moment later")
        assertFalse(ScreenOff.mayInsert(screenOn = true, locked = true), "the lock screen's fields are not the user's")
        assertFalse(ScreenOff.mayInsert(screenOn = false, locked = true))
    }
}
