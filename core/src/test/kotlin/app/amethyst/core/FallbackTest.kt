package app.amethyst.core

import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.EngineTrust
import app.amethyst.core.engine.Fallback
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.FallbackMode
import app.amethyst.core.settings.FallbackSettings
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FallbackTest {

    private val offline = SttFailure("No connection.", "unknown host", cause = IOException("unknown host"))
    private val trustOf: (EngineConfig) -> TrustLevel = { EngineTrust.forDisplay(it, onDeviceSpeech = true) }

    private val tinfoil = EngineConfig.fromPreset(EnginePresets.TINFOIL)
    private val groq = EngineConfig.fromPreset(EnginePresets.GROQ)
    private val openai = EngineConfig.fromPreset(EnginePresets.OPENAI)
    private val whisper = EngineConfig.fromPreset(EnginePresets.LOCAL_WHISPER)
    private val parakeet = EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET)
    private val all = listOf(tinfoil, groq, openai, whisper, parakeet)

    @Test
    fun `no connection, a timeout or a server down may be recovered from`() {
        assertNotNull(Fallback.recoverable(offline, TrustLevel.CLOUD, FallbackMode.AUTOMATIC))
        assertNotNull(Fallback.recoverable(SttFailure("x", "t", cause = SocketTimeoutException()), TrustLevel.ENCLAVE_VERIFIED, FallbackMode.ENGINE))
        assertNotNull(Fallback.recoverable(SttFailure("down", "HTTP 503", statusCode = 503), TrustLevel.CLOUD, FallbackMode.AUTOMATIC))
    }

    @Test
    fun `a refused key, a switched-off fallback or a local engine are not`() {
        assertNull(Fallback.recoverable(SttFailure("key", "HTTP 401", statusCode = 401), TrustLevel.CLOUD, FallbackMode.AUTOMATIC))
        assertNull(Fallback.recoverable(offline, TrustLevel.CLOUD, FallbackMode.OFF))
        assertNull(Fallback.recoverable(offline, TrustLevel.ON_DEVICE, FallbackMode.AUTOMATIC))
        assertNull(Fallback.recoverable(SttFailure(SttFailure.NOTHING_RECOGNIZED, "empty"), TrustLevel.CLOUD, FallbackMode.AUTOMATIC))
    }

    @Test
    fun `automatic takes the local models, parakeet first`() {
        val picked = Fallback.candidates(FallbackSettings(FallbackMode.AUTOMATIC), all, groq, trustOf)
        assertEquals(listOf(parakeet.id, whisper.id), picked.map { it.id })
    }

    @Test
    fun `off takes nothing`() {
        assertEquals(emptyList(), Fallback.candidates(FallbackSettings(FallbackMode.OFF), all, groq, trustOf))
    }

    @Test
    fun `a chosen engine is used - another server is fine behind a server`() {
        val picked = Fallback.candidates(FallbackSettings(FallbackMode.ENGINE, openai.id), all, groq, trustOf)
        assertEquals(listOf(openai.id), picked.map { it.id })
    }

    @Test
    fun `an enclave engine never falls back to a plain server, even when one was chosen`() {
        assertEquals(listOf(whisper.id, parakeet.id), Fallback.choices(all, tinfoil, trustOf).map { it.id })
        assertEquals(emptyList(), Fallback.candidates(FallbackSettings(FallbackMode.ENGINE, groq.id), all, tinfoil, trustOf))
    }

    @Test
    fun `the text is labelled no better than either trip it may have made`() {
        // Groq timed out after the upload, OpenAI recognized it: cloud either way.
        val timeout = SttFailure("x", "t", cause = SocketTimeoutException("timeout"))
        assertEquals(TrustLevel.CLOUD, Fallback.trustAfter(timeout, TrustLevel.CLOUD, TrustLevel.CLOUD))
        // Tinfoil never connected, Whisper recognized it: on the phone.
        val neverConnected = SttFailure("x", "t", cause = java.net.UnknownHostException("inference.tinfoil.sh"))
        assertEquals(TrustLevel.ON_DEVICE, Fallback.trustAfter(neverConnected, TrustLevel.ENCLAVE_VERIFIED))
    }
}
