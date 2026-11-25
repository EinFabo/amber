package app.amethyst.core

import app.amethyst.core.onboarding.ServiceHealth
import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceHealthTest {

    @Test
    fun `a switch that is off is off, connected or not`() {
        assertEquals(ServiceHealth.OFF, ServiceHealth.of(switchOn = false, connected = false, msSinceAppStart = 60_000))
        assertEquals(ServiceHealth.OFF, ServiceHealth.of(switchOn = false, connected = true, msSinceAppStart = 0))
    }

    @Test
    fun `on and connected is running`() {
        assertEquals(ServiceHealth.RUNNING, ServiceHealth.of(switchOn = true, connected = true, msSinceAppStart = 0))
        assertEquals(ServiceHealth.RUNNING, ServiceHealth.of(switchOn = true, connected = true, msSinceAppStart = 60_000))
    }

    @Test
    fun `on and not connected is starting for the first 2000 ms, then not running`() {
        assertEquals(ServiceHealth.STARTING, ServiceHealth.of(switchOn = true, connected = false, msSinceAppStart = 0))
        assertEquals(ServiceHealth.STARTING, ServiceHealth.of(switchOn = true, connected = false, msSinceAppStart = 2_000))
        assertEquals(ServiceHealth.NOT_RUNNING, ServiceHealth.of(switchOn = true, connected = false, msSinceAppStart = 2_001))
    }
}
