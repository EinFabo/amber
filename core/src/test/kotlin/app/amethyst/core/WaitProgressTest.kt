package app.amethyst.core

import app.amethyst.core.engine.RecognitionEstimate
import app.amethyst.core.engine.WaitProgress
import app.amethyst.core.engine.WaitProgress.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaitProgressTest {

    private var now = 0L
    private val wait = WaitProgress { now }

    private fun at(ms: Long): Float {
        now = ms
        return wait.value()
    }

    @Test
    fun `nothing planned stands at zero`() {
        assertEquals(0f, at(5_000))
    }

    @Test
    fun `steps share the ring by their expected time`() {
        wait.plan(Step.RECOGNIZE to 1_000, Step.CLEANUP to 3_000)
        assertEquals(0.125f, at(500), 0.001f)
        wait.begin(Step.CLEANUP)
        assertEquals(0.25f, at(500), 0.001f, "recognition done is its whole share")
        assertEquals(0.5f, at(1_500), 0.001f, "a quarter of the cleanup's 3 s")
    }

    @Test
    fun `the clock creeps but never fills a step`() {
        wait.plan(Step.RECOGNIZE to 1_000)
        assertEquals(0.9f, at(900), 0.001f)
        val late = at(10_000)
        assertTrue(late > 0.9f && late <= WaitProgress.CEILING, "$late")
    }

    @Test
    fun `bytes sent are the upload's share exactly`() {
        wait.plan(Step.UPLOAD to 2_000, Step.RECOGNIZE to 2_000)
        wait.report(Step.UPLOAD, done = 0.5f)
        assertEquals(0.25f, at(0), 0.001f)
        assertEquals(0.25f, at(1_500), 0.001f, "no signal, no movement")
        wait.report(Step.UPLOAD, done = 1f)
        assertEquals(0.5f, at(1_500), 0.001f)
    }

    @Test
    fun `between two chunks the clock fills only up to the next`() {
        wait.plan(Step.RECOGNIZE to 1_000)
        wait.report(Step.RECOGNIZE, done = 0f, upTo = 0.5f)
        val stuck = at(60_000)
        assertTrue(stuck <= 0.5f && stuck > 0.45f, "$stuck")
        wait.report(Step.RECOGNIZE, done = 0.5f, upTo = 1f)
        assertEquals(0.5f, at(60_000), 0.001f)
        assertEquals(0.75f, at(60_250), 0.001f)
    }

    @Test
    fun `it never goes back`() {
        wait.plan(Step.RECOGNIZE to 1_000)
        val before = at(800)
        wait.report(Step.RECOGNIZE, done = 0.1f)
        assertTrue(at(800) >= before)
        wait.begin(Step.UPLOAD)
        assertTrue(at(800) >= before, "a step that already passed or is not planned changes nothing")
    }

    @Test
    fun `a new plan spreads the rest from where the ring stands`() {
        wait.plan(Step.UPLOAD to 1_000, Step.RECOGNIZE to 1_000)
        now = 1_000
        wait.report(Step.UPLOAD, done = 1f)
        wait.begin(Step.RECOGNIZE)
        val stood = at(1_500)
        assertEquals(0.75f, stood, 0.001f)
        // The server failed: the fallback recognizes on the phone, then the cleanup.
        wait.plan(Step.RECOGNIZE to 1_000, Step.CLEANUP to 1_000)
        assertEquals(stood, at(1_500), 0.001f)
        assertEquals(0.75f + 0.25f * 0.25f, at(2_000), 0.001f)
    }

    @Test
    fun `a streamed cleanup counts its characters`() {
        wait.plan(Step.RECOGNIZE to 1_000, Step.CLEANUP to 1_000)
        wait.report(Step.CLEANUP, done = 0.4f)
        assertEquals(0.7f, at(0), 0.001f)
    }

    @Test
    fun `the creep is linear to ninety percent`() {
        assertEquals(0.5f, WaitProgress.creep(0.5f))
        assertEquals(0.9f, WaitProgress.creep(0.9f))
        assertTrue(WaitProgress.creep(5f) < 1f)
        assertEquals(0f, WaitProgress.creep(-1f))
    }

    @Test
    fun `learned rates survive a restart`() {
        val first = RecognitionEstimate()
        first.learn("tinfoil/voxtral", audioMs = 10_000, tookMs = 2_900, overheadMs = 900)
        val again = RecognitionEstimate()
        again.restore(first.snapshot())
        assertEquals(first.expectedMs("tinfoil/voxtral", 5_000, 0.08, 900), again.expectedMs("tinfoil/voxtral", 5_000, 0.08, 900))
        again.restore(mapOf("broken" to Double.NaN, "negative" to -1.0))
        assertEquals(1_000 + 500, again.expectedMs("broken", 5_000, 0.1, 1_000), "nonsense is dropped")
    }
}
