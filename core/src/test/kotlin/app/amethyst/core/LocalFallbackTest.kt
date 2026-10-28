package app.amethyst.core

import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.LocalFallback
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.EngineConfig
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalFallbackTest {

    private val offline = SttFailure("No connection.", "unknown host", cause = IOException("unknown host"))

    @Test
    fun `no connection, a timeout or a server down sends the recording to the phone`() {
        assertTrue(LocalFallback.applies(offline, TrustLevel.CLOUD, enabled = true))
        assertTrue(LocalFallback.applies(SttFailure("x", "t", cause = SocketTimeoutException()), TrustLevel.ENCLAVE_VERIFIED, true))
        assertTrue(LocalFallback.applies(SttFailure("down", "HTTP 503", statusCode = 503), TrustLevel.CLOUD, true))
    }

    @Test
    fun `a refused key, a switched-off setting or a local engine do not`() {
        assertFalse(LocalFallback.applies(SttFailure("key", "HTTP 401", statusCode = 401), TrustLevel.CLOUD, true))
        assertFalse(LocalFallback.applies(offline, TrustLevel.CLOUD, enabled = false))
        assertFalse(LocalFallback.applies(offline, TrustLevel.ON_DEVICE, true))
        assertFalse(LocalFallback.applies(SttFailure(SttFailure.NOTHING_RECOGNIZED, "empty"), TrustLevel.CLOUD, true))
    }

    @Test
    fun `only local engines qualify, parakeet first`() {
        val engines = listOf(EnginePresets.TINFOIL, EnginePresets.LOCAL_WHISPER, EnginePresets.ANDROID_ON_DEVICE, EnginePresets.LOCAL_PARAKEET)
            .map { EngineConfig.fromPreset(it) }
        assertEquals(
            listOf(EnginePresets.LOCAL_PARAKEET.id, EnginePresets.LOCAL_WHISPER.id),
            LocalFallback.candidates(engines).map { it.id },
        )
    }
}
