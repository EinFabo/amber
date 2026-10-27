package app.nocturne.core.engine

import app.nocturne.core.settings.EngineConfig

/**
 * Says what an engine configuration is still missing, before any audio is recorded.
 *
 * Without this, a base URL typed without a scheme ("api.groq.com/v1") fails inside OkHttp and
 * surfaces as "no connection" - which sends the user looking at their network instead of at
 * the one character they left out. Every problem that can be spotted from the configuration
 * alone should be named as that problem.
 */
object EngineConfigValidator {

    /** Empty means ready to use. Each entry is a user-facing German sentence. */
    fun validate(config: EngineConfig, hasApiKey: Boolean): List<String> = buildList {
        when (config.kind) {
            EngineKind.ANDROID_ON_DEVICE -> Unit

            EngineKind.LOCAL_SHERPA -> {
                if (config.modelId.isNullOrBlank()) add("Es ist kein Modell ausgewählt.")
            }

            EngineKind.OPENAI_COMPATIBLE, EngineKind.ELEVENLABS_REALTIME -> {
                addAll(validateUrl(config))
                if (config.modelId.isNullOrBlank()) add("Der Modellname fehlt.")
                if (config.apiKeyAlias != null && !hasApiKey) add("Der API-Schlüssel fehlt.")
            }
        }

        if (config.languageTag != null && !isPlausibleLanguageTag(config.languageTag)) {
            add("\"${config.languageTag}\" ist kein gültiges Sprachkürzel (z.B. de-DE oder de).")
        }
    }

    fun isReady(config: EngineConfig, hasApiKey: Boolean): Boolean =
        validate(config, hasApiKey).isEmpty()

    private fun validateUrl(config: EngineConfig): List<String> {
        val url = config.baseUrl?.trim()
        if (url.isNullOrBlank()) return listOf("Die Base-URL fehlt.")

        val expectedSchemes = if (config.kind == EngineKind.ELEVENLABS_REALTIME) {
            // Streaming runs over a WebSocket, so ws:// and wss:// are the right schemes
            // there and http:// would be the mistake.
            listOf("ws://", "wss://")
        } else {
            listOf("http://", "https://")
        }

        return buildList {
            if (expectedSchemes.none { url.startsWith(it, ignoreCase = true) }) {
                add("Die Base-URL muss mit ${expectedSchemes.joinToString(" oder ")} beginnen.")
                return@buildList
            }
            val afterScheme = url.substringAfter("//")
            if (afterScheme.isBlank() || afterScheme.startsWith("/")) {
                add("In der Base-URL fehlt der Host.")
            }
            if (url.any { it.isWhitespace() }) {
                add("Die Base-URL enthält ein Leerzeichen.")
            }
        }
    }

    /** Deliberately loose: a language-tag check must not reject valid exotic tags. */
    private fun isPlausibleLanguageTag(tag: String): Boolean =
        Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$").matches(tag)
}
