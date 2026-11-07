package app.amethyst.core.engine

import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.EngineConfig

/**
 * Servers as one kind of speech recognition: added as often as wanted, each found out by its
 * address - Groq, OpenAI, an Amber PC or Amber Server, anything else that speaks the OpenAI
 * audio endpoint. Tinfoil stays its own entry: it is only ever its verified enclave.
 */
object Servers {

    /** A new server's id: opaque, so a server pointed elsewhere later is not named wrong by it. */
    fun newId(random: () -> Int = { (0..0xffffff).random() }, taken: Set<String>): String {
        while (true) {
            val id = "server-" + "%06x".format(random() and 0xffffff)
            if (id !in taken) return id
        }
    }

    /** A server with nothing filled in yet; its page asks for the address and the key. */
    fun newServer(id: String, languageTag: String?): EngineConfig = EngineConfig(
        id = id,
        kind = EngineKind.OPENAI_COMPATIBLE,
        displayName = EnginePresets.SERVER.displayName,
        languageTag = languageTag,
        apiKeyAlias = "key.$id",
    )

    /**
     * [settings] as they are stored: every server named after what it is, and a key without a
     * host bound to the address it is used with. Applied on every write.
     */
    fun normalized(settings: AmethystSettings): AmethystSettings {
        // Every server can take a key (an own server from before had no place for one).
        val bound = settings.engines
            .map { if (isServer(it) && it.apiKeyAlias == null) it.copy(apiKeyAlias = "key.${it.id}") else it }
            .map(KeyBinding::bindIfUnbound)
        return ServerNames.apply(if (bound == settings.engines) settings else settings.copy(engines = bound))
    }

    /** A server engine, not Tinfoil: the ones named by [ServerNames] and edited freely. */
    fun isServer(engine: EngineConfig): Boolean =
        engine.kind == EngineKind.OPENAI_COMPATIBLE && !engine.requireEnclave

    /** The known services a server's page offers as one tap: their address and first model. */
    val QUICK = listOf(
        Quick(ServerProviders.GROQ, EnginePresets.GROQ.baseUrl!!, EnginePresets.GROQ.modelId!!),
        Quick(ServerProviders.OPENAI, EnginePresets.OPENAI.baseUrl!!, EnginePresets.OPENAI.modelId!!),
    )

    data class Quick(val provider: ServerProvider, val baseUrl: String, val modelId: String)

    /**
     * The known service a pasted [key] belongs to, by its prefix - Groq's start with "gsk_",
     * OpenAI's with "sk-" - so a key alone fills in the address. Null for anything else.
     */
    fun quickForKey(key: String): Quick? {
        val k = key.trim()
        return when {
            k.startsWith("gsk_") -> QUICK.first { it.provider == ServerProviders.GROQ }
            k.startsWith("sk-") && !k.startsWith("sk-ant-") -> QUICK.first { it.provider == ServerProviders.OPENAI }
            else -> null
        }
    }

    /**
     * [engine] pointed at [quick]'s service: its address, and its recommended model unless the
     * one chosen is that service's too. The key stays bound to where it was given.
     */
    fun pointAt(engine: EngineConfig, quick: Quick): EngineConfig {
        val keep = engine.modelId?.takeIf { quick.provider.speechModel(it) != null }
        return engine.copy(baseUrl = quick.baseUrl, modelId = keep ?: quick.modelId)
    }

    /** Whether a key is needed at [engine]'s address: at a known service yes, at an own server it is optional. */
    fun keyRequired(engine: EngineConfig): Boolean =
        engine.apiKeyAlias != null && (engine.requireEnclave || ServerProviders.forUrl(engine.baseUrl) !== ServerProviders.CUSTOM)
}

/**
 * Which host an API key may go to. A key is bound when it is given ([EngineConfig.keyHost]); a
 * request to any other host goes without it, and the engine says the key must be entered again.
 * So a changed address - a typo, a pasted link, someone else's hand - never takes the key along.
 */
object KeyBinding {

    /** Whether [engine]'s key may go to its address: bound to that host, or not bound at all. */
    fun allows(engine: EngineConfig): Boolean {
        val bound = engine.keyHost ?: return true
        return bound.equals(EnginePresets.hostOf(engine.baseUrl), ignoreCase = true)
    }

    /** [engine] with its key bound to its current host, as when the key was just entered. */
    fun bind(engine: EngineConfig): EngineConfig = engine.copy(keyHost = EnginePresets.hostOf(engine.baseUrl))

    /** Binds a key that has no host yet to the address it is used with (schema 12, and keys stored without one). */
    fun bindIfUnbound(engine: EngineConfig): EngineConfig =
        if (engine.apiKeyAlias != null && engine.keyHost == null && EnginePresets.hostOf(engine.baseUrl) != null) bind(engine) else engine
}

/**
 * What a server is called: the name its Amber PC or Amber Server gives, a known service's
 * name, or its host - kept in [EngineConfig.displayName], so every place that names an engine
 * names a server the same way. Two servers with the same name get their host added.
 */
object ServerNames {

    /** [settings] with every server named after what it is now; the same object when nothing changed. */
    fun apply(settings: AmethystSettings): AmethystSettings {
        val servers = settings.engines.filter(Servers::isServer)
        if (servers.isEmpty()) return settings
        val plain = servers.associate { it.id to plainName(it, settings.catalogFor(it)) }
        val counts = plain.values.groupingBy { it }.eachCount()
        val engines = settings.engines.map { engine ->
            val name = plain[engine.id] ?: return@map engine
            val host = EnginePresets.hostOf(engine.baseUrl)
            val unique = if ((counts[name] ?: 0) > 1 && host != null && name != host) "$name ($host)" else name
            if (unique == engine.displayName) engine else engine.copy(displayName = unique)
        }
        return if (engines == settings.engines) settings else settings.copy(engines = engines)
    }

    /** "Amber PC", "Groq", "192.168.1.20" - or "Server" while it has no address. */
    fun plainName(engine: EngineConfig, catalog: AmberCatalog?): String {
        catalog?.let { return it.name }
        val provider = ServerProviders.forUrl(engine.baseUrl)
        if (provider !== ServerProviders.CUSTOM) return provider.name
        return EnginePresets.hostOf(engine.baseUrl)?.removePrefix("www.") ?: EnginePresets.SERVER.displayName
    }
}
