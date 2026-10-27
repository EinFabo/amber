package app.nocturne.core

import app.nocturne.core.audio.PcmAudio
import app.nocturne.core.engine.EngineCapabilities
import app.nocturne.core.engine.OpenAiCompatibleEngine
import app.nocturne.core.engine.SttFailure
import app.nocturne.core.engine.TranscribeOptions
import app.nocturne.core.net.OkHttpTransport
import app.nocturne.core.privacy.TrustLevel
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Drives the real OkHttp transport against a real HTTP server.
 *
 * The fake-transport tests prove the engine's logic; this one proves the wire format - that
 * the multipart body a provider receives actually contains a playable WAV and the fields
 * whisper endpoints require. Without it, the first time that assembly runs for real would be
 * on a phone, against someone's paid API.
 */
class HttpIntegrationTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    private var lastBody: ByteArray = ByteArray(0)
    private var lastContentType: String? = null
    private var lastAuthorization: String? = null
    private var responseCode = 200
    private var responseBody = """{"text":"Hallo aus dem Testserver","language":"de"}"""

    @BeforeTest
    fun startServer() {
        // Port 0: let the OS pick a free one, so parallel runs never collide.
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/audio/transcriptions") { exchange: HttpExchange ->
            lastBody = exchange.requestBody.readBytes()
            lastContentType = exchange.requestHeaders.getFirst("Content-Type")
            lastAuthorization = exchange.requestHeaders.getFirst("Authorization")

            val bytes = responseBody.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(responseCode, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}/v1"
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    private fun engine() = OpenAiCompatibleEngine(
        id = "self-hosted",
        displayName = "Eigener Server",
        baseUrl = baseUrl,
        modelId = "whisper-large-v3-turbo",
        transport = OkHttpTransport(),
        apiKeyProvider = { "test-key" },
        capabilities = EngineCapabilities(TrustLevel.CLOUD, needsApiKey = true),
    )

    private fun speechLikeAudio() = PcmAudio(
        ShortArray(16_000) { i -> (4_000 * kotlin.math.sin(i / 12.0)).toInt().toShort() },
    )

    @Test
    fun `round trips a dictation over real http`() = runTest {
        val transcript = engine()
            .transcribe(speechLikeAudio(), TranscribeOptions(languageTag = "de-DE"))
            .getOrThrow()

        assertEquals("Hallo aus dem Testserver", transcript.text)
        assertEquals("de", transcript.detectedLanguage)
        assertEquals(TrustLevel.CLOUD, transcript.trustLevel)
        assertTrue(transcript.latencyMs >= 0)
    }

    @Test
    fun `sends a multipart body the server can actually parse`() = runTest {
        engine().transcribe(speechLikeAudio(), TranscribeOptions(languageTag = "de-DE")).getOrThrow()

        assertContains(lastContentType.orEmpty(), "multipart/form-data")
        assertContains(lastContentType.orEmpty(), "boundary=")
        assertEquals("Bearer test-key", lastAuthorization)

        val asText = lastBody.decodeToString(throwOnInvalidSequence = false)
        assertContains(asText, "name=\"file\"")
        assertContains(asText, "filename=\"dictation.wav\"")
        assertContains(asText, "name=\"model\"")
        assertContains(asText, "whisper-large-v3-turbo")
        assertContains(asText, "name=\"language\"")
        assertContains(asText, "name=\"response_format\"")

        // The WAV must survive the multipart encoding byte for byte, header and payload.
        val riff = indexOfBytes(lastBody, "RIFF".encodeToByteArray())
        assertTrue(riff >= 0, "no RIFF header in the uploaded part")
        assertEquals("WAVE", lastBody.decodeToString(riff + 8, riff + 12))
        val expectedBytes = PcmAudio.WAV_HEADER_SIZE + 16_000 * 2
        assertTrue(
            lastBody.size >= riff + expectedBytes,
            "uploaded audio is truncated: ${lastBody.size - riff} of $expectedBytes bytes",
        )
    }

    @Test
    fun `surfaces a server error with its status code`() = runTest {
        responseCode = 401
        responseBody = """{"error":{"message":"invalid api key"}}"""

        val failure = assertIs<SttFailure>(
            engine().transcribe(speechLikeAudio()).exceptionOrNull(),
        )
        assertEquals(401, failure.statusCode)
        assertContains(failure.userMessage, "abgelehnt")
        assertContains(failure.technical, "invalid api key")
    }

    @Test
    fun `reports an unreachable host instead of throwing`() = runTest {
        val unreachable = OpenAiCompatibleEngine(
            id = "self-hosted",
            displayName = "Eigener Server",
            // Port 1 on loopback refuses connections immediately.
            baseUrl = "http://127.0.0.1:1/v1",
            modelId = "whisper-large-v3-turbo",
            transport = OkHttpTransport(),
            apiKeyProvider = { "test-key" },
            capabilities = EngineCapabilities(TrustLevel.CLOUD, needsApiKey = true),
        )

        val failure = assertIs<SttFailure>(
            unreachable.transcribe(speechLikeAudio()).exceptionOrNull(),
        )
        assertContains(failure.userMessage, "Keine Verbindung")
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
