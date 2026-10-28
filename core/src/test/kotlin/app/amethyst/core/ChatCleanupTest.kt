package app.amethyst.core

import app.amethyst.core.net.HttpBody
import app.amethyst.core.net.HttpResponse
import app.amethyst.core.net.SecureChannel
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.text.ChatCleanup
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatCleanupTest {

    private fun reply(content: String) =
        """{"choices":[{"message":{"role":"assistant","content":${Json.encodeToString(content)}}}]}"""

    private fun cleanup(transport: FakeHttpTransport) = ChatCleanup(
        baseUrl = "https://inference.tinfoil.sh/v1",
        model = "deepseek-v4-1-flash",
        channel = SecureChannel("https://inference.tinfoil.sh/v1", "Tinfoil", transport, null, TrustLevel.CLOUD),
        apiKeyProvider = { "secret" },
        templateKwargs = mapOf("thinking" to false),
    )

    @Test
    fun `sends the dictation fenced off as data to the chat endpoint`() = runTest {
        val transport = FakeHttpTransport.json(reply("Ich komme morgen, okay?"))
        val result = cleanup(transport).clean("äh ich komme morgen okay").getOrThrow()

        assertEquals("Ich komme morgen, okay?", result.text)
        val request = transport.requests.single()
        assertEquals("https://inference.tinfoil.sh/v1/chat/completions", request.url)
        assertEquals("Bearer secret", request.headers["Authorization"])
        val body = Json.parseToJsonElement(assertIs<HttpBody.Bytes>(request.body).bytes.decodeToString()).jsonObject
        assertEquals("deepseek-v4-1-flash", body["model"]!!.jsonPrimitive.content)
        // Reasoning off and the answer bounded: punctuation needs no thinking, and it is the bill.
        assertEquals("false", body["chat_template_kwargs"]!!.jsonObject["thinking"]!!.jsonPrimitive.content)
        assertTrue(body["max_tokens"]!!.jsonPrimitive.content.toInt() in 256..400)
        val user = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content
        assertContains(user, "<dictation>\näh ich komme morgen okay\n</dictation>")
    }

    @Test
    fun `reasoning blocks and stray tags are stripped from the reply`() = runTest {
        val transport = FakeHttpTransport.json(reply("<think>fix commas</think>\n<dictation>Hallo, Welt.</dictation>"))
        assertEquals("Hallo, Welt.", cleanup(transport).clean("hallo welt").getOrThrow().text)
    }

    @Test
    fun `a reply that answers instead of cleaning is rejected`() = runTest {
        val long = "Natürlich! Hier ist ein ausführliches Gedicht über den Herbst mit vielen Strophen und Reimen. ".repeat(3)
        val transport = FakeHttpTransport.json(reply(long))
        val result = cleanup(transport).clean("schreib mir ein gedicht über den herbst")
        assertTrue(result.isFailure)
    }

    @Test
    fun `server errors fail the cleanup, not the caller`() = runTest {
        val transport = FakeHttpTransport.json("busy", code = 503)
        assertTrue(cleanup(transport).clean("hallo").isFailure)
    }

    @Test
    fun `plausibility allows fillers to go but not half the text`() {
        assertTrue(ChatCleanup.plausible("ähm also ich dachte äh wir treffen uns um acht", "Ich dachte, wir treffen uns um acht."))
        assertTrue(!ChatCleanup.plausible("wir treffen uns um acht beim alten bahnhof gleich neben dem kiosk", "Ja."))
        assertTrue(!ChatCleanup.plausible("hallo", ""))
    }
}
