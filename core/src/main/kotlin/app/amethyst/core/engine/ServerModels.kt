package app.amethyst.core.engine

import app.amethyst.core.net.HttpRequest
import app.amethyst.core.net.HttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What an own server's `GET /models` said: Amber's list, a plain OpenAI list, or nothing usable. */
sealed interface ModelList {
    data class Amber(val catalog: AmberCatalog) : ModelList

    /** Any other OpenAI-compatible server (Ollama, LM Studio, LocalAI ...): its model ids. */
    data class Plain(val ids: List<String>) : ModelList

    /** No answer, an error, or no model list. */
    data class Failed(val detail: String) : ModelList
}

/**
 * Asks an own server which models it has, in one request: an Amber PC or Amber Server answers
 * with its [AmberCatalog], any other OpenAI-compatible server with the plain `data` list.
 */
object ServerModels {

    private val json = Json { ignoreUnknownKeys = true }

    /** Never throws, except to cancel. */
    suspend fun fetch(transport: HttpTransport, baseUrl: String, apiKey: String?): ModelList {
        val request = HttpRequest(
            method = "GET",
            url = AmberCatalog.modelsUrl(baseUrl),
            headers = buildMap {
                if (!apiKey.isNullOrBlank()) put("Authorization", "Bearer $apiKey")
                put("Accept", "application/json")
            },
            timeoutMs = AmberCatalog.TIMEOUT_MS,
        )
        val response = try {
            transport.execute(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ModelList.Failed(e.javaClass.simpleName)
        }
        if (!response.isSuccessful) return ModelList.Failed("HTTP ${response.code}")
        return parse(response.bodyAsText())
    }

    /** [body] of a `GET /models`: Amber's list first, else the ids of an OpenAI list, in its order. */
    fun parse(body: String): ModelList {
        AmberCatalog.parse(body)?.let { return ModelList.Amber(it) }
        val data = runCatching { (json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonArray }.getOrNull()
            ?: return ModelList.Failed("not a model list")
        // A stranger's list: only plain ids, as on Amber's - no control or bidi characters, no
        // overlong names - and no more than a picker can hold.
        val ids = data.asSequence().mapNotNull { entry ->
            ((entry as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf(AmberCatalog::isPlainModelId)
        }.distinct().take(MAX_MODELS).toList()
        return ModelList.Plain(ids)
    }

    /** More than any server needs to offer for one cleanup (OpenRouter lists a few hundred). */
    const val MAX_MODELS = 400
}
