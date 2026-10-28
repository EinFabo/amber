package app.amethyst.core.engine

import app.amethyst.core.engine.EnginePresets.ModelChoice
import java.net.URI

/**
 * Every server engine - Tinfoil, Groq, OpenAI, your own - goes through the same backend: the
 * OpenAI audio endpoint for recognition and the chat endpoint for cleanup. What differs is
 * only what a provider offers, and that lives here: its speech and chat models with their
 * prices, and whether its address is fixed. An unknown host is an own server, where the user
 * names the models.
 */
data class ServerProvider(
    val id: String,
    val name: String,
    val hosts: Set<String>,
    val speechModels: List<ModelChoice>,
    val cleanupModels: List<ModelChoice>,
    /** Known services have one address; only an own server's can be edited. */
    val fixedAddress: Boolean,
) {
    val known: Boolean get() = hosts.isNotEmpty()

    fun speechModel(id: String?): ModelChoice? = speechModels.firstOrNull { it.id == id }
    fun cleanupModel(id: String?): ModelChoice? = cleanupModels.firstOrNull { it.id == id }
}

object ServerProviders {

    val TINFOIL = ServerProvider(
        id = "tinfoil",
        name = "Tinfoil",
        hosts = EnginePresets.ENCLAVE_HOSTS,
        speechModels = EnginePresets.TINFOIL_SPEECH_MODELS,
        cleanupModels = EnginePresets.TINFOIL_CLEANUP_MODELS,
        fixedAddress = true,
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

    val known = listOf(TINFOIL, GROQ, OPENAI)

    fun forUrl(baseUrl: String?): ServerProvider {
        val host = runCatching { URI(baseUrl.orEmpty().trim()).host?.lowercase() }.getOrNull() ?: return CUSTOM
        return known.firstOrNull { host in it.hosts } ?: CUSTOM
    }

    /**
     * http:// only to addresses in the user's own network - a server in the same Wi-Fi or on
     * the phone itself. Anything on the internet must be https.
     */
    fun isPrivateHost(host: String?): Boolean {
        val h = host?.lowercase()?.trim('[', ']') ?: return false
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
        val parts = h.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size == 4 && h.count { it == '.' } == 3) {
            val (a, b) = parts
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 100 && b in 64..127)
        }
        return h == "::1" || h.startsWith("fd") || h.startsWith("fe80")
    }
}
