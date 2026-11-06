package app.amethyst.core

import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.EngineTrust
import app.amethyst.core.engine.Fallback
import app.amethyst.core.engine.ReadyFallback
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.FallbackMode
import app.amethyst.core.settings.FallbackSettings
import app.amethyst.core.text.Transcript
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReadyFallbackTest {

    private val trustOf: (EngineConfig) -> TrustLevel = { EngineTrust.forDisplay(it, onDeviceSpeech = true) }

    private val tinfoil = EngineConfig.fromPreset(EnginePresets.TINFOIL)
    private val groq = EngineConfig.fromPreset(EnginePresets.GROQ)
    private val whisper = EngineConfig.fromPreset(EnginePresets.LOCAL_WHISPER)
    private val parakeet = EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET)

    private fun settings(fallback: FallbackSettings) =
        AmethystSettings.defaults(TEST_PHONE).copy(engines = listOf(tinfoil, groq, whisper, parakeet), fallback = fallback)

    private val prepared = FallbackSettings(FallbackMode.AUTOMATIC, prepare = true)

    @Test
    fun `preparing is off by default`() {
        assertFalse(AmethystSettings.defaults(TEST_PHONE).fallback.prepare)
        assertNull(ReadyFallback.engineToPrepare(settings(FallbackSettings()), tinfoil, trustOf) { true })
    }

    @Test
    fun `the first ready model on the phone is prepared`() {
        assertEquals(parakeet.id, ReadyFallback.engineToPrepare(settings(prepared), tinfoil, trustOf) { true }?.id)
        assertEquals(whisper.id, ReadyFallback.engineToPrepare(settings(prepared), tinfoil, trustOf) { it.id != parakeet.id }?.id)
        assertNull(ReadyFallback.engineToPrepare(settings(prepared), tinfoil, trustOf) { false }, "nothing downloaded")
    }

    @Test
    fun `nothing is prepared for a recognition on the phone, a fallback that is off or one on another server`() {
        assertNull(ReadyFallback.engineToPrepare(settings(prepared), parakeet, trustOf) { true })
        assertNull(ReadyFallback.engineToPrepare(settings(FallbackSettings(FallbackMode.OFF, prepare = true)), tinfoil, trustOf) { true })
        val toGroq = FallbackSettings(FallbackMode.ENGINE, groq.id, prepare = true)
        assertNull(ReadyFallback.engineToPrepare(settings(toGroq).copy(engines = listOf(groq, EngineConfig.fromPreset(EnginePresets.OPENAI))), EngineConfig.fromPreset(EnginePresets.OPENAI), trustOf) { true })
        val toWhisper = FallbackSettings(FallbackMode.ENGINE, whisper.id, prepare = true)
        assertEquals(whisper.id, ReadyFallback.engineToPrepare(settings(toWhisper), tinfoil, trustOf) { true }?.id)
    }

    @Test
    fun `the switch is shown only for a fallback on the phone`() {
        val engines = listOf(tinfoil, groq, whisper, parakeet)
        assertTrue(ReadyFallback.preparable(FallbackSettings(FallbackMode.AUTOMATIC), engines))
        assertTrue(ReadyFallback.preparable(FallbackSettings(FallbackMode.ENGINE, parakeet.id), engines))
        assertFalse(ReadyFallback.preparable(FallbackSettings(FallbackMode.ENGINE, groq.id), engines))
        assertFalse(ReadyFallback.preparable(FallbackSettings(FallbackMode.ENGINE, "gone"), engines))
        assertFalse(ReadyFallback.preparable(FallbackSettings(FallbackMode.OFF), engines))
    }

    @Test
    fun `a server gets twice its usual time, at least four seconds`() {
        assertEquals(4_000, ReadyFallback.deadlineMs(500))
        assertEquals(4_000, ReadyFallback.deadlineMs(2_000))
        assertEquals(7_000, ReadyFallback.deadlineMs(3_500))
    }

    @Test
    fun `a slow server's text is only as trusted as the server`() {
        assertEquals(TrustLevel.CLOUD, Fallback.trustAfterSlowServer(TrustLevel.CLOUD, TrustLevel.ON_DEVICE))
        assertEquals(TrustLevel.ENCLAVE_VERIFIED, Fallback.trustAfterSlowServer(TrustLevel.ENCLAVE_VERIFIED, TrustLevel.ON_DEVICE))
    }

    // The race.

    private fun text(t: String, engine: String) = Transcript(t, engine, if (engine == "local") TrustLevel.ON_DEVICE else TrustLevel.CLOUD, 0)
    private val serverText = Result.success(text("Okay, danke.", "server"))
    private val localText = Result.success(text("okay danke", "local"))
    private val offline = SttFailure("No connection.", "unknown host", cause = IOException("unknown host"))
    private val refusedKey = SttFailure("The API key was rejected.", "HTTP 401", statusCode = 401)
    private val recoverable: (Throwable?) -> SttFailure? = { Fallback.recoverable(it, TrustLevel.CLOUD, FallbackMode.AUTOMATIC) }

    @Test
    fun `the server's answer wins`() = runTest {
        val local = CompletableDeferred(localText)
        val outcome = ReadyFallback.race({ delay(1_000); serverText }, local, 4_000, recoverable)
        assertEquals(serverText, assertIs<ReadyFallback.Outcome.Server>(outcome).result)
    }

    @Test
    fun `a server that cannot be reached gives way to the text from the phone at once`() = runTest {
        val local = async { delay(300); localText }
        val outcome = ReadyFallback.race({ delay(100); Result.failure(offline) }, local, 4_000, recoverable)
        val taken = assertIs<ReadyFallback.Outcome.Local>(outcome)
        assertEquals("okay danke", taken.transcript.text)
        assertSame(offline, taken.serverFailure)
        assertEquals(300, currentTime, "waits only for the local text")
    }

    @Test
    fun `a refused key stands`() = runTest {
        val outcome = ReadyFallback.race({ Result.failure(refusedKey) }, CompletableDeferred(localText), 4_000, recoverable)
        assertSame(refusedKey, assertIs<ReadyFallback.Outcome.Server>(outcome).result.exceptionOrNull())
    }

    @Test
    fun `a hanging server gives way at the deadline`() = runTest {
        var cancelled = false
        val outcome = ReadyFallback.race(
            server = { try { awaitCancellation() } finally { cancelled = true } },
            local = CompletableDeferred(localText),
            deadlineMs = 4_000,
            recoverable = recoverable,
        )
        assertTrue(assertIs<ReadyFallback.Outcome.Local>(outcome).serverTooSlow)
        assertEquals(4_000, currentTime)
        assertTrue(cancelled, "the request is cancelled")
    }

    @Test
    fun `a local text later than the deadline still beats a hanging server`() = runTest {
        val local = async { delay(6_000); localText }
        val outcome = ReadyFallback.race({ awaitCancellation() }, local, 4_000, recoverable)
        assertTrue(assertIs<ReadyFallback.Outcome.Local>(outcome).serverTooSlow)
        assertEquals(6_000, currentTime)
    }

    @Test
    fun `a server answering after the deadline but before the local text wins`() = runTest {
        val local = async { delay(9_000); localText }
        val outcome = ReadyFallback.race({ delay(5_000); serverText }, local, 4_000, recoverable)
        assertEquals(serverText, assertIs<ReadyFallback.Outcome.Server>(outcome).result)
        assertEquals(5_000, currentTime)
    }

    @Test
    fun `a failed local text leaves the server to finish`() = runTest {
        val failedLocal = CompletableDeferred<Result<Transcript>>(Result.failure(SttFailure("x", "model gone")))
        val outcome = ReadyFallback.race({ delay(20_000); serverText }, failedLocal, 4_000, recoverable)
        assertEquals(serverText, assertIs<ReadyFallback.Outcome.Server>(outcome).result)
        assertEquals(20_000, currentTime)
    }

    @Test
    fun `when both fail the server's failure is what is told`() = runTest {
        val failedLocal = CompletableDeferred<Result<Transcript>>(Result.failure(SttFailure("x", "model gone")))
        val outcome = ReadyFallback.race({ Result.failure(offline) }, failedLocal, 4_000, recoverable)
        assertSame(offline, assertIs<ReadyFallback.Outcome.Server>(outcome).result.exceptionOrNull())
    }
}
