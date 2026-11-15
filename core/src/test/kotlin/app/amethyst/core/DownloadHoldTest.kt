package app.amethyst.core

import app.amethyst.core.engine.FileProblem
import app.amethyst.core.engine.download.DownloadHold
import app.amethyst.core.engine.download.HoldStep
import app.amethyst.core.engine.download.NetworkFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When a model download in hand starts, waits and tries again. */
class DownloadHoldTest {

    private var now = 1_000_000L
    private val hold = DownloadHold { now }
    private val wifi = NetworkFacts(validated = true, metered = false)
    private val mobile = NetworkFacts(validated = true, metered = true)
    private val loginPage = NetworkFacts(validated = false, metered = false)
    private val minute = 60_000L

    @Test
    fun `wifi that reaches the internet starts it, a login page does not`() {
        hold.take(mobileData = false)
        assertEquals(HoldStep.START, hold.step(wifi))
        assertEquals(HoldStep.WAIT_FOR_WIFI, hold.step(loginPage))
        assertEquals(HoldStep.WAIT_FOR_WIFI, hold.step(null))
    }

    @Test
    fun `mobile data only when it was allowed`() {
        hold.take(mobileData = false)
        assertEquals(HoldStep.WAIT_FOR_WIFI, hold.step(mobile))
        hold.take(mobileData = true)
        assertEquals(HoldStep.START, hold.step(mobile))
        assertEquals(HoldStep.WAIT_FOR_NETWORK, hold.step(null))
        assertEquals(HoldStep.WAIT_FOR_NETWORK, hold.step(mobile.copy(validated = false)))
    }

    @Test
    fun `after a failure the next tries come after 1, 5, 15 and 60 minutes, then every hour`() {
        hold.take(mobileData = false)
        val waits = (1..6).map {
            assertTrue(hold.failed(FileProblem.ServerAnswered(503), progressKept = false, resumes = true))
            assertEquals(HoldStep.WAIT_FOR_TURN, hold.step(wifi))
            val wait = hold.notBeforeMs - now
            now = hold.notBeforeMs - 1
            assertEquals(HoldStep.WAIT_FOR_TURN, hold.step(wifi))
            now += 1
            assertEquals(HoldStep.START, hold.step(wifi))
            wait / minute
        }
        assertEquals(listOf(1L, 5L, 15L, 60L, 60L, 60L), waits)
    }

    @Test
    fun `a try that got somewhere starts the schedule over`() {
        hold.take(mobileData = false)
        repeat(3) { hold.failed(FileProblem.Interrupted, progressKept = false, resumes = true) }
        assertEquals(15 * minute, hold.notBeforeMs - now)
        hold.failed(FileProblem.Interrupted, progressKept = true, resumes = true)
        assertEquals(1 * minute, hold.notBeforeMs - now)
        assertEquals(1, hold.failures)
    }

    @Test
    fun `the connection coming back ends the wait for a try that broke with it`() {
        hold.take(mobileData = false)
        repeat(4) { hold.failed(FileProblem.NoConnection, progressKept = false, resumes = true) }
        assertEquals(HoldStep.WAIT_FOR_TURN, hold.step(wifi))
        hold.networkBack()
        assertEquals(HoldStep.START, hold.step(wifi))
        // The schedule itself goes on: the next failure waits its hour.
        hold.failed(FileProblem.NoConnection, progressKept = false, resumes = true)
        assertEquals(60 * minute, hold.notBeforeMs - now)
    }

    @Test
    fun `a server that said no is not asked sooner because the network changed`() {
        hold.take(mobileData = false)
        hold.failed(FileProblem.ServerAnswered(503), progressKept = false, resumes = true)
        hold.networkBack()
        assertEquals(HoldStep.WAIT_FOR_TURN, hold.step(wifi))
    }

    @Test
    fun `what no waiting fixes is left to the user`() {
        hold.take(mobileData = false)
        for (problem in listOf(
            FileProblem.NotEnoughStorage(700, 100), FileProblem.ChecksumMismatch, FileProblem.CouldNotSave,
            FileProblem.ServerAnswered(404), FileProblem.ServerAnswered(403), FileProblem.Other,
        )) {
            assertFalse(hold.failed(problem, progressKept = false, resumes = true), "$problem")
        }
        assertEquals(0, hold.failures)
        for (problem in listOf(
            FileProblem.NoConnection, FileProblem.Interrupted, FileProblem.EmptyAnswer, FileProblem.Incomplete(1, 2),
            FileProblem.ServerAnswered(500), FileProblem.ServerAnswered(429), FileProblem.ServerAnswered(408),
        )) {
            assertTrue(hold.failed(problem, progressKept = false, resumes = true), "$problem")
        }
    }

    @Test
    fun `mobile data stays allowed for a download that continues, not for one that starts over`() {
        hold.take(mobileData = true)
        hold.failed(FileProblem.Interrupted, progressKept = true, resumes = true)
        assertTrue(hold.mobileData)
        now = hold.notBeforeMs
        assertEquals(HoldStep.START, hold.step(mobile))

        hold.take(mobileData = true)
        hold.failed(FileProblem.Interrupted, progressKept = false, resumes = false)
        assertFalse(hold.mobileData)
        now = hold.notBeforeMs
        assertEquals(HoldStep.WAIT_FOR_WIFI, hold.step(mobile))
        assertEquals(HoldStep.START, hold.step(wifi))
    }

    @Test
    fun `taken in hand again by the user it starts at once`() {
        hold.take(mobileData = false)
        repeat(5) { hold.failed(FileProblem.NoConnection, progressKept = false, resumes = true) }
        hold.take(mobileData = false)
        assertEquals(0, hold.failures)
        assertEquals(HoldStep.START, hold.step(wifi))
    }
}
