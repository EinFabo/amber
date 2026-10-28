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
import app.amethyst.core.privacy.EnclaveProof
import app.amethyst.core.net.HttpResponse
import app.amethyst.core.privacy.TrustLevel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAiCompatibleEngineTest {

    /** The engine() helper wants a FakeHttpTransport; the pinnable fake is passed separately. */
    private fun FakePinnableTransport.asFake() = FakeHttpTransport { error("unpinned request") }

    private val audio = PcmAudio(ShortArray(16_000) { (it % 500).toShort() })

    private fun engine(
        transport: FakeHttpTransport,
        baseUrl: String = "https://inference.tinfoil.sh/v1",
        apiKey: String? = "secret",
        verifier: AttestationVerifier? = null,
        capabilities: EngineCapabilities = EngineCapabilities(TrustLevel.CLOUD, needsApiKey = true),
        pinnable: FakePinnableTransport? = null,
    ) = OpenAiCompatibleEngine(
        id = "tinfoil-whisper",
        displayName = "Tinfoil (Enclave)",
        baseUrl = baseUrl,
        modelId = "whisper-large-v3-turbo",
        transport = pinnable ?: transport,
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
            "https://inference.tinfoil.sh/v1/audio/transcriptions",
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
    fun `a chunk's preceding text and the vocabulary go out as the prompt`() = runTest {
        val transport = FakeHttpTransport.json("""{"text":"ok"}""")
        engine(transport).transcribe(audio, TranscribeOptions(vocabularyHint = "Tinfoil", precedingText = "Das ist"))

        val fields = assertIs<HttpBody.Multipart>(transport.requests.single().body).parts
            .filterIsInstance<MultipartPart.Field>().associate { it.name to it.value }
        assertEquals("Tinfoil Das ist", fields["prompt"])
    }

    @Test
    fun `a model that refuses the prompt gets the audio again without it`() = runTest {
        val transport = FakeHttpTransport { request ->
            val hasPrompt = assertIs<HttpBody.Multipart>(request.body).parts.any { it.name == "prompt" }
            if (hasPrompt) HttpResponse(400, "prompt not supported".encodeToByteArray())
            else HttpResponse(200, """{"text":"weiter"}""".encodeToByteArray())
        }
        val transcript = engine(transport)
            .transcribe(audio, TranscribeOptions(precedingText = "Das ist"))
            .getOrThrow()

        assertEquals("weiter", transcript.text)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `refuses to send audio when no key is configured`() = runTest {
        val transport = FakeHttpTransport.json("""{"text":"never"}""")
        val result = engine(transport, apiKey = null).transcribe(audio)

        val failure = assertIs<SttFailure>(result.exceptionOrNull())
        assertContains(failure.userMessage, "API key")
        assertTrue(transport.requests.isEmpty(), "must not call the API without a key")
    }

    @Test
    fun `maps http errors to readable messages`() = runTest {
        val unauthorized = engine(FakeHttpTransport.json("""{"error":"bad key"}""", 401))
            .transcribe(audio)
        val failure = assertIs<SttFailure>(unauthorized.exceptionOrNull())
        assertEquals(401, failure.statusCode)
        assertContains(failure.userMessage, "rejected")

        val notFound = engine(FakeHttpTransport.json("nope", 404)).transcribe(audio)
        assertContains(assertIs<SttFailure>(notFound.exceptionOrNull()).userMessage, "base URL")
    }

    @Test
    fun `turns a transport exception into a connection failure`() = runTest {
        val result = engine(FakeHttpTransport.failing(java.io.IOException("unreachable")))
            .transcribe(audio)
        val failure = assertIs<SttFailure>(result.exceptionOrNull())
        assertContains(failure.userMessage, "No connection")
    }

    @Test
    fun `rejects an empty or malformed response`() = runTest {
        val empty = engine(FakeHttpTransport.json("""{"text":"   "}""")).transcribe(audio)
        assertTrue(assertIs<SttFailure>(empty.exceptionOrNull()).nothingRecognized)

        val garbage = engine(FakeHttpTransport.json("<html>502</html>")).transcribe(audio)
        assertContains(assertIs<SttFailure>(garbage.exceptionOrNull()).userMessage, "could not be read")
    }

    private fun proof(key: String, host: String = "inference.tinfoil.sh") = EnclaveProof(
        host = host,
        repo = "tinfoilsh/confidential-model-router",
        codeTag = "v1.0.0",
        codeDigest = "abc",
        measurementType = "https://tinfoil.sh/predicate/tdx-guest/v2",
        registers = listOf("00"),
        tlsKeySha256Hex = key,
        hpkePublicKey = null,
        verifiedAtMillis = 0,
        freshUntilMillis = Long.MAX_VALUE,
    )

    /** Hands out [keys] in turn: the first verification sees the first key, and so on. */
    private class ScriptedVerifier(private val keys: List<String?>, private val host: String = "inference.tinfoil.sh") : AttestationVerifier {
        val calls = mutableListOf<Boolean>()
        override suspend fun verify(baseUrl: String, forceFresh: Boolean): AttestationResult {
            calls.add(forceFresh)
            val key = keys[minOf(calls.size - 1, keys.lastIndex)]
                ?: return AttestationResult(false, "quote: signature invalid")
            return AttestationResult(true, "ok", OpenAiCompatibleEngineTest().proof(key, host))
        }
    }

    private val keyA = "a".repeat(64)
    private val keyB = "b".repeat(64)

    @Test
    fun `sends over a connection bound to the attested key and says so`() = runTest {
        val transport = FakePinnableTransport(serverKey = keyA) {
            HttpResponse(200, """{"text":"geheim"}""".encodeToByteArray(), mapOf("tinfoil-enclave" to "whisper-inf3.tinfoil.sh"))
        }
        val transcript = engine(transport.asFake(), verifier = ScriptedVerifier(listOf(keyA)), pinnable = transport)
            .transcribe(audio).getOrThrow()

        assertEquals(TrustLevel.ENCLAVE_VERIFIED, transcript.trustLevel)
        assertEquals("whisper-inf3.tinfoil.sh", transcript.servedBy)
        assertEquals(keyA, transport.pinnedRequests.single().first)
        assertTrue(transport.requests.isEmpty(), "nothing may go out unpinned")
    }

    @Test
    fun `sends nothing when the enclave cannot be verified`() = runTest {
        val failing = ScriptedVerifier(listOf(null))
        val throwing = object : AttestationVerifier {
            override suspend fun verify(baseUrl: String, forceFresh: Boolean): AttestationResult = error("no attestation doc")
        }
        for (verifier in listOf(failing, throwing)) {
            val transport = FakePinnableTransport(serverKey = keyA) { HttpResponse(200, """{"text":"x"}""".encodeToByteArray()) }
            val result = engine(transport.asFake(), verifier = verifier, pinnable = transport).transcribe(audio)

            assertContains(assertIs<SttFailure>(result.exceptionOrNull()).userMessage, "not sent")
            assertTrue(transport.requests.isEmpty() && transport.pinnedRequests.isEmpty())
        }
    }

    @Test
    fun `a rotated enclave key is verified afresh once and then used`() = runTest {
        // The cached proof still names key A, but the enclave now presents key B.
        val transport = FakePinnableTransport(serverKey = keyB) { HttpResponse(200, """{"text":"neu"}""".encodeToByteArray()) }
        val verifier = ScriptedVerifier(listOf(keyA, keyB))

        val transcript = engine(transport.asFake(), verifier = verifier, pinnable = transport).transcribe(audio).getOrThrow()

        assertEquals("neu", transcript.text)
        assertEquals(listOf(false, true), verifier.calls, "second verification must bypass the cache")
        assertEquals(keyB, transport.pinnedRequests.single().first)
    }

    @Test
    fun `a server that never presents the attested key gets nothing`() = runTest {
        val transport = FakePinnableTransport(serverKey = "c".repeat(64)) { HttpResponse(200, """{"text":"x"}""".encodeToByteArray()) }
        val result = engine(transport.asFake(), verifier = ScriptedVerifier(listOf(keyA, keyB)), pinnable = transport).transcribe(audio)

        assertContains(assertIs<SttFailure>(result.exceptionOrNull()).userMessage, "not sent")
        assertTrue(transport.pinnedRequests.isEmpty())
    }

    @Test
    fun `a proof for another host does not authorize this endpoint`() = runTest {
        val transport = FakePinnableTransport(serverKey = keyA) { HttpResponse(200, """{"text":"x"}""".encodeToByteArray()) }
        val result = engine(
            transport.asFake(),
            verifier = ScriptedVerifier(listOf(keyA), host = "other.tinfoil.sh"),
            pinnable = transport,
        ).transcribe(audio)

        assertIs<SttFailure>(result.exceptionOrNull())
        assertTrue(transport.pinnedRequests.isEmpty())
    }
}
