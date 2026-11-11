package app.amethyst.core.text

import app.amethyst.core.engine.OpenAiCompatibleEngine
import app.amethyst.core.engine.Problem
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.net.HttpBody
import app.amethyst.core.net.HttpRequest
import app.amethyst.core.net.HttpResponse
import app.amethyst.core.net.Redact
import app.amethyst.core.net.SecureChannel
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.WritingStyle
import app.amethyst.core.text.TextCleanup.Cleaned
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The cleanup on Claude, in Anthropic's own Messages API (`POST /v1/messages`): its
 * OpenAI-compatible layer refuses the cleanup's temperature 0 and is not meant for production.
 * The same prompt, worked examples and [CleanupGuard] as [ChatCleanup]; the prompt goes in the
 * top-level `system` field, the key in `x-api-key`.
 *
 * No sampling settings are sent: current Claude models take only their defaults. Thinking
 * before the answer is switched off the way the model takes it ([thinkingOff]) - tidying
 * punctuation needs none, and it is the slow part.
 */
class ClaudeCleanup(
    private val baseUrl: String,
    override val model: String,
    private val channel: SecureChannel,
    private val apiKeyProvider: () -> String?,
    /** What the service is called, for the user: "Claude". */
    private val service: String,
    /** The thinking type that turns this model's thinking off ("disabled", "between_tools"); null sends none. */
    private val thinkingOff: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : TextCleanup {

    override suspend fun warmUp(): Boolean = channel.warmUp(OpenAiCompatibleEngine.apiRoot(baseUrl))

    override suspend fun clean(
        text: String,
        style: WritingStyle,
        instruction: String?,
        generalInstruction: String?,
        language: String?,
        onProgress: ((Float) -> Unit)?,
    ): Result<Cleaned> {
        if (text.isBlank()) return Result.success(Cleaned(text, 0, TrustLevel.ON_DEVICE))
        // Never sent without one: Claude answers nothing without a key.
        val key = apiKeyProvider()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(SttFailure(Problem.NoApiKey(service), "no key for this address"))
        val startedAt = clock()
        val body = request(text, style, instruction, generalInstruction, language)
        val request = HttpRequest(
            method = "POST",
            url = messagesUrl(baseUrl),
            headers = mapOf(
                "x-api-key" to key,
                "anthropic-version" to API_VERSION,
                "Accept" to "application/json",
            ),
            body = HttpBody.Bytes(body.toString().encodeToByteArray(), "application/json"),
            timeoutMs = ChatCleanup.timeoutMs(text.length),
        )
        val sent = channel.send(request, what = Problem.Payload.TEXT).getOrElse { return Result.failure(it) }
        val response = sent.response
        if (!response.isSuccessful) return Result.failure(failure(response))
        val reply = runCatching { replyOf(response.bodyAsText()) }.getOrNull()
            ?: return Result.failure(SttFailure(Problem.CleanupUnreadable, "unparseable reply, ${response.body.size} bytes"))
        when (reply.stopReason) {
            in NORMAL_ENDS -> Unit
            // Declined by its safety checks: the answer is no tidied dictation.
            "refusal" -> return Result.failure(SttFailure(Problem.CleanupChangedTooMuch("refused"), "stop_reason=refusal"))
            // max_tokens, or anything new: the end of the dictation may be lost.
            else -> return Result.failure(SttFailure(Problem.CleanupCutOff, "stop_reason=${reply.stopReason}"))
        }
        return ChatCleanup.accepted(text, reply.text, style, instruction, generalInstruction, language)
            .map { Cleaned(it, clock() - startedAt, sent.trust) }
    }

    /** The request body: the prompt as `system`, the worked examples as turns, the dictation last. */
    internal fun request(
        text: String,
        style: WritingStyle,
        instruction: String?,
        generalInstruction: String?,
        language: String?,
    ): JsonObject = buildJsonObject {
        put("model", model)
        // As long as the dictation and a little more; with thinking left on, room for it too,
        // or a short dictation comes back cut off.
        put("max_tokens", text.length / 2 + 256 + if (thinkingOff == null) THINKING_HEADROOM else 0)
        put("system", ChatCleanup.systemPrompt(style, instruction, generalInstruction, language))
        thinkingOff?.let { type -> put("thinking", buildJsonObject { put("type", type) }) }
        put(
            "messages",
            buildJsonArray {
                ChatCleanup.EXAMPLES.forEach { (spoken, tidied) ->
                    add(turn("user", ChatCleanup.fenced(spoken)))
                    add(turn("assistant", tidied))
                }
                add(turn("user", ChatCleanup.fenced(text)))
            },
        )
    }

    /** What an error answer means, from its status and Anthropic's error type and message. */
    private fun failure(response: HttpResponse): SttFailure {
        val error = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]?.jsonObject }.getOrNull()
        val type = error?.get("type")?.jsonPrimitive?.contentOrNull.orEmpty()
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty()
        val code = response.code
        val problem = when {
            code == 401 || code == 403 -> Problem.KeyRejected
            code == 402 || type == "billing_error" || BILLING.containsMatchIn(message) -> Problem.NoCredit(service)
            // The address is Anthropic's own, so a 404 is the model ("model: claude-..."), as
            // is a 400 about the model field.
            code == 404 || (code == 400 && message.startsWith("model")) -> Problem.CleanupModelUnknown(model)
            code == 429 -> Problem.RateLimited
            // 529: overloaded; 500 and 504 also for its own time limits.
            code >= 500 -> Problem.ServiceDown(code)
            else -> Problem.CleanupFailed(code)
        }
        return SttFailure(problem, "HTTP $code ${type.ifEmpty { "-" }}: ${Redact.text(message).take(200)}")
    }

    companion object {
        /** The Messages API version Amber is written against: no sampling settings in it. */
        const val API_VERSION = "2026-10-21"

        private const val THINKING_HEADROOM = 1024

        private val NORMAL_ENDS = setOf("end_turn", "stop_sequence")

        /** How Anthropic says the account cannot pay: credit used up, a spend limit reached. */
        private val BILLING = Regex("credit balance|spend limit|usage limit|billing", RegexOption.IGNORE_CASE)

        private val json = Json { ignoreUnknownKeys = true }

        /** Beside the other endpoints of the address: `/v1/messages`. */
        fun messagesUrl(baseUrl: String): String = OpenAiCompatibleEngine.apiRoot(baseUrl) + "/messages"

        private fun turn(role: String, content: String) = buildJsonObject {
            put("role", role)
            put("content", content)
        }

        private class Reply(val text: String, val stopReason: String?)

        /** Every text block of the answer in order; thinking blocks, if any, are left out. */
        private fun replyOf(body: String): Reply {
            val message = json.parseToJsonElement(body).jsonObject
            val blocks = message["content"]!!.jsonArray
            val text = blocks.map { it.jsonObject }
                .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                .joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            return Reply(text, message["stop_reason"]?.jsonPrimitive?.contentOrNull)
        }
    }
}
