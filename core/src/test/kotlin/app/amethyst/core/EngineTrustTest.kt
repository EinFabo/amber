package app.amethyst.core

import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.EngineTrust
import app.amethyst.core.engine.LocalFallback
import app.amethyst.core.engine.NotSentException
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.EngineConfig
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineTrustTest {

    private fun failure(cause: Throwable? = null, status: Int? = null) = SttFailure("x", "t", statusCode = status, cause = cause)

    @Test
    fun `a text recognized on the phone keeps the server's label when the upload may have happened`() {
        // The server answered (5xx) or went quiet after the upload (read timeout): it may have the recording.
        assertEquals(TrustLevel.CLOUD, LocalFallback.trustAfter(failure(status = 503), TrustLevel.CLOUD))
        assertEquals(TrustLevel.CLOUD, LocalFallback.trustAfter(failure(SocketTimeoutException("timeout")), TrustLevel.CLOUD))
        assertEquals(TrustLevel.CLOUD, LocalFallback.trustAfter(failure(IOException("unexpected end of stream")), TrustLevel.CLOUD))
        assertEquals(
            TrustLevel.ENCLAVE_VERIFIED,
            LocalFallback.trustAfter(failure(IOException("canceled")), TrustLevel.ENCLAVE_VERIFIED),
        )
    }

    @Test
    fun `only a failure to connect at all proves the voice stayed on the phone`() {
        for (cause in listOf(
            UnknownHostException("api.groq.com"),
            ConnectException("Connection refused"),
            SocketTimeoutException("failed to connect to api.groq.com/1.2.3.4 (port 443) after 10000ms"),
            NotSentException("Attestation unavailable"),
            IOException("wrapped", UnknownHostException("inner")),
        )) {
            assertFalse(failure(cause).recordingMayHaveLeft, cause.toString())
            assertEquals(TrustLevel.ON_DEVICE, LocalFallback.trustAfter(failure(cause), TrustLevel.CLOUD))
        }
    }

    @Test
    fun `the Android recognizer is on device only where the phone has an on-device recognizer`() {
        val android = EngineConfig.fromPreset(EnginePresets.ANDROID_ON_DEVICE)
        assertEquals(TrustLevel.ON_DEVICE, EngineTrust.forDisplay(android, onDeviceSpeech = true))
        assertEquals(TrustLevel.CLOUD, EngineTrust.forDisplay(android, onDeviceSpeech = false))
        assertEquals(TrustLevel.ON_DEVICE, EngineTrust.forDisplay(EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET), false))
        assertEquals(TrustLevel.ENCLAVE_VERIFIED, EngineTrust.forDisplay(EngineConfig.fromPreset(EnginePresets.TINFOIL), false))
    }

    @Test
    fun `plain http is called unencrypted, https is not`() {
        val own = EngineConfig.fromPreset(EnginePresets.SELF_HOSTED)
        assertTrue(EngineTrust.unencrypted(own.copy(baseUrl = "http://192.168.1.20:8000/v1")))
        assertTrue(EngineTrust.unencrypted(own.copy(baseUrl = " HTTP://nas:8000")))
        assertFalse(EngineTrust.unencrypted(own.copy(baseUrl = "https://nas.ts.net/v1")))
        assertFalse(EngineTrust.unencrypted(EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET)))
    }
}
