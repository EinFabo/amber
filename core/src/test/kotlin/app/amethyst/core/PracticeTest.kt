package app.amethyst.core

import app.amethyst.core.bubble.BubbleEvent
import app.amethyst.core.bubble.BubbleSide
import app.amethyst.core.bubble.QuickAction
import app.amethyst.core.onboarding.Practice
import app.amethyst.core.onboarding.PracticeBlock
import app.amethyst.core.onboarding.PracticeReadiness
import app.amethyst.core.onboarding.PracticeTask
import app.amethyst.core.onboarding.Recognition
import app.amethyst.core.onboarding.TaskState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PracticeTest {

    private val fresh = Practice.start(BubbleSide.RIGHT)

    @Test
    fun `a fresh practice has every task open, the tap first`() {
        PracticeTask.entries.forEach { assertEquals(TaskState.OPEN, fresh.state(it)) }
        assertEquals(PracticeTask.TAP, fresh.current)
        assertEquals(listOf(PracticeTask.TAP, PracticeTask.HOLD, PracticeTask.DRAG), PracticeTask.entries)
        assertFalse(fresh.allDone)
        assertFalse(fresh.finished)
    }

    @Test
    fun `an inserted dictation ticks the tap`() {
        val after = fresh.on(BubbleEvent.DictationInserted)
        assertEquals(TaskState.DONE, after.state(PracticeTask.TAP))
        assertEquals(PracticeTask.HOLD, after.current)
    }

    @Test
    fun `any quick action that ran ticks the hold`() {
        QuickAction.entries.forEach { action ->
            assertEquals(TaskState.DONE, fresh.on(BubbleEvent.QuickActionRun(action)).state(PracticeTask.HOLD), action.name)
        }
    }

    @Test
    fun `a dock on the side it started on is no drag to the other edge`() {
        val after = fresh.on(BubbleEvent.Docked(BubbleSide.RIGHT))
        assertEquals(TaskState.OPEN, after.state(PracticeTask.DRAG))
    }

    @Test
    fun `a dock on the other side ticks the drag, from either side`() {
        assertEquals(TaskState.DONE, fresh.on(BubbleEvent.Docked(BubbleSide.LEFT)).state(PracticeTask.DRAG))
        val fromLeft = Practice.start(BubbleSide.LEFT)
        assertEquals(TaskState.DONE, fromLeft.on(BubbleEvent.Docked(BubbleSide.RIGHT)).state(PracticeTask.DRAG))
    }

    @Test
    fun `putting the flame back afterwards keeps the drag ticked`() {
        val after = fresh.on(BubbleEvent.Docked(BubbleSide.LEFT)).on(BubbleEvent.Docked(BubbleSide.RIGHT))
        assertEquals(TaskState.DONE, after.state(PracticeTask.DRAG))
    }

    @Test
    fun `tasks tick in any order - Amber counts what it sees`() {
        val after = fresh.on(BubbleEvent.Docked(BubbleSide.LEFT))
        assertEquals(TaskState.DONE, after.state(PracticeTask.DRAG))
        assertEquals(PracticeTask.TAP, after.current)
    }

    @Test
    fun `all three done`() {
        val after = fresh
            .on(BubbleEvent.DictationInserted)
            .on(BubbleEvent.QuickActionRun(QuickAction.COPY_LAST))
            .on(BubbleEvent.Docked(BubbleSide.LEFT))
        assertTrue(after.allDone)
        assertTrue(after.finished)
        assertNull(after.current)
    }

    @Test
    fun `a skipped task moves on to the next, and is not done`() {
        val after = fresh.skip(PracticeTask.TAP)
        assertEquals(TaskState.SKIPPED, after.state(PracticeTask.TAP))
        assertEquals(PracticeTask.HOLD, after.current)
        assertFalse(after.allDone)
    }

    @Test
    fun `everything skipped or done is finished, but not all done`() {
        val after = fresh.skip(PracticeTask.TAP).on(BubbleEvent.QuickActionRun(QuickAction.COPY_LAST)).skip(PracticeTask.DRAG)
        assertTrue(after.finished)
        assertFalse(after.allDone)
    }

    @Test
    fun `a skipped task that happens after all is done`() {
        val after = fresh.skip(PracticeTask.TAP).on(BubbleEvent.DictationInserted)
        assertEquals(TaskState.DONE, after.state(PracticeTask.TAP))
    }

    @Test
    fun `a done task cannot be skipped back`() {
        val after = fresh.on(BubbleEvent.DictationInserted).skip(PracticeTask.TAP)
        assertEquals(TaskState.DONE, after.state(PracticeTask.TAP))
    }

    @Test
    fun `reopening the step starts over from the flame's side then`() {
        val left = fresh.on(BubbleEvent.Docked(BubbleSide.LEFT)).on(BubbleEvent.DictationInserted)
        val again = Practice.start(BubbleSide.LEFT)
        assertTrue(left.state(PracticeTask.DRAG) == TaskState.DONE)
        PracticeTask.entries.forEach { assertEquals(TaskState.OPEN, again.state(it)) }
        assertEquals(TaskState.DONE, again.on(BubbleEvent.Docked(BubbleSide.RIGHT)).state(PracticeTask.DRAG))
    }

    @Test
    fun `saved and restored, as across a rotation`() {
        val progress = fresh.on(BubbleEvent.DictationInserted).skip(PracticeTask.HOLD)
        assertEquals(progress, Practice.restore(progress.save()))
        assertEquals(fresh, Practice.restore(fresh.save()))
    }

    @Test
    fun `a broken saved state starts over instead of crashing`() {
        assertNull(Practice.restore(emptyList()))
        assertNull(Practice.restore(listOf("UP")))
        assertEquals(Practice.start(BubbleSide.LEFT), Practice.restore(listOf("LEFT", "", "")))
        assertEquals(Practice.start(BubbleSide.LEFT), Practice.restore(listOf("LEFT", "NONSENSE", "")))
    }

    // What stands in the way.

    private val ready = PracticeReadiness(serviceOn = true, bubbleOn = true, microphone = true, recognition = Recognition.READY, hasLastDictation = true)

    @Test
    fun `nothing stands in the way when everything is ready`() {
        PracticeTask.entries.forEach { assertNull(Practice.blocked(it, ready, fresh)) }
        assertNull(Practice.stepBlock(ready))
    }

    @Test
    fun `without the service no task can happen`() {
        val off = ready.copy(serviceOn = false)
        assertEquals(PracticeBlock.NO_SERVICE, Practice.stepBlock(off))
        PracticeTask.entries.forEach { assertEquals(PracticeBlock.NO_SERVICE, Practice.blocked(it, off, fresh)) }
    }

    @Test
    fun `the service off says so before a flame switched off`() {
        assertEquals(PracticeBlock.NO_SERVICE, Practice.stepBlock(ready.copy(serviceOn = false, bubbleOn = false)))
        assertEquals(PracticeBlock.BUBBLE_OFF, Practice.stepBlock(ready.copy(bubbleOn = false)))
    }

    @Test
    fun `without the microphone only the tap is stuck - and the hold until a dictation was made`() {
        val noMic = ready.copy(microphone = false, hasLastDictation = false)
        assertNull(Practice.stepBlock(noMic))
        assertEquals(PracticeBlock.NO_MICROPHONE, Practice.blocked(PracticeTask.TAP, noMic, fresh))
        assertEquals(PracticeBlock.NEEDS_DICTATION, Practice.blocked(PracticeTask.HOLD, noMic, fresh))
        assertNull(Practice.blocked(PracticeTask.DRAG, noMic, fresh))
    }

    @Test
    fun `the hold needs a dictation first, also when one could be made but the tap was skipped`() {
        // Copy last is greyed out in the quick menu until there is a last dictation.
        val none = ready.copy(hasLastDictation = false)
        val tapSkipped = fresh.skip(PracticeTask.TAP)
        assertEquals(PracticeBlock.NEEDS_DICTATION, Practice.blocked(PracticeTask.HOLD, none, tapSkipped))
        assertEquals(PracticeBlock.NEEDS_DICTATION, Practice.blocked(PracticeTask.HOLD, none, fresh))
        assertNull(Practice.blocked(PracticeTask.TAP, none, fresh), "the tap is what makes one")
        // One from before - an earlier dictation in this session - is enough.
        assertNull(Practice.blocked(PracticeTask.HOLD, ready, tapSkipped))
    }

    @Test
    fun `a model still downloading holds the tap, not the drag`() {
        val downloading = ready.copy(recognition = Recognition.DOWNLOADING, hasLastDictation = false)
        assertEquals(PracticeBlock.DOWNLOADING, Practice.blocked(PracticeTask.TAP, downloading, fresh))
        assertEquals(PracticeBlock.NEEDS_DICTATION, Practice.blocked(PracticeTask.HOLD, downloading, fresh))
        assertNull(Practice.blocked(PracticeTask.DRAG, downloading, fresh))
    }

    @Test
    fun `no recognition at all holds the tap`() {
        val none = ready.copy(recognition = Recognition.NONE)
        assertEquals(PracticeBlock.NO_RECOGNITION, Practice.blocked(PracticeTask.TAP, none, fresh))
    }

    @Test
    fun `the microphone is asked for before the download is waited for`() {
        val both = ready.copy(microphone = false, recognition = Recognition.DOWNLOADING)
        assertEquals(PracticeBlock.NO_MICROPHONE, Practice.blocked(PracticeTask.TAP, both, fresh))
    }

    @Test
    fun `a task already done is never blocked`() {
        val noMic = ready.copy(microphone = false)
        val tapped = fresh.on(BubbleEvent.DictationInserted)
        assertNull(Practice.blocked(PracticeTask.TAP, noMic, tapped))
        assertNull(Practice.blocked(PracticeTask.HOLD, noMic, tapped))
    }

    @Test
    fun `recognition is ready, downloading or missing`() {
        assertEquals(Recognition.NONE, Recognition.of(engineChosen = false, downloading = false, needsModel = false, modelReady = false))
        assertEquals(Recognition.DOWNLOADING, Recognition.of(engineChosen = true, downloading = true, needsModel = true, modelReady = false))
        assertEquals(Recognition.NONE, Recognition.of(engineChosen = true, downloading = false, needsModel = true, modelReady = false))
        assertEquals(Recognition.READY, Recognition.of(engineChosen = true, downloading = false, needsModel = true, modelReady = true))
        // A server or Android's own recognizer needs no model on the phone.
        assertEquals(Recognition.READY, Recognition.of(engineChosen = true, downloading = false, needsModel = false, modelReady = false))
    }
}
