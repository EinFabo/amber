package app.amethyst.core.engine

import app.amethyst.core.audio.AudioEncoder
import app.amethyst.core.engine.EnginePresets.ModelChoice
import app.amethyst.core.net.HttpRequest
import app.amethyst.core.net.HttpTransport
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.StoredCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.security.MessageDigest

/**
 * What Amber for Windows ("Amber PC") or Amber Server offers, from its `GET /models`: an
 * OpenAI model list with an `amber` object at the top level - the mark of Amber, which no
 * other server has - and one in each entry saying what the model is for. An id goes back
 * unchanged as `model` in the transcription form and the chat request; a server that no longer
 * has it answers with its default.
 */
data class AmberCatalog(
    val service: String,
    val version: String,
    val speech: List<ModelChoice>,
    val cleanup: List<ModelChoice>,
    /**
     * The upload formats it takes, of those this app knows ([AUDIO_WAV], [AUDIO_OPUS]) - WAV
     * alone for an Amber that names none.
     */
    val audio: List<String> = listOf(AUDIO_WAV),
) {
    /** Whether a recording may go up as Ogg Opus instead of WAV. */
    val takesOpus: Boolean get() = AUDIO_OPUS in audio

    /** "Amber PC" or "Amber Server"; a service only a later Amber knows is plain "Amber". */
    val name: String
        get() = when (service) {
            SERVICE_PC -> "Amber PC"
            SERVICE_SERVER -> "Amber Server"
            else -> "Amber"
        }

    /** The machine that answers for a model it no longer lists: "the PC", "the server". */
    val machine: String get() = if (service == SERVICE_PC) "the PC" else "the server"

    companion object {
        const val SERVICE_PC = "amber-pc"
        const val SERVICE_SERVER = "amber-server"

        /** The upload formats of `amber.audio`: WAV, and Opus in an Ogg container. */
        const val AUDIO_WAV = "wav"
        const val AUDIO_OPUS = "opus"
        private val KNOWN_AUDIO = setOf(AUDIO_WAV, AUDIO_OPUS)

        /** A list is a small answer from a machine nearby; a slow one is not worth waiting for. */
        const val TIMEOUT_MS = 5_000L

        private const val SPEECH = "speech"
        private const val CLEANUP = "cleanup"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The catalog in [body], or null for anything that is not Amber's list: another
         * server's model list (no top-level `amber` object) as much as an error page. Entries
         * of a kind this app does not know, or without an id, are skipped; extra fields are
         * ignored. The first `default` of a kind becomes its recommended model. Of the upload
         * formats in `amber.audio` only those this app knows count; none at all means WAV.
         */
        fun parse(body: String): AmberCatalog? {
            val root = parseObject(body) ?: return null
            val amber = root["amber"] as? JsonObject ?: return null
            val byKind = mapOf(SPEECH to mutableListOf<ModelChoice>(), CLEANUP to mutableListOf())
            (root["data"] as? JsonArray).orEmpty().forEach { element ->
                val entry = element as? JsonObject ?: return@forEach
                val id = entry.string("id")?.takeIf { it.isNotBlank() } ?: return@forEach
                val about = entry["amber"] as? JsonObject ?: return@forEach
                val list = byKind[about.string("kind")] ?: return@forEach
                if (list.any { it.id == id }) return@forEach
                val default = (about["default"] as? JsonPrimitive)?.booleanOrNull == true
                list += ModelChoice(
                    id = id,
                    label = about.string("label")?.takeIf { it.isNotBlank() } ?: id,
                    note = about.string("note").orEmpty(),
                    recommended = default && list.none { it.recommended },
                )
            }
            return AmberCatalog(
                service = amber.string("service").orEmpty(),
                version = amber.string("version").orEmpty(),
                speech = byKind.getValue(SPEECH),
                cleanup = byKind.getValue(CLEANUP),
                audio = audioFormats(amber),
            )
        }

        private fun audioFormats(amber: JsonObject): List<String> {
            val named = (amber["audio"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }
                .filter { it in KNOWN_AUDIO }
                .distinct()
            return named.ifEmpty { listOf(AUDIO_WAV) }
        }

        /** Where the list is: next to the transcription endpoint, however the address is typed. */
        fun modelsUrl(baseUrl: String): String = OpenAiCompatibleEngine.apiRoot(baseUrl) + "/models"

        /**
         * One address, however it is spelled, as a SHA-256 in hex: what a stored catalog keeps
         * instead of the address, which carries the server's key in its path.
         */
        fun fingerprint(baseUrl: String): String {
            val url = modelsUrl(baseUrl)
            // Scheme and host ignore case ("Http://" from a keyboard); the path, key and all, does not.
            val scheme = url.indexOf("://")
            val path = if (scheme < 0) -1 else url.indexOf('/', scheme + 3)
            val normalized = if (path < 0 || '@' in url.substring(0, path)) url else url.substring(0, path).lowercase() + url.substring(path)
            return MessageDigest.getInstance("SHA-256").digest(normalized.encodeToByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        /**
         * Asks the server at [baseUrl] for its list, with the engine's key as a transcription
         * sends it. Never throws, except to cancel.
         */
        suspend fun fetch(transport: HttpTransport, baseUrl: String, apiKey: String?): CatalogFetch {
            val request = HttpRequest(
                method = "GET",
                url = modelsUrl(baseUrl),
                headers = buildMap {
                    if (!apiKey.isNullOrBlank()) put("Authorization", "Bearer $apiKey")
                    put("Accept", "application/json")
                },
                timeoutMs = TIMEOUT_MS,
            )
            val response = try {
                transport.execute(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return CatalogFetch.Failed(e.javaClass.simpleName)
            }
            if (!response.isSuccessful) return CatalogFetch.Failed("HTTP ${response.code}")
            val body = response.bodyAsText()
            parse(body)?.let { return CatalogFetch.Found(it) }
            // Only a model list proves another server; a captive portal's page proves nothing.
            return if (parseObject(body)?.get("data") is JsonArray) CatalogFetch.NotAmber else CatalogFetch.Failed("not a model list")
        }

        private fun parseObject(body: String): JsonObject? =
            runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
    }
}

/** What asking a server for its list came to. */
sealed interface CatalogFetch {
    data class Found(val catalog: AmberCatalog) : CatalogFetch

    /** It answered with a model list without Amber's mark: some other server. */
    data object NotAmber : CatalogFetch

    /** No answer, an error, or something unreadable - the stored list stays. */
    data class Failed(val detail: String) : CatalogFetch
}

/**
 * Which engines are asked for a list, and what an answer does to the settings. A catalog is
 * kept per engine and address, so the lists work offline and on a cold start.
 */
object AmberCatalogs {

    /**
     * An own server (no known host) with an address its key may go to: the rules a
     * transcription follows - http only inside the own network, nothing beside an enclave
     * engine's enclave. The model may still be missing: the list is how one gets picked.
     */
    fun isCandidate(engine: EngineConfig): Boolean =
        engine.kind == EngineKind.OPENAI_COMPATIBLE &&
            !engine.requireEnclave &&
            !engine.baseUrl.isNullOrBlank() &&
            ServerProviders.forUrl(engine.baseUrl) === ServerProviders.CUSTOM &&
            EngineConfigValidator.addressProblems(engine).isEmpty()

    /**
     * [outcome] of asking [baseUrl] for [engineId]'s list: a catalog replaces the stored one,
     * a server that is not Amber clears it, a failure keeps it. An answer for an address the
     * engine has left since changes nothing, a removed engine's catalog goes. Returns
     * [settings] itself when nothing changed, so nothing needs writing.
     */
    fun apply(settings: AmethystSettings, engineId: String, baseUrl: String, outcome: CatalogFetch): AmethystSettings {
        val engine = settings.engines.firstOrNull { it.id == engineId }
            ?: return if (engineId in settings.serverCatalogs) settings.copy(serverCatalogs = settings.serverCatalogs - engineId) else settings
        if (engine.baseUrl.isNullOrBlank() || AmberCatalog.fingerprint(engine.baseUrl) != AmberCatalog.fingerprint(baseUrl)) return settings
        val catalogs = when (outcome) {
            is CatalogFetch.Found -> settings.serverCatalogs + (engineId to StoredCatalog.of(baseUrl, outcome.catalog))
            CatalogFetch.NotAmber -> settings.serverCatalogs - engineId
            is CatalogFetch.Failed -> return settings
        }
        return if (catalogs == settings.serverCatalogs) settings else settings.copy(serverCatalogs = catalogs)
    }

    /**
     * How [engine] may compress its recordings: with [encoder], when it is an own server whose
     * stored list (from the address it points at now) takes Ogg Opus. Null - WAV as always -
     * for every other engine: Groq, OpenAI, Tinfoil and plain own servers have no list, and
     * an enclave engine is never a candidate. [onRefused] runs when the server answers a
     * compressed upload with 400 anyway; it should record [compressionRefused].
     */
    fun compressedUpload(settings: AmethystSettings, engine: EngineConfig, encoder: AudioEncoder?, onRefused: () -> Unit): CompressedUpload? {
        if (encoder == null || !isCandidate(engine)) return null
        if (settings.catalogFor(engine)?.takesOpus != true) return null
        return CompressedUpload(encoder, onRefused)
    }

    /**
     * [engineId]'s server at [baseUrl] answered a compressed upload with 400 although its list
     * offered it: its stored list keeps everything but Opus, so recordings go up as WAV until
     * the list is fetched again and replaces it. Returns [settings] itself when there is
     * nothing to change - an address the engine has left, a list without Opus.
     */
    fun compressionRefused(settings: AmethystSettings, engineId: String, baseUrl: String): AmethystSettings {
        val stored = settings.serverCatalogs[engineId] ?: return settings
        if (!stored.isFor(baseUrl) || AmberCatalog.AUDIO_OPUS !in stored.audio) return settings
        val audio = (stored.audio - AmberCatalog.AUDIO_OPUS).ifEmpty { listOf(AmberCatalog.AUDIO_WAV) }
        return settings.copy(serverCatalogs = settings.serverCatalogs + (engineId to stored.copy(audio = audio)))
    }
}

/**
 * At most one list a minute per engine and address, so opening a page again and again does not
 * ask each time; [claim] with force (a passed connection test) asks anyway, and a new address
 * is asked at once. A failed fetch counts too: an unreachable server is not hammered. While a
 * fetch runs, until [finished], nothing else is asked of the same engine and address.
 */
class CatalogThrottle(
    private val intervalMs: Long = 60_000,
    private val clock: () -> Long,
) {
    private val last = HashMap<String, Long>()
    private val running = HashSet<String>()

    @Synchronized
    fun claim(engineId: String, baseUrl: String, force: Boolean = false): Boolean {
        val key = key(engineId, baseUrl)
        if (key in running) return false
        val now = clock()
        val previous = last[key]
        if (!force && previous != null && now - previous < intervalMs) return false
        last[key] = now
        running += key
        return true
    }

    /** The fetch [claim] allowed has its answer. */
    @Synchronized
    fun finished(engineId: String, baseUrl: String) {
        running -= key(engineId, baseUrl)
    }

    private fun key(engineId: String, baseUrl: String) = engineId + "\n" + AmberCatalog.fingerprint(baseUrl)
}
