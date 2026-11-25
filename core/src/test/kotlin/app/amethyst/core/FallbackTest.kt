package app.amethyst.core

import app.amethyst.core.engine.Problem
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.EngineTrust
import app.amethyst.core.engine.Fallback
import app.amethyst.core.engine.FallbackNotice
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

    private val offline = SttFailure(Problem.NoConnection("test"), "unknown host", cause = IOException("unknown host"))
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
        assertNotNull(Fallback.recoverable(SttFailure(Problem.RecognitionFailed, "t", cause = SocketTimeoutException()), TrustLevel.ENCLAVE_VERIFIED, FallbackMode.ENGINE))
        assertNotNull(Fallback.recoverable(SttFailure(Problem.ServiceDown(503), "HTTP 503", statusCode = 503), TrustLevel.CLOUD, FallbackMode.AUTOMATIC))
    }

    @Test
    fun `a refused key, a switched-off fallback or a local engine are not`() {
        assertNull(Fallback.recoverable(SttFailure(Problem.KeyRejected("Groq"), "HTTP 401", statusCode = 401), TrustLevel.CLOUD, FallbackMode.AUTOMATIC))
        assertNull(Fallback.recoverable(offline, TrustLevel.CLOUD, FallbackMode.OFF))
        assertNull(Fallback.recoverable(offline, TrustLevel.ON_DEVICE, FallbackMode.AUTOMATIC))
        assertNull(Fallback.recoverable(SttFailure(SttFailure.NOTHING_RECOGNIZED, "empty"), TrustLevel.CLOUD, FallbackMode.AUTOMATIC))
    }

    private val outOfMemory = SttFailure(Problem.OutOfMemory, "parakeet: out of memory")
    private val modelFailed = SttFailure(Problem.LocalModelFailed, "whisper transcribe: whisper_full returned -7")
    private val android = EngineConfig.fromPreset(EnginePresets.ANDROID_ON_DEVICE)

    @Test
    fun `a model on the phone that ran out of memory or failed falls back to another engine on the phone`() {
        // Parakeet ran out of memory, Whisper is the fallback - automatic or chosen.
        val automatic = Fallback.candidates(FallbackSettings(FallbackMode.AUTOMATIC), all, parakeet, trustOf)
        assertEquals(listOf(whisper.id), automatic.map { it.id })
        assertNotNull(Fallback.recoverable(outOfMemory, TrustLevel.ON_DEVICE, FallbackMode.AUTOMATIC, automatic))
        val chosen = Fallback.candidates(FallbackSettings(FallbackMode.ENGINE, whisper.id), all, parakeet, trustOf)
        assertNotNull(Fallback.recoverable(outOfMemory, TrustLevel.ON_DEVICE, FallbackMode.ENGINE, chosen))
        // Whisper failed, Android's speech recognition on the phone was chosen.
        val withAndroid = all + android
        val toAndroid = Fallback.candidates(FallbackSettings(FallbackMode.ENGINE, android.id), withAndroid, whisper, trustOf)
        assertEquals(listOf(android.id), toAndroid.map { it.id })
        assertNotNull(Fallback.recoverable(modelFailed, TrustLevel.ON_DEVICE, FallbackMode.ENGINE, toAndroid))
    }

    @Test
    fun `a model on the phone does not fall back to itself, to nothing, or for a failure another engine would meet too`() {
        // The fallback chosen is the very engine that failed: nothing else to try.
        val itself = Fallback.candidates(FallbackSettings(FallbackMode.ENGINE, parakeet.id), all, parakeet, trustOf)
        assertEquals(emptyList(), itself)
        assertNull(Fallback.recoverable(outOfMemory, TrustLevel.ON_DEVICE, FallbackMode.ENGINE, itself))
        // Only Parakeet on the phone: automatic has nothing to take over.
        val alone = Fallback.candidates(FallbackSettings(FallbackMode.AUTOMATIC), listOf(groq, parakeet), parakeet, trustOf)
        assertNull(Fallback.recoverable(outOfMemory, TrustLevel.ON_DEVICE, FallbackMode.AUTOMATIC, alone))
        // Switched off, or nothing heard: the failure stands.
        assertNull(Fallback.recoverable(outOfMemory, TrustLevel.ON_DEVICE, FallbackMode.OFF, listOf(whisper)))
        assertNull(Fallback.recoverable(SttFailure(SttFailure.NOTHING_RECOGNIZED, "empty"), TrustLevel.ON_DEVICE, FallbackMode.AUTOMATIC, listOf(whisper)))
        assertNull(Fallback.recoverable(offline, TrustLevel.ON_DEVICE, FallbackMode.AUTOMATIC, listOf(whisper)))
    }

    /** A 3 GB phone as Android reports it (A20e), and an 8 GB one. */
    private val threeGb = 2_800L
    private val eightGb = 7_600L

    @Test
    fun `after running out of memory a fallback model too big for the phone is not loaded`() {
        // Whisper ran out of memory on a 3 GB phone: Parakeet (about 700 MB) would only get the app killed.
        val toParakeet = Fallback.candidates(FallbackSettings(FallbackMode.AUTOMATIC), all, whisper, trustOf)
        assertEquals(listOf(parakeet.id), toParakeet.map { it.id })
        val fitting = Fallback.fitAfter(Problem.OutOfMemory, toParakeet, threeGb)
        assertEquals(emptyList(), fitting)
        assertNull(Fallback.recoverable(outOfMemory, TrustLevel.ON_DEVICE, FallbackMode.AUTOMATIC, fitting))
        // On an 8 GB phone it may.
        assertEquals(listOf(parakeet.id), Fallback.fitAfter(Problem.OutOfMemory, toParakeet, eightGb).map { it.id })
        // A model that failed for another reason: memory says nothing, Parakeet stays.
        assertEquals(listOf(parakeet.id), Fallback.fitAfter(Problem.LocalModelFailed, toParakeet, threeGb).map { it.id })
        // Memory not known: nothing left out.
        assertEquals(listOf(parakeet.id), Fallback.fitAfter(Problem.OutOfMemory, toParakeet, 0).map { it.id })
    }

    @Test
    fun `after running out of memory the small Whisper models stay, the large one and Parakeet go on a 3 GB phone`() {
        val small = whisper.copy(id = "w74", modelId = "futo-multilingual-74")
        val medium = whisper.copy(id = "w244", modelId = "futo-multilingual-244")
        val large = whisper.copy(id = "wl", modelId = "acft-whisper-large-v3-turbo-q8_0")
        // A file the user picked: its size is not known here, so it is not left out.
        val picked = whisper.copy(id = "wp", modelId = "my-own-model")
        val kept = Fallback.fitAfter(Problem.OutOfMemory, listOf(parakeet, large, medium, small, picked), threeGb)
        assertEquals(listOf("w244", "w74", "wp"), kept.map { it.id })
    }

    @Test
    fun `the notice says the model on the phone failed, not the connection`() {
        assertEquals(FallbackNotice.ModelOnPhoneFailed, FallbackNotice.afterFailure(outOfMemory, TrustLevel.ON_DEVICE, recognizedOnPhone = true))
        assertEquals(FallbackNotice.ModelOnPhoneFailed, FallbackNotice.afterFailure(modelFailed, TrustLevel.ON_DEVICE, recognizedOnPhone = true))
        assertEquals(TrustLevel.ON_DEVICE, Fallback.trustAfter(outOfMemory, TrustLevel.ON_DEVICE))
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
        val timeout = SttFailure(Problem.RecognitionFailed, "t", cause = SocketTimeoutException("timeout"))
        assertEquals(TrustLevel.CLOUD, Fallback.trustAfter(timeout, TrustLevel.CLOUD, TrustLevel.CLOUD))
        // Tinfoil never connected, Whisper recognized it: on the phone.
        val neverConnected = SttFailure(Problem.RecognitionFailed, "t", cause = java.net.UnknownHostException("inference.tinfoil.sh"))
        assertEquals(TrustLevel.ON_DEVICE, Fallback.trustAfter(neverConnected, TrustLevel.ENCLAVE_VERIFIED))
    }
}
