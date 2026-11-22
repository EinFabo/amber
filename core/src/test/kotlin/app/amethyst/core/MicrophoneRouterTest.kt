package app.amethyst.core

import app.amethyst.core.capture.MicrophoneRoute
import app.amethyst.core.capture.MicrophoneRouter
import app.amethyst.core.capture.TriggerSource
import app.amethyst.core.engine.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MicrophoneRouterTest {

    private fun route(source: TriggerSource, permission: Boolean = true, sdk: Int = 35, connected: Boolean = true) =
        MicrophoneRouter.route(source, permission, sdk, connected).route

    @Test
    fun `no permission beats everything else`() {
        for (source in TriggerSource.entries) {
            assertEquals(MicrophoneRoute.NEEDS_PERMISSION, route(source, permission = false), "source $source")
        }
    }

    @Test
    fun `from Android 11 a bubble tap records directly, with no foreground service running`() {
        // The accessibility binding carries while-in-use microphone access from Android 11.
        for (sdk in listOf(30, 31, 34, 35, 36, 37)) {
            assertEquals(MicrophoneRoute.DIRECT, route(TriggerSource.BUBBLE, sdk = sdk), "API $sdk")
        }
    }

    @Test
    fun `on Android 9 and 10 a bubble tap goes through the proxy activity, as before`() {
        // Fabi's decision for the store review: the proxy stays there; nothing proves the
        // binding gives the process (or Android's speech recognition) the microphone there.
        for (sdk in listOf(28, 29)) {
            assertEquals(MicrophoneRoute.VIA_PROXY_ACTIVITY, route(TriggerSource.BUBBLE, sdk = sdk), "API $sdk")
        }
    }

    @Test
    fun `the reason names the Android version and why that route`() {
        val eleven = MicrophoneRouter.route(TriggerSource.BUBBLE, hasPermission = true, sdk = 30, accessibilityConnected = true)
        assertTrue("while-in-use" in eleven.why && "30" in eleven.why, eleven.why)
        val nine = MicrophoneRouter.route(TriggerSource.BUBBLE, hasPermission = true, sdk = 28, accessibilityConnected = true)
        assertTrue("before Android 11" in nine.why && "28" in nine.why, nine.why)
    }

    @Test
    fun `without its accessibility service the bubble goes through the proxy activity first`() {
        for (sdk in listOf(28, 30, 36)) {
            assertEquals(MicrophoneRoute.VIA_PROXY_ACTIVITY, route(TriggerSource.BUBBLE, sdk = sdk, connected = false), "API $sdk")
        }
    }

    @Test
    fun `nothing but the bubble without its service or before Android 11 routes to the proxy first`() {
        for (source in TriggerSource.entries) {
            for (sdk in listOf(28, 29, 30, 36)) {
                for (connected in listOf(true, false)) {
                    val first = route(source, sdk = sdk, connected = connected)
                    val expected = source == TriggerSource.BUBBLE && (!connected || sdk < 30)
                    assertEquals(expected, first == MicrophoneRoute.VIA_PROXY_ACTIVITY, "$source, API $sdk, connected $connected")
                }
            }
        }
    }

    @Test
    fun `an in-app press never opens the proxy window`() {
        for (connected in listOf(true, false)) {
            assertEquals(MicrophoneRoute.DIRECT, route(TriggerSource.IN_APP, connected = connected))
        }
    }

    @Test
    fun `the proxy activity does not bounce back to itself`() {
        for (connected in listOf(true, false)) {
            assertEquals(MicrophoneRoute.DIRECT, route(TriggerSource.PROXY_ACTIVITY, connected = connected))
        }
    }

    @Test
    fun `only the bubble may retry through the proxy after a capture failure`() {
        assertTrue(TriggerSource.BUBBLE.mayUseProxyFallback)
        assertFalse(TriggerSource.IN_APP.mayUseProxyFallback)
        assertFalse(TriggerSource.PROXY_ACTIVITY.mayUseProxyFallback)
    }

    @Test
    fun `only the bubble's direct start is watched for silence`() {
        assertEquals(MicrophoneRouter.PROBE_MS, MicrophoneRouter.startProbeMs(TriggerSource.BUBBLE))
        assertEquals(0, MicrophoneRouter.startProbeMs(TriggerSource.IN_APP))
        assertEquals(0, MicrophoneRouter.startProbeMs(TriggerSource.PROXY_ACTIVITY))
    }

    @Test
    fun `Android's speech recognition that could not open the microphone retries through the proxy`() {
        for (problem in listOf(Problem.MicrophonePermissionMissing, Problem.MicrophoneUnreadable)) {
            assertTrue(MicrophoneRouter.proxyAfterRecognizerFailure(TriggerSource.BUBBLE, opened = false, endedByCall = false, problem = problem))
        }
    }

    @Test
    fun `Android's speech recognition keeps what it heard once the microphone was open`() {
        assertFalse(MicrophoneRouter.proxyAfterRecognizerFailure(TriggerSource.BUBBLE, opened = true, endedByCall = false, problem = Problem.MicrophoneUnreadable))
        assertFalse(MicrophoneRouter.proxyAfterRecognizerFailure(TriggerSource.BUBBLE, opened = false, endedByCall = true, problem = Problem.MicrophoneUnreadable))
    }

    @Test
    fun `other failures of Android's speech recognition are said, not retried`() {
        for (problem in listOf(Problem.NoSpeechHeard, Problem.RecognizerOffline, Problem.RecognitionFailed, Problem.LanguageNotInstalled, null)) {
            assertFalse(MicrophoneRouter.proxyAfterRecognizerFailure(TriggerSource.BUBBLE, opened = false, endedByCall = false, problem = problem), "$problem")
        }
        // In front already, the proxy would change nothing.
        for (source in listOf(TriggerSource.IN_APP, TriggerSource.PROXY_ACTIVITY)) {
            assertFalse(MicrophoneRouter.proxyAfterRecognizerFailure(source, opened = false, endedByCall = false, problem = Problem.MicrophonePermissionMissing))
        }
    }
}
