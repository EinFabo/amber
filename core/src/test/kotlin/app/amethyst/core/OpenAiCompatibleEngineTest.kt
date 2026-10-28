package app.amethyst.core

import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.engine.EngineCapabilities
import app.amethyst.core.engine.OpenAiCompatibleEngine
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.engine.TranscribeOptions
import app.amethyst.core.net.HttpBody
import app.amethyst.core.net.MultipartPart
import app.amethyst.core.privacy.AttestationResult
import app.amethyst.core.privacy.AttestationVerifier
import app.amethyst.core.privacy.TrustLevel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAiCompatibleEngineTest {

    private val audio = PcmAudio(ShortArray(16_000) { (it % 500).toShort() })

    private fun engine(
        transport: FakeHttpTransport,
        baseUrl: String = "https://whisper-large-v3-turbo.inf.tinfoil.sh/v1",
        apiKey: String? = "secret",
        verifier: AttestationVerifier? = null,
        capabilities: EngineCapabilities = EngineCapabilities(TrustLevel.CLOUD, needsApiKey = true),
    ) = OpenAiCompatibleEngine(
        id = "tinfoil-whisper",
        displayName = "Tinfoil (Enclave)",
        baseUrl = baseUrl,
        modelId = "whisper-large-v3-turbo",
        transport = transport,
        apiKeyProvider = { apiKey },
        capabilities = capabilities,
        attestationVerifier = verifier,
        clock = clockSequence(1_000L, 1_250L),
    )

    /** Deterministic latency: first call is the start, second is the finish. */
    private fun clockSequence(vararg values: Long): () -> Long {
        var i = 0
        return { values[minOf(i++, values.lastIndex)] }
    }

    @Test
    fun `builds endpoint urls from every shape a provider documents`() {
        val expected = "https://api.groq.com/openai/v1/audio/transcriptions"
        assertEquals(expected, OpenAiCompatibleEngine.endpointUrl("https://api.groq.com/openai/v1"))
        assertEquals(expected, OpenAiCompatibleEngine.endpointUrl("https://api.groq.com/openai/v1/"))
        assertEquals(expected, OpenAiCompatibleEngine.endpointUrl(" https://api.groq.com/openai "))
        assertEquals(expected, OpenAiCompatibleEngine.endpointUrl(expected))
    }

    @Test
    fun `transcribes and reports latency`() = runTest {
        val transport = FakeHttpTransport.json("""{"text":"  Hallo Welt  ","language":"de"}""")
        val result = engine(transport).transcribe(audio, TranscribeOptions(languageTag = "de-DE"))

        val transcript = result.getOrThrow()
        assertEquals("Hallo Welt", transcript.text)
        assertEquals("de", transcript.detectedLanguage)
        assertEquals(250, transcript.latencyMs)
        assertEquals(TrustLevel.CLOUD, transcript.trustLevel)
    }

    @Test
    fun `sends a wav file plus model and language fields`() = runTest {
        val transport = FakeHttpTransport.json("""{"text":"ok"}""")
        engine(transport).transcribe(audio, TranscribeOptions(languageTag = "de-DE"))

        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals(
            "https://whisper-large-v3-turbo.inf.tinfoil.sh/v1/audio/transcriptions",
            request.url,
        )
        assertEquals("Bearer secret", request.headers["Authorization"])

        val parts = assertIs<HttpBody.Multipart>(request.body).parts
        val file = assertIs<MultipartPart.FilePart>(parts.first { it.name == "file" })
        assertEquals("audio/wav", file.contentType)
        assertEquals("RIFF", file.bytes.decodeToString(0, 4))

        val fields = parts.filterIsInstance<MultipartPart.Field>().associate { it.name to it.value }
        assertEquals("whisper-large-v3-turbo", fields["model"])
        // BCP-47 narrowed to the ISO-639-1 code whisper endpoints expect.
        assertEquals("de", fields["language"])
    }

    @Test
    fun `refuses to send audio when no key is configured`() = runTest {
        val transport = FakeHttpTransport.json("""{"text":"never"}""")
        val result = engine(transport, apiKey = null).transcribe(audio)

        val failure = assertIs<SttFailure>(result.exceptionOrNull())
        assertContains(failure.userMessage, "API-Schlüssel")
        assertTrue(transport.requests.isEmpty(), "must not call the API without a key")
    }

    @Test
    fun `maps http errors to readable messages`() = runTest {
        val unauthorized = engine(FakeHttpTransport.json("""{"error":"bad key"}""", 401))
            .transcribe(audio)
        val failure = assertIs<SttFailure>(unauthorized.exceptionOrNull())
        assertEquals(401, failure.statusCode)
        assertContains(failure.userMessage, "abgelehnt")

        val notFound = engine(FakeHttpTransport.json("nope", 404)).transcribe(audio)
        assertContains(assertIs<SttFailure>(notFound.exceptionOrNull()).userMessage, "Base-URL")
    }

    @Test
    fun `turns a transport exception into a connection failure`() = runTest {
        val result = engine(FakeHttpTransport.failing(java.io.IOException("unreachable")))
            .transcribe(audio)
        val failure = assertIs<SttFailure>(result.exceptionOrNull())
        assertContains(failure.userMessage, "Keine Verbindung")
    }

    @Test
    fun `rejects an empty or malformed response`() = runTest {
        val empty = engine(FakeHttpTransport.json("""{"text":"   "}""")).transcribe(audio)
        assertContains(assertIs<SttFailure>(empty.exceptionOrNull()).userMessage, "nichts erkannt")

        val garbage = engine(FakeHttpTransport.json("<html>502</html>")).transcribe(audio)
        assertContains(assertIs<SttFailure>(garbage.exceptionOrNull()).userMessage, "unverständlich")
    }

    @Test
    fun `claims enclave trust only after the attestation passed`() = runTest {
        val passing = object : AttestationVerifier {
            override suspend fun verify(baseUrl: String) = AttestationResult(true, "digest ok")
        }
        val engine = engine(FakeHttpTransport.json("""{"text":"geheim"}"""), verifier = passing)

        // Before warm-up nothing has been verified, so the label must stay CLOUD.
        assertEquals(TrustLevel.CLOUD, engine.transcribe(audio).getOrThrow().trustLevel)

        engine.warmUp()
        assertEquals(TrustLevel.ENCLAVE_VERIFIED, engine.transcribe(audio).getOrThrow().trustLevel)
    }

    @Test
    fun `stays on cloud trust when the attestation fails or throws`() = runTest {
        val failing = object : AttestationVerifier {
            override suspend fun verify(baseUrl: String) = AttestationResult(false, "digest mismatch")
        }
        val throwing = object : AttestationVerifier {
            override suspend fun verify(baseUrl: String): AttestationResult = error("no attestation doc")
        }

        for (verifier in listOf(failing, throwing)) {
            val engine = engine(FakeHttpTransport.json("""{"text":"x"}"""), verifier = verifier)
            engine.warmUp()
            assertEquals(TrustLevel.CLOUD, engine.transcribe(audio).getOrThrow().trustLevel)
            assertEquals(false, engine.attestation()?.verified)
        }
    }
}
