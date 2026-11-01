package app.amethyst.core

import app.amethyst.core.net.HttpBody
import app.amethyst.core.net.HttpRequest
import app.amethyst.core.net.MultipartPart
import app.amethyst.core.net.OkHttpTransport
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the progress ring hears from the network: bytes sent, and a reply as it streams in. */
class TransportProgressTest {

    private val server = MockWebServer().apply { start() }
    private val transport = OkHttpTransport()

    @AfterTest
    fun stop() = server.shutdown()

    @Test
    fun `an upload reports its bytes up to the whole body`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"ok"}"""))
        val reports = CopyOnWriteArrayList<Pair<Long, Long>>()
        val recording = ByteArray(200_000) { it.toByte() }
        transport.execute(
            HttpRequest(
                method = "POST",
                url = server.url("/v1/audio/transcriptions").toString(),
                body = HttpBody.Multipart(listOf(MultipartPart.FilePart("file", "d.wav", "audio/wav", recording), MultipartPart.Field("model", "m"))),
                onUpload = { sent, total -> reports += sent to total },
            ),
        )
        val total = reports.last().second
        assertTrue(total > recording.size, "the whole form counts: $total")
        assertEquals(total, reports.last().first, "ends at the total")
        assertTrue(reports.size > 2, "in steps, not at once: ${reports.size}")
        assertTrue(reports.zipWithNext().all { (a, b) -> b.first >= a.first }, "never backwards")
        assertEquals(total, server.takeRequest().bodySize)
    }

    @Test
    fun `a streamed reply arrives in pieces and whole`() = runTest {
        val events = (1..50).joinToString("") { "data: {\"n\":$it}\n\n" } + "data: [DONE]\n\n"
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(Buffer().writeUtf8(events), 64),
        )
        val pieces = CopyOnWriteArrayList<ByteArray>()
        val response = transport.execute(
            HttpRequest(method = "GET", url = server.url("/v1/chat/completions").toString(), onReceive = { pieces += it }),
        )
        assertEquals(events, response.bodyAsText())
        assertContentEquals(response.body, pieces.fold(ByteArray(0)) { acc, p -> acc + p })
        assertTrue(pieces.isNotEmpty())
    }

    @Test
    fun `without listeners nothing changes`() = runTest {
        server.enqueue(MockResponse().setBody("plain"))
        assertEquals("plain", transport.execute(HttpRequest(method = "GET", url = server.url("/").toString())).bodyAsText())
    }
}
