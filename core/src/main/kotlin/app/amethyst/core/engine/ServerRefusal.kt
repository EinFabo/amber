package app.amethyst.core.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * What a server said in the body of an error answer. The status alone misleads: a model the
 * service retired answers 404 like a wrong address, an account without credit 429 like too
 * many requests - and "check the address" or "wait a moment" sends the user the wrong way.
 *
 * Read are OpenAI's shape (`{"error":{"message","type","code"}}`, as Groq, Tinfoil and most
 * own servers send it), a bare `{"error":"..."}` (Ollama) and `{"detail":"..."}` (FastAPI:
 * Speaches). Anything else is plain text, searched as a message.
 */
internal object ServerRefusal {

    class Said(val code: String, val type: String, val message: String)

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): Said {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return Said("", "", body)
        val error = root["error"]
        return when {
            error is JsonObject -> Said(error.text("code"), error.text("type"), error.text("message"))
            error is JsonPrimitive && error.contentOrNull != null -> Said(root.text("code"), root.text("type"), error.content)
            else -> Said(root.text("code"), root.text("type"), root.text("detail").ifEmpty { root.text("message") })
        }
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /**
     * The problem an error answer with [status] and [body] names beyond its status, or null
     * when the status says all there is. [model] and [service] fill in the names.
     */
    fun problem(status: Int, body: String, model: String, service: String): Problem? {
        // A server that is down is down, whatever its error page mentions.
        if (status !in 400..499) return null
        val said = parse(body)
        val named = setOf(said.code.lowercase(), said.type.lowercase())
        return when {
            named.any { it in KEY_CODES } -> Problem.KeyRejected
            // A refused key stays a refused key, whatever else the answer mentions.
            status == 401 || status == 403 -> null
            status == 402 || named.any { it in CREDIT_CODES } || NO_CREDIT.containsMatchIn(said.message) -> Problem.NoCredit(service)
            named.any { it in MODEL_CODES } -> Problem.ModelNotOnServer(model, service)
            // Without a code: a 404 that speaks of the model is about the model - a wrong
            // address knows nothing of one. A 400 must say that the model is not there.
            status == 404 && said.message.contains("model", ignoreCase = true) -> Problem.ModelNotOnServer(model, service)
            status == 400 && MODEL_GONE.containsMatchIn(said.message) -> Problem.ModelNotOnServer(model, service)
            else -> null
        }
    }

    private val KEY_CODES = setOf("invalid_api_key", "incorrect_api_key", "invalid_authentication", "authentication_error")
    private val MODEL_CODES = setOf("model_not_found", "model_decommissioned", "model_deprecated", "model_not_available")
    private val CREDIT_CODES = setOf(
        "insufficient_quota", "billing_not_active", "billing_hard_limit_reached", "billing_error",
        "insufficient_credits", "insufficient_balance", "payment_required",
    )
    private val NO_CREDIT = Regex(
        "exceeded your current quota|insufficient (credit|balance|funds|quota)|credit balance|out of credits?|spend(ing)? limit",
        RegexOption.IGNORE_CASE,
    )
    private val MODEL_GONE = Regex(
        "model\\b.{0,80}\\b(not found|does not exist|doesn't exist|decommissioned|deprecated|no longer|not installed|not available|unknown)" +
            "|(unknown|no such|invalid) model",
        RegexOption.IGNORE_CASE,
    )
}
