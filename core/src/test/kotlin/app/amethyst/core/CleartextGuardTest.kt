package app.amethyst.core

import app.amethyst.core.engine.Problem
import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.engine.EngineCapabilities
import app.amethyst.core.engine.OpenAiCompatibleEngine
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.net.CleartextGuard
import app.amethyst.core.net.CleartextRefusedException
import app.amethyst.core.net.HttpRequest
import app.amethyst.core.net.OkHttpTransport
import app.amethyst.core.net.guardCleartext
import app.amethyst.core.privacy.TrustLevel
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * The request-time guard on the real OkHttp path. The test server listens on loopback, which
 * is local; a guard that takes every address for public stands in for a server on the internet.
 */
class CleartextGuardTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val hits = AtomicInteger()

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            hits.incrementAndGet()
            exchange.requestBody.readBytes()
            val bytes = """{"text":"ok"}""".encodeToByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}/v1"
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    private val everythingPublic = OkHttpClient.Builder().addNetworkInterceptor(CleartextGuard { false }).build()

    @Test
    fun `plain http to a local address goes through`() = runTest {
        val response = OkHttpTransport().execute(HttpRequest(method = "GET", url = "$baseUrl/models"))
        assertEquals(200, response.code)
        assertEquals(1, hits.get())
    }

    @Test
    fun `plain http to a public address is refused before anything is sent`() = runTest {
        val refused = assertFailsWith<CleartextRefusedException> {
            OkHttpTransport(everythingPublic).execute(HttpRequest(method = "GET", url = "$baseUrl/models"))
        }
        assertEquals(CleartextRefusedException.MESSAGE, refused.message)
        assertEquals(0, hits.get())
    }

    @Test
    fun `the refusal reaches the user as such and is no case for the local fallback`() = runTest {
        val engine = OpenAiCompatibleEngine(
            id = "self-hosted",
            displayName = "Own server",
            baseUrl = baseUrl,
            modelId = "whisper-large-v3-turbo",
            transport = OkHttpTransport(everythingPublic),
            capabilities = EngineCapabilities(TrustLevel.CLOUD, needsApiKey = false),
        )
        val failure = assertIs<SttFailure>(engine.transcribe(PcmAudio(ShortArray(16_000))).exceptionOrNull())
        assertEquals(Problem.UnencryptedRefused, failure.problem)
        // Recognizing it on the phone instead would hide a setting the user has to fix.
        assertFalse(failure.serviceUnavailable)
        assertFalse(failure.recordingMayHaveLeft)
        assertEquals(0, hits.get())
    }

    @Test
    fun `every client gets exactly one guard`() {
        val plain = OkHttpClient.Builder().guardCleartext().guardCleartext().build()
        assertEquals(1, plain.networkInterceptors.count { it is CleartextGuard })
        // A transport given a client without one adds it; one given a guard keeps that one.
        val transport = OkHttpTransport(OkHttpClient())
        assertEquals(1, transport.guards())
        assertEquals(1, OkHttpTransport(everythingPublic).guards())
    }

    private fun OkHttpTransport.guards(): Int = client.networkInterceptors.count { it is CleartextGuard }
}
