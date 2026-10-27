package app.nocturne.core

import app.nocturne.core.capture.MicrophoneRoute
import app.nocturne.core.capture.MicrophoneRouter
import app.nocturne.core.capture.TriggerSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MicrophoneRouterTest {

    private fun route(source: TriggerSource, permission: Boolean = true, service: Boolean = false) =
        MicrophoneRouter.route(source, permission, service)

    @Test
    fun `no permission beats everything else`() {
        for (source in TriggerSource.entries) {
            assertEquals(
                MicrophoneRoute.NEEDS_PERMISSION,
                route(source, permission = false, service = true),
                "source $source",
            )
        }
    }

    @Test
    fun `with the service running every trigger records directly`() {
        for (source in TriggerSource.entries) {
            assertEquals(
                MicrophoneRoute.RECORD_NOW,
                route(source, service = true),
                "source $source",
            )
        }
    }

    @Test
    fun `a bubble tap without the service goes through the proxy activity`() {
        assertEquals(MicrophoneRoute.VIA_PROXY_ACTIVITY, route(TriggerSource.BUBBLE))
    }

    @Test
    fun `an in-app press never opens the proxy window`() {
        assertEquals(
            MicrophoneRoute.START_SERVICE_THEN_RECORD,
            route(TriggerSource.IN_APP),
        )
    }

    @Test
    fun `the proxy activity does not bounce back to itself`() {
        assertEquals(
            MicrophoneRoute.START_SERVICE_THEN_RECORD,
            route(TriggerSource.PROXY_ACTIVITY),
        )
    }

    @Test
    fun `only the bubble may retry through the proxy after a capture failure`() {
        assertTrue(TriggerSource.BUBBLE.mayUseProxyFallback)
        assertFalse(TriggerSource.IN_APP.mayUseProxyFallback)
        assertFalse(TriggerSource.PROXY_ACTIVITY.mayUseProxyFallback)
    }
}
