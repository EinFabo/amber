package app.amethyst.core.engine

import app.amethyst.core.settings.EngineConfig

/**
 * Says what an engine configuration is still missing, before any audio is recorded.
 *
 * Without this, a base URL typed without a scheme ("api.groq.com/v1") fails inside OkHttp and
 * surfaces as "no connection" - which sends the user looking at their network instead of at
 * the one character they left out. Every problem that can be spotted from the configuration
 * alone should be named as that problem.
 */
object EngineConfigValidator {

    /** Empty means ready to use. Each entry is a user-facing sentence. */
    fun validate(config: EngineConfig, hasApiKey: Boolean): List<String> = buildList {
        when (config.kind) {
            EngineKind.ANDROID_ON_DEVICE -> Unit

            EngineKind.LOCAL_SHERPA, EngineKind.WHISPER_CPP -> {
                if (config.modelId.isNullOrBlank()) add("No model is selected.")
            }

            EngineKind.ELEVENLABS_REALTIME -> add("ElevenLabs Realtime is not supported.")

            EngineKind.OPENAI_COMPATIBLE -> {
                addAll(validateUrl(config))
                if (config.modelId.isNullOrBlank()) add("The model name is missing.")
                if (config.apiKeyAlias != null && !hasApiKey) add("The API key is missing.")
                if (config.requireEnclave && !EnginePresets.isEnclaveHost(config.baseUrl)) {
                    add("This engine only sends to its verifiable enclave address (${EnginePresets.TINFOIL_BASE_URL}).")
                }
            }
        }

        // Only Whisper decides between several languages itself; every other engine takes one.
        val tags = if (config.kind == EngineKind.WHISPER_CPP) {
            config.languageTag?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        } else {
            listOfNotNull(config.languageTag)
        }
        tags?.filterNot(::isPlausibleLanguageTag)?.forEach { tag ->
            add("\"$tag\" is not a valid language code (e.g. de-DE or de).")
        }
    }

    fun isReady(config: EngineConfig, hasApiKey: Boolean): Boolean =
        validate(config, hasApiKey).isEmpty()

    private fun validateUrl(config: EngineConfig): List<String> {
        val url = config.baseUrl?.trim()
        if (url.isNullOrBlank()) return listOf("The base URL is missing.")

        val expectedSchemes = if (config.kind == EngineKind.ELEVENLABS_REALTIME) {
            // Streaming runs over a WebSocket, so ws:// and wss:// are the right schemes
            // there and http:// would be the mistake.
            listOf("ws://", "wss://")
        } else {
            listOf("http://", "https://")
        }

        return buildList {
            if (expectedSchemes.none { url.startsWith(it, ignoreCase = true) }) {
                add("The base URL must start with ${expectedSchemes.joinToString(" or ")}.")
                return@buildList
            }
            val afterScheme = url.substringAfter("//")
            if (afterScheme.isBlank() || afterScheme.startsWith("/")) {
                add("The base URL has no host.")
            }
            if (url.any { it.isWhitespace() }) {
                add("The base URL contains a space.")
            }
            // Plain http only inside the user's own network; Android blocks the rest anyway,
            // and a recording should not cross the internet unencrypted.
            if (url.startsWith("http://", ignoreCase = true)) {
                val host = runCatching { java.net.URI(url).host }.getOrNull()
                if (!ServerProviders.isPrivateHost(host)) {
                    add("http:// only works for servers in your own network - use https://.")
                }
            }
        }
    }

    /** Deliberately loose: a language-tag check must not reject valid exotic tags. */
    private fun isPlausibleLanguageTag(tag: String): Boolean =
        Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$").matches(tag)
}
