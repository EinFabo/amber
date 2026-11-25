package app.amethyst.core

import app.amethyst.core.dictation.CancelledRun
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CancelledRunTest {

    @Test
    fun `a cancel while the bubble works on an ended recording keeps it for Try again`() {
        assertTrue(CancelledRun.keepsRecording(keepRecording = true, working = true, delivering = false))
    }

    @Test
    fun `a cancel once the words are being inserted keeps nothing - they land anyway`() {
        // A kept recording would let Try again type the same words a second time.
        assertFalse(CancelledRun.keepsRecording(keepRecording = true, working = true, delivering = true))
    }

    @Test
    fun `a cancel while listening, or a start over, keeps nothing`() {
        assertFalse(CancelledRun.keepsRecording(keepRecording = true, working = false, delivering = false))
        assertFalse(CancelledRun.keepsRecording(keepRecording = false, working = true, delivering = false))
    }

    @Test
    fun `Try again waits until the run before has ended, whatever it was doing`() {
        assertFalse(CancelledRun.mayBegin(retry = true, previousEnded = false, previousDelivering = false))
        assertFalse(CancelledRun.mayBegin(retry = true, previousEnded = false, previousDelivering = true))
        assertTrue(CancelledRun.mayBegin(retry = true, previousEnded = true, previousDelivering = false))
    }

    @Test
    fun `a new dictation waits only while the run before is still inserting its words`() {
        // A recognition on the phone cannot be aborted: a new dictation may record while a
        // cancelled one still unwinds there ([RunStop]) - but not while its words go in.
        assertTrue(CancelledRun.mayBegin(retry = false, previousEnded = false, previousDelivering = false))
        assertFalse(CancelledRun.mayBegin(retry = false, previousEnded = false, previousDelivering = true))
        assertTrue(CancelledRun.mayBegin(retry = false, previousEnded = true, previousDelivering = true))
    }
}
