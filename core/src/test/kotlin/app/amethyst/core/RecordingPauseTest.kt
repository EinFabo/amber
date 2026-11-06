package app.amethyst.core

import app.amethyst.core.audio.DiscardReason
import app.amethyst.core.audio.StopReason
import app.amethyst.core.audio.VadConfig
import app.amethyst.core.audio.VadDecision
import app.amethyst.core.audio.VadPolicy
import app.amethyst.core.capture.PausableRecording
import app.amethyst.core.capture.RecordingPause
import app.amethyst.core.capture.withPausableTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingPauseTest {

    @Test
    fun `paused time adds up over several pauses, an open one included`() {
        val pause = RecordingPause()
        assertEquals(0L, pause.pausedMs(now = 1_000))
        assertTrue(pause.pause(now = 1_000))
        assertTrue(pause.paused)
        assertEquals(500L, pause.pausedMs(now = 1_500))
        assertTrue(pause.resume(now = 3_000))
        assertFalse(pause.paused)
        assertEquals(2_000L, pause.pausedMs(now = 9_000))
        pause.pause(now = 10_000)
        assertEquals(2_700L, pause.pausedMs(now = 10_700), "the open pause counts up to now")
    }

    @Test
    fun `pausing twice or resuming what is not paused changes nothing`() {
        val pause = RecordingPause()
        assertFalse(pause.resume(now = 100))
        pause.pause(now = 1_000)
        assertFalse(pause.pause(now = 2_000), "already paused")
        pause.resume(now = 3_000)
        assertEquals(2_000L, pause.pausedMs(now = 5_000))
    }

    @Test
    fun `the time a run may still take does not count paused time`() {
        val pause = RecordingPause()
        assertEquals(60_000L, pause.remainingMs(limitMs = 60_000, startedAt = 0, now = 0))
        pause.pause(now = 10_000)
        assertEquals(50_000L, pause.remainingMs(limitMs = 60_000, startedAt = 0, now = 40_000), "frozen while paused")
        pause.resume(now = 40_000)
        assertEquals(40_000L, pause.remainingMs(limitMs = 60_000, startedAt = 0, now = 50_000))
    }

    @Test
    fun `the run trace says how long the recording was paused`() {
        val pause = RecordingPause()
        assertNull(pause.summary(now = 5_000), "never paused: no line")
        pause.pause(now = 1_000)
        pause.resume(now = 5_200)
        assertEquals("paused 4.2 s", pause.summary(now = 9_000))
        val short = RecordingPause().apply { pause(now = 0); resume(now = 600) }
        assertEquals("paused 0.6 s", short.summary(now = 1_000))
    }

    // One frame is 20 ms; speech is loud, the room quiet.
    private val config = VadConfig(silenceTimeoutMs = 900, maxDurationMs = 10_000, noSpeechTimeoutMs = 4_000, calibrationFrames = 5)
    private val quiet = 50.0
    private val loud = 2_000.0

    private fun recording() = PausableRecording(VadPolicy(config))

    /** Feeds [frames] frames of [rms]; the last step. */
    private fun PausableRecording.feed(frames: Int, rms: Double, stop: Boolean = false): PausableRecording.Step {
        var step: PausableRecording.Step = PausableRecording.Step.Drop
        repeat(frames) {
            step = onFrame(rms, stop)
            val decision = (step as? PausableRecording.Step.Keep)?.decision
            if (decision != null && decision != VadDecision.Continue) return step
        }
        return step
    }

    @Test
    fun `while paused every frame is dropped and none reaches the silence detection`() {
        val rec = recording()
        rec.feed(5, quiet)
        rec.feed(30, loud)
        // 600 ms of silence, then a pause long enough to stop on silence many times over.
        rec.feed(30, quiet)
        rec.pause.pause(now = 0)
        repeat(500) { assertEquals(PausableRecording.Step.Drop, rec.onFrame(quiet, stopRequested = false)) }
        rec.pause.resume(now = 10_000)
        // After the pause the full silence time starts again.
        assertEquals(PausableRecording.Step.Keep(VadDecision.Continue), rec.feed(40, quiet))
        assertEquals(PausableRecording.Step.Keep(VadDecision.Stop(StopReason.SILENCE)), rec.feed(10, quiet))
    }

    @Test
    fun `paused time does not count toward the longest recording`() {
        val rec = recording()
        rec.feed(5, quiet)
        rec.feed(400, loud) // 8 s of the 10 s limit
        rec.pause.pause(now = 0)
        rec.feed(1_000, loud) // 20 s paused: dropped, though loud
        rec.pause.resume(now = 20_000)
        assertEquals(PausableRecording.Step.Keep(VadDecision.Continue), rec.feed(80, loud))
        assertEquals(PausableRecording.Step.Keep(VadDecision.Stop(StopReason.MAX_DURATION)), rec.feed(20, loud))
    }

    @Test
    fun `a pause before anything was said does not run into the no-speech limit`() {
        val rec = recording()
        rec.feed(5, quiet)
        rec.feed(100, quiet) // 2 s of the 4 s
        rec.pause.pause(now = 0)
        rec.feed(500, quiet)
        rec.pause.resume(now = 10_000)
        assertEquals(PausableRecording.Step.Keep(VadDecision.Continue), rec.feed(90, quiet))
    }

    @Test
    fun `a stop while paused ends the recording as it stands, without the frame read meanwhile`() {
        val rec = recording()
        rec.feed(5, quiet)
        rec.feed(30, loud)
        rec.pause.pause(now = 0)
        rec.feed(50, quiet)
        val step = rec.onFrame(loud, stopRequested = true)
        assertEquals(PausableRecording.Step.End(VadDecision.Stop(StopReason.MANUAL)), step)
    }

    @Test
    fun `a stop while paused with nothing said throws the recording away`() {
        val rec = recording()
        rec.feed(5, quiet)
        rec.pause.pause(now = 0)
        val step = rec.onFrame(quiet, stopRequested = true)
        assertIs<PausableRecording.Step.End>(step)
        assertEquals(VadDecision.Discard(DiscardReason.NO_SPEECH), step.decision)
    }

    @Test
    fun `without a pause it is the plain silence detection`() {
        val rec = recording()
        rec.feed(5, quiet)
        rec.feed(30, loud)
        assertEquals(PausableRecording.Step.Keep(VadDecision.Stop(StopReason.SILENCE)), rec.feed(46, quiet))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the run's time limit waits while the recording is paused`() = runTest {
        val pause = RecordingPause()
        val result = async {
            withPausableTimeout(limitMs = 1_000, pause = pause, now = { testScheduler.currentTime }) {
                delay(2_500)
                "done"
            }
        }
        advanceTimeBy(400)
        pause.pause(testScheduler.currentTime)
        advanceTimeBy(1_100)
        assertFalse(result.isCompleted, "past the plain limit, still running")
        advanceTimeBy(900)
        pause.resume(testScheduler.currentTime)
        // 400 ms before the pause and 100 after it: 500 of 1 000 used when the work ends at 2 500.
        assertEquals("done", result.await())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the run's time limit still runs out when nothing is paused`() = runTest {
        val pause = RecordingPause()
        val never = CompletableDeferred<String>()
        val result = withPausableTimeout(limitMs = 1_000, pause = pause, now = { testScheduler.currentTime }) { never.await() }
        assertNull(result)
        assertEquals(1_000L, testScheduler.currentTime)
    }
}
