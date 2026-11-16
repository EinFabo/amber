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
        assertEquals(MicrophoneWay.ASK, MicrophoneAsk.way(refusedBefore = false, rationale = false))
    }

    @Test
    fun `after one refusal android asks once more`() {
        assertEquals(MicrophoneWay.ASK, MicrophoneAsk.way(refusedBefore = true, rationale = true))
    }

    @Test
    fun `after the second refusal only the app info can grant it`() {
        assertEquals(MicrophoneWay.APP_INFO, MicrophoneAsk.way(refusedBefore = true, rationale = false))
    }

    @Test
    fun `a refusal is remembered until the microphone is granted`() {
        assertTrue(MicrophoneAsk.refused(granted = false))
        assertFalse(MicrophoneAsk.refused(granted = true))
        // Granted, then taken away in Android's settings: Android's question comes first again.
        assertEquals(MicrophoneWay.ASK, MicrophoneAsk.way(refusedBefore = MicrophoneAsk.refused(granted = true), rationale = false))
    }
}
