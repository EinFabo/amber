package app.amethyst.core

import app.amethyst.core.onboarding.MicrophoneAnswer
import app.amethyst.core.onboarding.MicrophoneAsk
import app.amethyst.core.onboarding.MicrophoneWay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MicrophoneAskTest {

    private fun answer(granted: Boolean = false, before: Boolean? = false, after: Boolean = false, elapsedMs: Long? = 4_000) =
        MicrophoneAsk.answer(granted, rationaleBefore = before, rationaleAfter = after, elapsedMs = elapsedMs)

    @Test
    fun `the first question is android's`() {
        assertEquals(MicrophoneWay.ASK, MicrophoneAsk.way(blocked = false, rationale = false))
    }

    @Test
    fun `an answer with no question shown opens the app info in the same tap`() {
        val answer = answer(elapsedMs = 120)
        assertEquals(MicrophoneAnswer.NO_QUESTION, answer)
        assertTrue(answer.blocked)
        assertTrue(answer.opensAppInfo)
        assertEquals(MicrophoneWay.APP_INFO, MicrophoneAsk.way(answer.blocked, rationale = false))
    }

    @Test
    fun `a question on screen that was refused or dismissed is asked again`() {
        // Tapping outside the dialog or "Don't allow" the first time: the dialog was up for seconds.
        val refused = answer(before = false, after = true)
        assertEquals(MicrophoneAnswer.ASKS_AGAIN, refused)
        assertFalse(refused.blocked)
        assertEquals(MicrophoneWay.ASK, MicrophoneAsk.way(refused.blocked, rationale = true))
        // Dismissed before Android has a reason to give (rationale false before and after): still not a block.
        val dismissed = answer(before = false, after = false)
        assertEquals(MicrophoneAnswer.ASKS_AGAIN, dismissed)
        assertEquals(MicrophoneWay.ASK, MicrophoneAsk.way(dismissed.blocked, rationale = false))
    }

    @Test
    fun `android's don't ask again on screen sends the next allow to the app info, whatever the time`() {
        // Question 2 refused: Android had a reason to give before, none after - it stopped asking.
        // The dialog was on screen, so the user just said no: the app info waits for the next tap.
        for (elapsed in listOf(3_000L, 300L, 80L, null)) {
            val stopped = answer(before = true, after = false, elapsedMs = elapsed)
            assertEquals(MicrophoneAnswer.STOPPED_ASKING, stopped, "after $elapsed ms")
            assertTrue(stopped.blocked)
            assertFalse(stopped.opensAppInfo)
            assertEquals(MicrophoneWay.APP_INFO, MicrophoneAsk.way(stopped.blocked, rationale = false))
        }
    }

    @Test
    fun `after android's don't ask again the next answer comes at once and the app info opens with it`() {
        // A phone where the stop was not seen (an older install): the third "Allow" is answered
        // at once, without a dialog, and that same tap goes on to the app info.
        assertEquals(MicrophoneAnswer.NO_QUESTION, answer(before = false, after = false, elapsedMs = 80))
    }

    @Test
    fun `the limit is 300 ms`() {
        assertEquals(MicrophoneAnswer.NO_QUESTION, answer(elapsedMs = 299))
        assertEquals(MicrophoneAnswer.ASKS_AGAIN, answer(elapsedMs = 300))
    }

    @Test
    fun `an unknown time or rationale never blocks, and granting clears it`() {
        assertEquals(MicrophoneAnswer.ASKS_AGAIN, answer(elapsedMs = null))
        assertEquals(MicrophoneAnswer.ASKS_AGAIN, answer(before = null, after = false))
        assertEquals(MicrophoneAnswer.ASKS_AGAIN, answer(granted = true, before = true, after = false, elapsedMs = 10))
        // Granted, then taken away in Android's settings: Android's question comes first again.
        assertEquals(MicrophoneWay.ASK, MicrophoneAsk.way(answer(granted = true, elapsedMs = 10).blocked, rationale = false))
    }

    @Test
    fun `a block with a reason to give is not a block`() {
        // Android offers its question again (a reset permission): ask.
        assertEquals(MicrophoneWay.ASK, MicrophoneAsk.way(blocked = true, rationale = true))
        assertEquals(MicrophoneAnswer.ASKS_AGAIN, answer(before = true, after = true, elapsedMs = 80))
    }
}
