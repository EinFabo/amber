package app.amethyst.core

import app.amethyst.core.onboarding.MicrophoneAsk
import app.amethyst.core.onboarding.MicrophoneWay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MicrophoneAskTest {

    @Test
    fun `the first question is android's`() {
        assertEquals(MicrophoneWay.ASK, MicrophoneAsk.way(blocked = false, rationale = false))
    }

    @Test
    fun `an answer with no question shown means android will not ask - the app info next`() {
        val blocked = MicrophoneAsk.blocked(granted = false, rationale = false, elapsedMs = 120)
        assertTrue(blocked)
        assertEquals(MicrophoneWay.APP_INFO, MicrophoneAsk.way(blocked, rationale = false))
    }

    @Test
    fun `a question on screen that was refused or dismissed is asked again`() {
        // Tapping outside the dialog or "Don't allow": the dialog was up for seconds.
        val blocked = MicrophoneAsk.blocked(granted = false, rationale = true, elapsedMs = 4_000)
        assertFalse(blocked)
        assertEquals(MicrophoneWay.ASK, MicrophoneAsk.way(blocked, rationale = true))
        // Dismissed before Android has a reason to give (rationale still false): still not a block.
        val dismissed = MicrophoneAsk.blocked(granted = false, rationale = false, elapsedMs = 4_000)
        assertFalse(dismissed)
        assertEquals(MicrophoneWay.ASK, MicrophoneAsk.way(dismissed, rationale = false))
    }

    @Test
    fun `after android's don't ask again the next answer comes at once and the app info follows`() {
        // Question 1 refused, question 2 refused with "Don't ask again": both on screen.
        assertFalse(MicrophoneAsk.blocked(granted = false, rationale = true, elapsedMs = 3_000))
        assertFalse(MicrophoneAsk.blocked(granted = false, rationale = false, elapsedMs = 3_000))
        // The third "Allow" is answered at once, without a dialog.
        assertTrue(MicrophoneAsk.blocked(granted = false, rationale = false, elapsedMs = 80))
    }

    @Test
    fun `the limit is 300 ms`() {
        assertTrue(MicrophoneAsk.blocked(granted = false, rationale = false, elapsedMs = 299))
        assertFalse(MicrophoneAsk.blocked(granted = false, rationale = false, elapsedMs = 300))
    }

    @Test
    fun `an unknown time never blocks, and granting clears it`() {
        assertFalse(MicrophoneAsk.blocked(granted = false, rationale = false, elapsedMs = null))
        assertFalse(MicrophoneAsk.blocked(granted = true, rationale = false, elapsedMs = 10))
        // Granted, then taken away in Android's settings: Android's question comes first again.
        assertEquals(MicrophoneWay.ASK, MicrophoneAsk.way(MicrophoneAsk.blocked(granted = true, rationale = false, elapsedMs = 10), rationale = false))
    }

    @Test
    fun `a block with a reason to give is not a block`() {
        // Android offers its question again (a reset permission): ask.
        assertEquals(MicrophoneWay.ASK, MicrophoneAsk.way(blocked = true, rationale = true))
    }
}
