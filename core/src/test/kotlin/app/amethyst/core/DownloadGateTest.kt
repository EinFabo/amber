package app.amethyst.core

import app.amethyst.core.engine.DownloadGate
import app.amethyst.core.engine.GateDecision
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadGateTest {

    @Test
    fun `an unmetered network downloads at once`() {
        assertEquals(GateDecision.START, DownloadGate.decide(metered = false, mobileDataAllowed = false, waitingForWifi = false, needed = true))
        assertEquals(GateDecision.START, DownloadGate.decide(metered = false, mobileDataAllowed = false, waitingForWifi = true, needed = true))
    }

    @Test
    fun `mobile data only with consent`() {
        assertEquals(GateDecision.START, DownloadGate.decide(metered = true, mobileDataAllowed = true, waitingForWifi = false, needed = true))
        assertEquals(GateDecision.ASK, DownloadGate.decide(metered = true, mobileDataAllowed = false, waitingForWifi = false, needed = true))
    }

    @Test
    fun `waiting for wifi waits on mobile data`() {
        assertEquals(GateDecision.WAIT, DownloadGate.decide(metered = true, mobileDataAllowed = false, waitingForWifi = true, needed = true))
    }

    @Test
    fun `a model already on the phone is not fetched again`() {
        assertEquals(GateDecision.NONE, DownloadGate.decide(metered = false, mobileDataAllowed = true, waitingForWifi = false, needed = false))
        assertEquals(GateDecision.NONE, DownloadGate.decide(metered = true, mobileDataAllowed = false, waitingForWifi = true, needed = false))
    }
}
