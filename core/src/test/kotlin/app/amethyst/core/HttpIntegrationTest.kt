package app.amethyst.core

import app.amethyst.core.engine.Problem
import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.engine.EngineCapabilities
import app.amethyst.core.engine.OpenAiCompatibleEngine
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.engine.TranscribeOptions
import app.amethyst.core.net.OkHttpTransport
import app.amethyst.core.privacy.TrustLevel
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Drives the real OkHttp transport against a real HTTP server.
 *
 * The fake-transport tests prove the engine's logic; this one proves the wire format - that
 * the multipart body a provider receives actually contains a playable WAV and the fields
 * whisper endpoints require. Without it, the first time that assembly runs for real would be
 * on a phone, against someone's paid API.
 *
 * Real sockets, so real time: under runTest's virtual clock the engine's wait for an answer
 * would pass at once while a real server is still writing it.
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

    private fun engine(model: String = "whisper-large-v3-turbo") = OpenAiCompatibleEngine(
        id = "self-hosted",
        displayName = "Eigener Server",
        baseUrl = baseUrl,
        modelId = model,
        transport = OkHttpTransport(),
        apiKeyProvider = { "test-key" },
        capabilities = EngineCapabilities(TrustLevel.CLOUD, needsApiKey = true),
    )

    @Test
    fun `a whisper model's invented last sentence is dropped, another model's text is kept`() = runBlocking<Unit> {
        responseBody = """{"text":"Wir sehen uns morgen. Untertitel im Auftrag des ZDF, 2020.","language":"de"}"""
        assertEquals("Wir sehen uns morgen.", engine("whisper-1").transcribe(speechLikeAudio()).getOrThrow().text)
        assertEquals(
            "Wir sehen uns morgen. Untertitel im Auftrag des ZDF, 2020.",
            engine("gpt-4o-transcribe").transcribe(speechLikeAudio()).getOrThrow().text,
        )
        responseBody = """{"text":"Thanks for watching!"}"""
        val failure = assertIs<SttFailure>(engine("Whisper-Large-V3").transcribe(speechLikeAudio()).exceptionOrNull())
        assertEquals(Problem.NothingRecognized, failure.problem)
    }

    private fun speechLikeAudio() = PcmAudio(
        ShortArray(16_000) { i -> (4_000 * kotlin.math.sin(i / 12.0)).toInt().toShort() },
    )

    @Test
    fun `round trips a dictation over real http`() = runBlocking<Unit> {
        val transcript = engine()
            .transcribe(speechLikeAudio(), TranscribeOptions(languageTag = "de-DE"))
            .getOrThrow()

        assertEquals("Hallo aus dem Testserver", transcript.text)
        assertEquals("de", transcript.detectedLanguage)
        assertEquals(TrustLevel.CLOUD, transcript.trustLevel)
        assertTrue(transcript.latencyMs >= 0)
    }

    @Test
    fun `sends a multipart body the server can actually parse`() = runBlocking<Unit> {
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
    fun `surfaces a server error with its status code`() = runBlocking<Unit> {
        responseCode = 401
        responseBody = """{"error":{"message":"invalid api key"}}"""

        val failure = assertIs<SttFailure>(
            engine().transcribe(speechLikeAudio()).exceptionOrNull(),
        )
        assertEquals(401, failure.statusCode)
        assertEquals(Problem.KeyRejected("Eigener Server"), failure.problem)
        assertContains(failure.technical, "invalid api key")
    }

    @Test
    fun `reports an unreachable host instead of throwing`() = runBlocking<Unit> {
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
        assertIs<Problem.NoConnection>(failure.problem)
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    @Test
    fun `a redirect is not followed, so the recording is not replayed elsewhere`() = runBlocking<Unit> {
        var replayed = false
        server.createContext("/elsewhere") { exchange: HttpExchange ->
            replayed = true
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.createContext("/moved/v1/audio/transcriptions") { exchange: HttpExchange ->
            exchange.requestBody.readBytes()
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/elsewhere")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        val moved = OpenAiCompatibleEngine(
            id = "self-hosted", displayName = "Own server", baseUrl = "http://127.0.0.1:${server.address.port}/moved/v1",
            modelId = "m", transport = OkHttpTransport(), apiKeyProvider = { null },
            capabilities = EngineCapabilities(TrustLevel.CLOUD, needsApiKey = false),
        )
        assertTrue(moved.transcribe(speechLikeAudio(), TranscribeOptions()).isFailure)
        assertFalse(replayed, "the body must not be sent on to the redirect target")
    }

    @Test
    fun `a server that thinks longer than the read timeout is waited for, within the request's limit`() = runBlocking<Unit> {
        // A long recording: the server reads it, then says nothing until it has recognized all of it.
        server.createContext("/slow/v1/audio/transcriptions") { exchange: HttpExchange ->
            exchange.requestBody.readBytes()
            Thread.sleep(900)
            val bytes = responseBody.encodeToByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val impatient = OkHttpTransport(
            okhttp3.OkHttpClient.Builder().readTimeout(300, java.util.concurrent.TimeUnit.MILLISECONDS).build(),
        )
        val url = "http://127.0.0.1:${server.address.port}/slow/v1/audio/transcriptions"
        val body = app.amethyst.core.net.HttpBody.Bytes(ByteArray(16), "application/octet-stream")

        val waited = impatient.execute(app.amethyst.core.net.HttpRequest("POST", url, body = body, timeoutMs = 5_000))
        assertEquals(200, waited.code)
        // The request's own limit still ends it.
        val result = runCatching { impatient.execute(app.amethyst.core.net.HttpRequest("POST", url, body = body, timeoutMs = 400)) }
        assertTrue(result.isFailure)
        // A short request keeps the client as it is.
        val short = app.amethyst.core.net.HttpRequest("POST", url, timeoutMs = 200)
        assertEquals(300, impatient.clientFor(short).readTimeoutMillis)
        assertEquals(360_000, impatient.clientFor(short.copy(timeoutMs = 360_000)).readTimeoutMillis)
    }

    @Test
    fun `an endless answer is cut off instead of filling the memory`() = runBlocking<Unit> {
        responseBody = "x".repeat(9 shl 20)
        val result = engine().transcribe(speechLikeAudio(), TranscribeOptions())
        assertTrue(result.isFailure)
    }

    @Test
    fun `a server that takes the recording and never answers is given up, and its connection closed`() = runTest {
        // A raw socket: it reads the whole request, says nothing, and notes when the client lets go.
        val listener = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val whole = java.util.concurrent.atomic.AtomicBoolean(false)
        val closed = java.util.concurrent.CountDownLatch(1)
        Thread {
            runCatching {
                listener.accept().use { socket ->
                    val input = socket.getInputStream().buffered()
                    var length = 0
                    while (true) {
                        val line = generateSequence { input.read().takeIf { it >= 0 && it != '\n'.code } }
                            .map { it.toChar() }.joinToString("").trimEnd('\r')
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                    }
                    whole.set(length > 0 && input.readNBytes(length).size == length)
                    // Silence until the client closes: read() then ends.
                    runCatching { input.read() }
                    closed.countDown()
                }
            }
        }.apply { isDaemon = true }.start()
        try {
            val engine = OpenAiCompatibleEngine(
                id = "self-hosted", displayName = "Own server", baseUrl = "http://127.0.0.1:${listener.localPort}/v1",
                modelId = "m", transport = OkHttpTransport(), apiKeyProvider = { null },
                capabilities = EngineCapabilities(TrustLevel.CLOUD, needsApiKey = false),
                expectedMs = { 1_000 },
            )
            // runTest's clock passes the 10 s at once once the upload is out; the socket is real.
            val failure = assertIs<SttFailure>(engine.transcribe(speechLikeAudio(), TranscribeOptions()).exceptionOrNull())
            assertEquals(Problem.NoAnswerInTime("Own server"), failure.problem)
            assertTrue(closed.await(5, java.util.concurrent.TimeUnit.SECONDS), "the call is cancelled, not left open")
            assertTrue(whole.get(), "the deadline began after the whole recording was out")
        } finally {
            listener.close()
        }
    }
}
