package app.amethyst.core

import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.LocalFallback
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.EngineConfig
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertEquals

class LocalFallbackTest {

    private val offline = SttFailure("No connection.", "unknown host", cause = IOException("unknown host"))

    @Test
    fun `no connection, a timeout or a server down sends the recording to the phone`() {
        assertNotNull(LocalFallback.recoverable(offline, TrustLevel.CLOUD, enabled = true))
        assertNotNull(LocalFallback.recoverable(SttFailure("x", "t", cause = SocketTimeoutException()), TrustLevel.ENCLAVE_VERIFIED, true))
        assertNotNull(LocalFallback.recoverable(SttFailure("down", "HTTP 503", statusCode = 503), TrustLevel.CLOUD, true))
    }

    @Test
    fun `a refused key, a switched-off setting or a local engine do not`() {
        assertNull(LocalFallback.recoverable(SttFailure("key", "HTTP 401", statusCode = 401), TrustLevel.CLOUD, true))
        assertNull(LocalFallback.recoverable(offline, TrustLevel.CLOUD, enabled = false))
        assertNull(LocalFallback.recoverable(offline, TrustLevel.ON_DEVICE, true))
        assertNull(LocalFallback.recoverable(SttFailure(SttFailure.NOTHING_RECOGNIZED, "empty"), TrustLevel.CLOUD, true))
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
