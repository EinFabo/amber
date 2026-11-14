package app.amethyst.core

import app.amethyst.core.capture.RecordingPause
import app.amethyst.core.capture.RunLimit
import app.amethyst.core.capture.withPausableTimeout
import app.amethyst.core.settings.CaptureSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunLimitTest {

    private var now = 0L

    @Test
    fun `while it records the run has the longest recording's limit`() {
        val limit = RunLimit(840_000, RecordingPause()) { now }
        now = 10_000
        assertEquals(840_000L, limit.limitMs)
        assertEquals(10_000L, limit.elapsedMs())
        assertEquals(830_000L, limit.remainingMs())
    }

    @Test
    fun `once the recording has ended the limit is what that recording can still need`() {
        val limit = RunLimit(840_000, RecordingPause()) { now }
        now = 10_000
        limit.recordingEnded(afterMs = 140_000)
        assertEquals(150_000L, limit.limitMs)
        assertEquals(140_000L, limit.remainingMs())
        now = 150_000
        assertEquals(0L, limit.remainingMs())
    }

    @Test
    fun `the end of the recording never makes the limit longer`() {
        val limit = RunLimit(60_000, RecordingPause()) { now }
        now = 50_000
        limit.recordingEnded(afterMs = 140_000)
        assertEquals(60_000L, limit.limitMs, "a limit the user set shorter stays")
    }

    @Test
    fun `told twice, the first end of the recording stands`() {
        val limit = RunLimit(840_000, RecordingPause()) { now }
        now = 10_000
        limit.recordingEnded(afterMs = 140_000)
        now = 100_000
        limit.recordingEnded(afterMs = 140_000)
        assertEquals(150_000L, limit.limitMs)
    }

    @Test
    fun `paused time counts toward nothing, before and after the recording's end`() {
        val pause = RecordingPause()
        val limit = RunLimit(840_000, pause) { now }
        now = 5_000
        pause.pause(now)
        now = 65_000
        pause.resume(now)
        now = 70_000
        assertEquals(10_000L, limit.elapsedMs())
        limit.recordingEnded(afterMs = 20_000)
        assertEquals(30_000L, limit.limitMs)
        assertEquals(20_000L, limit.remainingMs())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a recognizer that never answers is given up once its short recording's time is over`() = runTest {
        val limit = RunLimit(840_000, RecordingPause()) { testScheduler.currentTime }
        val never = CompletableDeferred<String>()
        launch {
            delay(10_000)
            limit.recordingEnded(afterMs = 20_000)
        }
        val result = withPausableTimeout(limit) { never.await() }
        assertNull(result)
        // Not the fourteen minutes: thirty seconds, noticed within the watchdog's next look.
        assertTrue(testScheduler.currentTime in 30_000L..30_000L + RunLimit.LOOK_AGAIN_MS, "${testScheduler.currentTime}")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `work that ends inside the shortened limit is kept`() = runTest {
        val limit = RunLimit(840_000, RecordingPause()) { testScheduler.currentTime }
        val result = async {
            withPausableTimeout(limit) {
                delay(10_000)
                limit.recordingEnded(afterMs = 20_000)
                delay(15_000)
                "done"
            }
        }
        advanceTimeBy(24_000)
        assertFalse(result.isCompleted)
        assertEquals("done", result.await())
    }

    @Test
    fun `a short dictation gets minutes less than the longest one, and still the server's time and the phone's after it`() {
        val settings = CaptureSettings()
        val short = settings.afterRecordingMs(10_000)
        // The server's own limit for ten seconds (70 s), a cold model on the phone (60 s), the cleanup.
        assertTrue(short >= 70_000L + 60_000L + 12_000L, "$short")
        assertTrue(short < 180_000L, "$short")
        assertTrue(settings.effectiveTotalTimeoutMs() - short > 10 * 60_000L, "the longest recording's limit is far longer")
    }

    @Test
    fun `the limit for the longest recording is that recording and what comes after it`() {
        val settings = CaptureSettings(maxDurationMs = 300_000, totalTimeoutMs = 60_000)
        assertEquals(300_000L + settings.afterRecordingMs(300_000), settings.effectiveTotalTimeoutMs())
    }

    @Test
    fun `a recording counts for no more than the longest allowed`() {
        val settings = CaptureSettings(maxDurationMs = 60_000)
        assertEquals(settings.afterRecordingMs(60_000), settings.afterRecordingMs(900_000))
        assertEquals(settings.afterRecordingMs(0), settings.afterRecordingMs(-5))
    }
}
