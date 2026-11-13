package app.amethyst.core.engine

import app.amethyst.core.engine.EnginePresets.ModelChoice
import app.amethyst.core.net.LocalNetwork
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.EngineConfig

/** The two ways Amber talks to a chat model. */
enum class ChatDialect { OPENAI, ANTHROPIC }

/**
 * Every server engine - Tinfoil, Groq, OpenAI, your own - goes through the same backend: the
 * OpenAI audio endpoint for recognition and the chat endpoint for cleanup. What differs is
 * only what a provider offers, and that lives here: its speech and chat models with their
 * prices, and whether its address is fixed. An unknown host is an own server, where the user
 * names the models - unless it is an Amber PC or Amber Server, which lists its own.
 */
data class ServerProvider(
    val id: String,
    val name: String,
    val hosts: Set<String>,
    val speechModels: List<ModelChoice>,
    val cleanupModels: List<ModelChoice>,
    /** Known services have one address; only an own server's can be edited. */
    val fixedAddress: Boolean,
    /** Models to pick from: a known service's, or the ones an Amber server listed. */
    val hasModelList: Boolean = hosts.isNotEmpty(),
    /**
     * For a list the server sent itself, the machine that answers for a model no longer on it.
     * Null for the lists this app keeps.
     */
    val listedBy: AmberMachine? = null,
    /**
     * Its chat endpoint streams a reply (`"stream": true`, server-sent events), so the ring can
     * follow a cleanup as it is written. Amber PC's answers in one piece; an own server's is
     * unknown.
     */
    val streamsChat: Boolean = false,
    /** How its chat endpoint is spoken to: the OpenAI dialect, or Anthropic's own Messages API. */
    val chat: ChatDialect = ChatDialect.OPENAI,
    /**
     * Whether it recognizes speech at all. Claude does not: a server there is for the cleanup
     * only ([Servers.cleanupOnly]), never a speech recognition to choose.
     */
    val recognizes: Boolean = true,
    /** Where its API keys are made, for "Get a key"; null for a server of one's own. */
    val keyPage: String? = null,
) {
    /** The lists came from the server, so they are all it takes; nothing else is typed. */
    val listsItself: Boolean get() = listedBy != null

    fun speechModel(id: String?): ModelChoice? = speechModels.firstOrNull { it.id == id }
    fun cleanupModel(id: String?): ModelChoice? = cleanupModels.firstOrNull { it.id == id }

    /**
     * A chosen model [id] the server's own list ([models]) no longer has, said as such. It is
     * still sent, and the server answers with its default. Null when it is listed, when none
     * is chosen, and for the lists this app keeps - a model typed for Groq is the user's own.
     */
    fun notOfferedBy(id: String?, models: List<ModelChoice>): AmberMachine? {
        val by = listedBy ?: return null
        val chosen = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (models.any { it.id == chosen }) return null
        return by
    }
}

object ServerProviders {

    val TINFOIL = ServerProvider(
        id = "tinfoil",
        name = "Tinfoil",
        hosts = EnginePresets.ENCLAVE_HOSTS,
        speechModels = EnginePresets.TINFOIL_SPEECH_MODELS,
        cleanupModels = EnginePresets.TINFOIL_CLEANUP_MODELS,
        fixedAddress = true,
        streamsChat = true,
        keyPage = "https://tinfoil.sh",
    )

    /*
     * Groq and OpenAI prices from their public pages, October 2026. Groq bills audio per hour
     * (at least 10 s a request), OpenAI per minute.
     */
    val GROQ = ServerProvider(
        id = "groq",
        name = "Groq",
        hosts = setOf("api.groq.com"),
        speechModels = listOf(
            ModelChoice("whisper-large-v3-turbo", "Whisper large-v3-turbo", "fast · \$0.04 per hour", recommended = true, usdPerMinute = 0.04 / 60),
            ModelChoice("whisper-large-v3", "Whisper large-v3", "a little more accurate · \$0.11 per hour", usdPerMinute = 0.111 / 60),
        ),
        cleanupModels = listOf(
            // Reasons first; "low" keeps that short and cheap.
            ModelChoice(
                "openai/gpt-oss-20b", "gpt-oss 20B", "cheap and quick",
                recommended = true, usdPerMIn = 0.075, usdPerMOut = 0.30, extraParams = mapOf("reasoning_effort" to "low"),
            ),
            ModelChoice("llama-3.3-70b-versatile", "Llama 3.3 70B", "no reasoning, steady"),
            ModelChoice("llama-3.1-8b-instant", "Llama 3.1 8B", "fastest, simplest"),
        ),
        fixedAddress = true,
        streamsChat = true,
        keyPage = "https://console.groq.com/keys",
    )

    val OPENAI = ServerProvider(
        id = "openai",
        name = "OpenAI",
        hosts = setOf("api.openai.com"),
        speechModels = listOf(
            ModelChoice("gpt-4o-mini-transcribe", "GPT-4o mini Transcribe", "\$0.003 per minute", recommended = true, usdPerMinute = 0.003),
            ModelChoice("gpt-4o-transcribe", "GPT-4o Transcribe", "most accurate · \$0.006 per minute", usdPerMinute = 0.006),
            ModelChoice("whisper-1", "Whisper", "\$0.006 per minute", usdPerMinute = 0.006),
        ),
        // Not the GPT-5 family: reasoning models refuse temperature 0 and max_tokens.
        cleanupModels = listOf(
            ModelChoice("gpt-4.1-nano", "GPT-4.1 nano", "cheapest · ~\$0.0001", recommended = true, usdPerMIn = 0.10, usdPerMOut = 0.40),
            ModelChoice("gpt-4o-mini", "GPT-4o mini", "~\$0.0001", usdPerMIn = 0.15, usdPerMOut = 0.60),
            ModelChoice("gpt-4.1-mini", "GPT-4.1 mini", "smarter · ~\$0.0004", usdPerMIn = 0.40, usdPerMOut = 1.60),
        ),
        fixedAddress = true,
        streamsChat = true,
        keyPage = "https://platform.openai.com/api-keys",
    )

    /*
     * Claude, from Anthropic's models overview (platform.claude.com/docs/en/about-claude/models/overview),
     * November 2026: Haiku 5.5 $0.10 / $0.50 per million tokens (prompts up to 100K tokens),
     * Sonnet 5.5 $2 / $10. Spoken to in its own Messages API: its OpenAI layer refuses the
     * cleanup's temperature 0. Thinking before the answer is switched off, each model its way.
     */
    val ANTHROPIC = ServerProvider(
        id = "anthropic",
        name = "Claude",
        hosts = setOf("api.anthropic.com"),
        speechModels = emptyList(),
        cleanupModels = listOf(
            ModelChoice(
                "claude-haiku-5-5", "Claude Haiku 5.5", "fast and cheap · ~\$0.0001",
                recommended = true, usdPerMIn = 0.10, usdPerMOut = 0.50, thinkingOff = "disabled",
            ),
            ModelChoice(
                "claude-sonnet-5-5", "Claude Sonnet 5.5", "smarter · ~\$0.002",
                usdPerMIn = 2.0, usdPerMOut = 10.0, thinkingOff = "between_tools",
            ),
        ),
        fixedAddress = true,
        chat = ChatDialect.ANTHROPIC,
        recognizes = false,
        keyPage = "https://platform.claude.com/settings/keys",
    )

    /** Anything else: the user's own server, models typed by hand. */
    val CUSTOM = ServerProvider(
        id = "custom",
        name = "Own server",
        hosts = emptySet(),
        speechModels = emptyList(),
        cleanupModels = emptyList(),
        fixedAddress = false,
    )

    val known = listOf(TINFOIL, GROQ, OPENAI, ANTHROPIC)

    fun forUrl(baseUrl: String?): ServerProvider {
        val host = EnginePresets.hostOf(baseUrl) ?: return CUSTOM
        return known.firstOrNull { host in it.hosts } ?: CUSTOM
    }

    /**
     * A known service by its host; else, with the [catalog] an Amber PC or Amber Server sent,
     * a provider built from it; else an own server.
     */
    fun forUrl(baseUrl: String?, catalog: AmberCatalog?): ServerProvider {
        val byHost = forUrl(baseUrl)
        return if (byHost === CUSTOM && catalog != null) fromCatalog(catalog) else byHost
    }

    /** [engine]'s provider, with the catalog stored for it at its current address. */
    fun forEngine(engine: EngineConfig, settings: AmethystSettings): ServerProvider =
        forUrl(engine.baseUrl, settings.catalogFor(engine))

    /** The models an Amber server listed, no prices - it runs on its owner's machine. */
    fun fromCatalog(catalog: AmberCatalog) = ServerProvider(
        id = catalog.service.ifBlank { "amber" },
        name = catalog.name,
        hosts = emptySet(),
        speechModels = catalog.speech,
        cleanupModels = catalog.cleanup,
        fixedAddress = false,
        hasModelList = true,
        listedBy = catalog.machine,
    )

    /**
     * Whether the address stays out of the user's hands: only for Tinfoil, which is only ever
     * its verified enclave. Every server's address is its owner's to change - pointed at a
     * known host it gets that provider's models and prices, and its key stays bound to the
     * host it was given for ([KeyBinding]).
     */
    fun addressFixed(config: EngineConfig): Boolean = config.requireEnclave

    /**
     * http:// only to addresses in the user's own network - a server in the same Wi-Fi or on
     * the phone itself. Anything on the internet must be https. Address literals are judged
     * by [LocalNetwork], the same rule the request-time [app.amethyst.core.net.CleartextGuard]
     * applies to the address a name resolved to.
     */
    fun isPrivateHost(host: String?): Boolean {
        val h = host?.lowercase()?.trim('[', ']')?.takeIf { it.isNotEmpty() } ?: return false
        LocalNetwork.parseLiteral(h)?.let { return LocalNetwork.isLocal(it) }
        // A broken IPv6 literal, or a bare number - an IPv4 address to some resolvers.
        if (':' in h || h.all { it in '0'..'9' || it == '.' }) return false
        // A single label ("nas", "raspberrypi") only resolves inside the local network.
        if ('.' !in h) return true
        return LOCAL_SUFFIXES.any { h.endsWith(it) }
    }

    /** Names that only exist in a home network - or in a tailnet, which is encrypted itself. */
    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".localdomain", ".home.arpa", ".ts.net")
}
