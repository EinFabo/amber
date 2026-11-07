package app.amethyst.core.engine

import app.amethyst.core.settings.AmethystSettings
import java.net.URI
import java.net.URLDecoder

/**
 * An Amber PC's or Amber Server's address and its key, kept apart as with OpenAI: the address
 * without the key, the key sent as `Authorization: Bearer` (both servers take it). Older Ambers
 * showed one address with the key in its path (`http://<host>:<port>/<key>/v1`); newer ones a
 * pairing link (`amber://server?url=...&key=...`). Either, pasted or stored, is split here.
 */
object AmberAddress {

    /** An address without the key, and the key. */
    data class Split(val baseUrl: String, val key: String)

    /** Amber's keys: 20 characters from 31 letters and digits without look-alikes. */
    private val KEY = Regex("^[a-hj-km-np-z2-9]{20}$")

    /**
     * [typed] as address and key: a pairing link, or an address whose first path segment is an
     * Amber key followed by nothing or `v1...`. Null for anything else - an ordinary address.
     */
    fun split(typed: String?): Split? {
        val text = typed?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        return if (uri.scheme.equals("amber", ignoreCase = true)) fromLink(uri) else fromPath(uri)
    }

    private fun fromLink(uri: URI): Split? {
        if (!uri.authority.equals("server", ignoreCase = true) && !uri.host.equals("server", ignoreCase = true)) return null
        val query = uri.rawQuery ?: return null
        val params = query.split('&').mapNotNull { pair ->
            val (name, value) = pair.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            name to runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
        }.toMap()
        val url = params["url"]?.trim()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) } ?: return null
        val key = params["key"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // An old address inside the link still loses its key.
        return Split(fromPath(URI(url))?.baseUrl ?: url, key)
    }

    private fun fromPath(uri: URI): Split? {
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val authority = uri.rawAuthority?.takeIf { it.isNotEmpty() && '@' !in it } ?: return null
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        val key = segments.firstOrNull()?.takeIf { KEY.matches(it) } ?: return null
        val rest = segments.drop(1)
        if (rest.isNotEmpty() && rest.first() != "v1") return null
        val path = if (rest.isEmpty()) "/v1" else "/" + rest.joinToString("/")
        return Split("$scheme://$authority$path", key)
    }

    /** A stored server's key, moved out of its address: which engine, under which alias, which key. */
    data class Move(val engineId: String, val alias: String, val key: String)

    /**
     * Every server whose stored address carries an Amber key in its path, and whose model list
     * says it is Amber (so no other server's path is touched): the address without the key, the
     * key bound to its host under the engine's alias, the list kept for the new address. The
     * key itself is returned in [Move]s, for the app to put into its encrypted store first.
     */
    fun movePathKeys(settings: AmethystSettings): Pair<AmethystSettings, List<Move>> {
        val moves = mutableListOf<Move>()
        var catalogs = settings.serverCatalogs
        val engines = settings.engines.map { engine ->
            if (!Servers.isServer(engine) || settings.catalogFor(engine) == null) return@map engine
            val split = split(engine.baseUrl) ?: return@map engine
            val alias = engine.apiKeyAlias ?: "key.${engine.id}"
            moves += Move(engine.id, alias, split.key)
            catalogs[engine.id]?.let { stored ->
                catalogs = catalogs + (engine.id to stored.copy(address = AmberCatalog.fingerprint(split.baseUrl)))
            }
            KeyBinding.bind(engine.copy(baseUrl = split.baseUrl, apiKeyAlias = alias))
        }
        if (moves.isEmpty()) return settings to emptyList()
        return settings.copy(engines = engines, serverCatalogs = catalogs) to moves
    }
}
