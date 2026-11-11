package app.amethyst.core

import app.amethyst.core.engine.Problem
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.net.HttpBody
import app.amethyst.core.net.HttpResponse
import app.amethyst.core.net.SecureChannel
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.WritingStyle
import app.amethyst.core.text.ChatCleanup
import app.amethyst.core.text.ClaudeCleanup
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InterruptedIOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeCleanupTest {

    private val base = "https://api.anthropic.com/v1"

    private fun reply(text: String, stop: String = "end_turn", thinking: Boolean = false): String {
        val blocks = buildList {
            if (thinking) add("""{"type":"thinking","thinking":"","signature":"abc"}""")
            add("""{"type":"text","text":${Json.encodeToString(text)}}""")
        }.joinToString(",")
        return """{"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5",""" +
            """"content":[$blocks],"stop_reason":"$stop","usage":{"input_tokens":900,"output_tokens":12}}"""
    }

    private fun error(code: Int, type: String, message: String) =
        FakeHttpTransport.json("""{"type":"error","error":{"type":"$type","message":${Json.encodeToString(message)}},"request_id":"req_1"}""", code)

    private fun cleanup(transport: FakeHttpTransport, key: String? = "sk-ant-api03-secret", thinkingOff: String? = "disabled") = ClaudeCleanup(
        baseUrl = base,
        model = "claude-haiku-5-5",
        channel = SecureChannel(base, "Claude", transport, null, TrustLevel.CLOUD),
        apiKeyProvider = { key },
        service = "Claude",
        thinkingOff = thinkingOff,
    )

    private fun body(transport: FakeHttpTransport): JsonObject =
        Json.parseToJsonElement(assertIs<HttpBody.Bytes>(transport.requests.single().body).bytes.decodeToString()).jsonObject

    private suspend fun problemOf(transport: FakeHttpTransport): Problem =
        assertIs<SttFailure>(cleanup(transport).clean("äh ich komme morgen okay").exceptionOrNull()).problem

    @Test
    fun `sends the dictation to the messages endpoint with the key in its own header`() = runTest {
        val transport = FakeHttpTransport.json(reply("Ich komme morgen, okay?"))
        val result = cleanup(transport).clean("äh ich komme morgen okay").getOrThrow()

        assertEquals("Ich komme morgen, okay?", result.text)
        assertEquals(TrustLevel.CLOUD, result.trust)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.anthropic.com/v1/messages", request.url)
        assertEquals("sk-ant-api03-secret", request.headers["x-api-key"])
        assertEquals(ClaudeCleanup.API_VERSION, request.headers["anthropic-version"])
        assertNull(request.headers["Authorization"], "no Bearer key: Anthropic takes x-api-key")
        assertEquals("application/json", assertIs<HttpBody.Bytes>(request.body).contentType)
        assertEquals(ChatCleanup.timeoutMs("äh ich komme morgen okay".length), request.timeoutMs)
    }

    @Test
    fun `the prompt is the system field and the examples come as turns before the dictation`() = runTest {
        val transport = FakeHttpTransport.json(reply("Ich komme morgen, okay?"))
        cleanup(transport).clean("äh ich komme morgen okay", WritingStyle.CASUAL, instruction = "du", language = "de")

        val body = body(transport)
        assertEquals("claude-haiku-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(ChatCleanup.systemPrompt(WritingStyle.CASUAL, "du", null, "de"), body["system"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        // No system turn among the messages: Anthropic takes it only at the top.
        assertEquals(ChatCleanup.EXAMPLES.size * 2 + 1, messages.size)
        assertEquals(listOf("user", "assistant"), messages.take(2).map { it["role"]!!.jsonPrimitive.content })
        assertEquals("<dictation>\näh ich komme morgen okay\n</dictation>", messages.last()["content"]!!.jsonPrimitive.content)
        assertEquals("user", messages.last()["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no sampling settings, thinking off the model's way, the answer bounded`() = runTest {
        val transport = FakeHttpTransport.json(reply("Ich komme morgen, okay?"))
        cleanup(transport, thinkingOff = "between_tools").clean("äh ich komme morgen okay")

        val body = body(transport)
        listOf("temperature", "top_p", "top_k").forEach { assertFalse(it in body, "$it is refused by current models") }
        assertEquals("between_tools", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse("budget_tokens" in body["thinking"]!!.jsonObject)
        assertTrue(body["max_tokens"]!!.jsonPrimitive.content.toInt() in 256..400)
    }

    @Test
    fun `a model without a known switch thinks as it likes and gets room for it`() = runTest {
        val transport = FakeHttpTransport.json(reply("Ich komme morgen, okay?", thinking = true))
        val result = cleanup(transport, thinkingOff = null).clean("äh ich komme morgen okay").getOrThrow()

        // A thinking block before the text is passed over, not typed.
        assertEquals("Ich komme morgen, okay?", result.text)
        val body = body(transport)
        assertFalse("thinking" in body)
        assertTrue(body["max_tokens"]!!.jsonPrimitive.content.toInt() > 1024)
    }

    @Test
    fun `several text blocks are one answer`() = runTest {
        val transport = FakeHttpTransport.json(
            """{"type":"message","content":[{"type":"text","text":"Ich komme "},{"type":"text","text":"morgen, okay?"}],"stop_reason":"end_turn"}""",
        )
        assertEquals("Ich komme morgen, okay?", cleanup(transport).clean("äh ich komme morgen okay").getOrThrow().text)
    }

    @Test
    fun `an answer cut off at max_tokens keeps the original`() = runTest {
        val problem = problemOf(FakeHttpTransport.json(reply("Ich komme", stop = "max_tokens")))
        assertEquals(Problem.CleanupCutOff, problem)
    }

    @Test
    fun `a refusal is no tidied text`() = runTest {
        val problem = problemOf(FakeHttpTransport.json(reply("", stop = "refusal")))
        assertIs<Problem.CleanupChangedTooMuch>(problem)
    }

    @Test
    fun `an answer that changed too much is rejected by the guard`() = runTest {
        val problem = problemOf(FakeHttpTransport.json(reply("Hier ist eine Zusammenfassung deines Textes: Termin morgen.")))
        assertIs<Problem.CleanupChangedTooMuch>(problem)
    }

    @Test
    fun `an unreadable answer is said so`() = runTest {
        assertEquals(Problem.CleanupUnreadable, problemOf(FakeHttpTransport.json("<html>gateway</html>")))
        assertEquals(Problem.CleanupUnreadable, problemOf(FakeHttpTransport.json("""{"type":"message","stop_reason":"end_turn"}""")))
    }

    @Test
    fun `a rejected key`() = runTest {
        assertEquals(Problem.KeyRejected, problemOf(error(401, "authentication_error", "invalid x-api-key")))
        assertEquals(Problem.KeyRejected, problemOf(error(403, "permission_error", "Your API key does not have permission")))
    }

    @Test
    fun `an unknown model is named`() = runTest {
        assertEquals(Problem.CleanupModelUnknown("claude-haiku-5-5"), problemOf(error(404, "not_found_error", "model: claude-haiku-5-5")))
        assertEquals(Problem.CleanupModelUnknown("claude-haiku-5-5"), problemOf(error(400, "invalid_request_error", "model: Input should be a valid model")))
    }

    @Test
    fun `another bad request is a plain failure`() = runTest {
        assertEquals(Problem.CleanupFailed(400), problemOf(error(400, "invalid_request_error", "messages: roles must alternate")))
    }

    @Test
    fun `no credit left`() = runTest {
        assertEquals(Problem.NoCredit("Claude"), problemOf(error(400, "invalid_request_error", "Your credit balance is too low to access the Anthropic API.")))
        assertEquals(Problem.NoCredit("Claude"), problemOf(error(402, "billing_error", "There is an issue with your billing")))
    }

    @Test
    fun `too many requests`() = runTest {
        assertEquals(Problem.RateLimited, problemOf(error(429, "rate_limit_error", "Number of request tokens has exceeded your rate limit")))
    }

    @Test
    fun `overloaded and server errors`() = runTest {
        assertEquals(Problem.ServiceDown(529), problemOf(error(529, "overloaded_error", "Overloaded")))
        assertEquals(Problem.ServiceDown(500), problemOf(error(500, "api_error", "Internal server error")))
        assertEquals(Problem.ServiceDown(504), problemOf(error(504, "timeout_error", "Request timeout")))
    }

    @Test
    fun `no answer in time`() = runTest {
        val problem = problemOf(FakeHttpTransport.failing(InterruptedIOException("timeout")))
        assertEquals(Problem.NoAnswerInTime("Claude"), problem)
    }

    @Test
    fun `without a key nothing is sent`() = runTest {
        val transport = FakeHttpTransport.json(reply("x"))
        val failure = assertIs<SttFailure>(cleanup(transport, key = null).clean("äh ich komme morgen okay").exceptionOrNull())
        assertEquals(Problem.NoApiKey("Claude"), failure.problem)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `the key is in no failure's detail`() = runTest {
        val transport = error(401, "authentication_error", "invalid x-api-key")
        val failure = assertIs<SttFailure>(cleanup(transport).clean("äh ich komme morgen okay").exceptionOrNull())
        assertFalse("sk-ant-api03-secret" in failure.technical)
        assertTrue("401" in failure.technical)
    }

    @Test
    fun `a blank dictation is not sent`() = runTest {
        val transport = FakeHttpTransport.json(reply("x"))
        assertEquals("  ", cleanup(transport).clean("  ").getOrThrow().text)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `warming up opens the api root without the key`() = runTest {
        val transport = FakeHttpTransport { HttpResponse(404, ByteArray(0)) }
        assertTrue(cleanup(transport).warmUp())
        val head = transport.requests.single()
        assertEquals("HEAD", head.method)
        assertEquals("https://api.anthropic.com/v1", head.url)
        assertNull(head.headers["x-api-key"])
    }
}
