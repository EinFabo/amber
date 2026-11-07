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

    /** Empty means ready to use. Each entry is worded for the user by the app (ui/Texts.kt). */
    fun validate(config: EngineConfig, hasApiKey: Boolean): List<ConfigIssue> = buildList {
        when (config.kind) {
            EngineKind.ANDROID_ON_DEVICE -> Unit

            EngineKind.LOCAL_SHERPA, EngineKind.WHISPER_CPP -> {
                if (config.modelId.isNullOrBlank()) add(ConfigIssue.NoModelSelected)
            }

            EngineKind.ELEVENLABS_REALTIME -> add(ConfigIssue.NotSupported)

            EngineKind.OPENAI_COMPATIBLE -> {
                addAll(validateUrl(config))
                if (config.modelId.isNullOrBlank()) add(ConfigIssue.ModelNameMissing)
                when {
                    // A key for another host stays there; it has to be given again for this one.
                    config.apiKeyAlias != null && hasApiKey && !KeyBinding.allows(config) ->
                        add(ConfigIssue.KeyForOtherAddress(config.keyHost.orEmpty()))
                    !hasApiKey && Servers.keyRequired(config) -> add(ConfigIssue.ApiKeyMissing)
                }
                if (config.requireEnclave && !EnginePresets.isEnclaveHost(config.baseUrl)) {
                    add(ConfigIssue.EnclaveAddressOnly(EnginePresets.TINFOIL_BASE_URL))
                }
            }
        }

        // Whisper and an Amber server decide between several languages themselves, and any other
        // server gets no language for a list (OpenAiCompatibleEngine.languageField), so it detects.
        // The rest take one.
        val tags = if (config.kind == EngineKind.WHISPER_CPP || config.kind == EngineKind.OPENAI_COMPATIBLE) {
            config.languageTag?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        } else {
            listOfNotNull(config.languageTag)
        }
        tags?.filterNot(::isPlausibleLanguageTag)?.forEach { tag ->
            add(ConfigIssue.InvalidLanguageCode(tag))
        }
    }

    fun isReady(config: EngineConfig, hasApiKey: Boolean): Boolean =
        validate(config, hasApiKey).isEmpty()

    /** What is wrong with the address alone; empty when requests may go to it. */
    fun addressProblems(config: EngineConfig): List<ConfigIssue> = validateUrl(config)

    private fun validateUrl(config: EngineConfig): List<ConfigIssue> {
        val url = config.baseUrl?.trim()
        if (url.isNullOrBlank()) return listOf(ConfigIssue.AddressMissing)

        val expectedSchemes = if (config.kind == EngineKind.ELEVENLABS_REALTIME) {
            // Streaming runs over a WebSocket, so ws:// and wss:// are the right schemes
            // there and http:// would be the mistake.
            listOf("ws://", "wss://")
        } else {
            listOf("http://", "https://")
        }

        return buildList {
            if (expectedSchemes.none { url.startsWith(it, ignoreCase = true) }) {
                add(ConfigIssue.AddressScheme(expectedSchemes))
                return@buildList
            }
            val afterScheme = url.substringAfter("//")
            if (afterScheme.isBlank() || afterScheme.startsWith("/")) {
                add(ConfigIssue.AddressNoHost)
            }
            if (url.any { it.isWhitespace() }) {
                add(ConfigIssue.AddressHasSpace)
            }
            // Plain http only inside the user's own network: a recording should not cross the
            // internet unencrypted. CleartextGuard holds the same rule at request time, on the
            // address a name actually resolved to.
            if (url.startsWith("http://", ignoreCase = true)) {
                val host = runCatching { java.net.URI(url).host }.getOrNull()
                if (!ServerProviders.isPrivateHost(host)) {
                    add(ConfigIssue.HttpOnlyInOwnNetwork)
                }
            }
        }
    }

    /** Deliberately loose: a language-tag check must not reject valid exotic tags. */
    private fun isPlausibleLanguageTag(tag: String): Boolean =
        Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$").matches(tag)
}
