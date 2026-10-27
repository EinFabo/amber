package app.nocturne.core

import app.nocturne.core.engine.EngineConfigValidator
import app.nocturne.core.engine.EngineKind
import app.nocturne.core.engine.EnginePresets
import app.nocturne.core.settings.EngineConfig
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineConfigValidatorTest {

    private fun openAiCompatible(
        baseUrl: String? = "https://api.groq.com/openai/v1",
        modelId: String? = "whisper-large-v3-turbo",
        apiKeyAlias: String? = "key.groq",
        languageTag: String? = "de-DE",
    ) = EngineConfig(
        id = "groq",
        kind = EngineKind.OPENAI_COMPATIBLE,
        displayName = "Groq",
        baseUrl = baseUrl,
        modelId = modelId,
        languageTag = languageTag,
        apiKeyAlias = apiKeyAlias,
    )

    @Test
    fun `a complete configuration is ready`() {
        assertTrue(EngineConfigValidator.isReady(openAiCompatible(), hasApiKey = true))
    }

    @Test
    fun `the on-device engine needs nothing at all`() {
        val config = EngineConfig.fromPreset(EnginePresets.ANDROID_ON_DEVICE)
        assertEquals(emptyList(), EngineConfigValidator.validate(config, hasApiKey = false))
    }

    @Test
    fun `a url without a scheme is named as such, not as a network problem`() {
        val missing = EngineConfigValidator.validate(
            openAiCompatible(baseUrl = "api.groq.com/openai/v1"),
            hasApiKey = true,
        )
        assertEquals(1, missing.size)
        assertContains(missing.single(), "http://")
    }

    @Test
    fun `a plain http url is fine for a self-hosted server`() {
        val config = openAiCompatible(
            baseUrl = "http://192.168.1.10:8000/v1",
            apiKeyAlias = null,
        )
        assertTrue(EngineConfigValidator.isReady(config, hasApiKey = false))
    }

    @Test
    fun `a self-hosted engine without a key alias is not asked for a key`() {
        val missing = EngineConfigValidator.validate(
            openAiCompatible(apiKeyAlias = null),
            hasApiKey = false,
        )
        assertEquals(emptyList(), missing)
    }

    @Test
    fun `missing url model and key are all reported at once`() {
        val missing = EngineConfigValidator.validate(
            openAiCompatible(baseUrl = null, modelId = null),
            hasApiKey = false,
        )
        assertEquals(3, missing.size, "expected url, model and key: $missing")
        assertTrue(missing.any { it.contains("Base-URL") })
        assertTrue(missing.any { it.contains("Modellname") })
        assertTrue(missing.any { it.contains("API-Schlüssel") })
    }

    @Test
    fun `a url with a scheme but no host is rejected`() {
        val missing = EngineConfigValidator.validate(
            openAiCompatible(baseUrl = "https:///v1"),
            hasApiKey = true,
        )
        assertTrue(missing.any { it.contains("Host") }, "got $missing")
    }

    @Test
    fun `a pasted url with a stray space is caught`() {
        val missing = EngineConfigValidator.validate(
            openAiCompatible(baseUrl = "https://api.groq.com /v1"),
            hasApiKey = true,
        )
        assertTrue(missing.any { it.contains("Leerzeichen") }, "got $missing")
    }

    @Test
    fun `the streaming engine wants a websocket scheme`() {
        val http = EngineConfig(
            id = "elevenlabs-realtime",
            kind = EngineKind.ELEVENLABS_REALTIME,
            displayName = "ElevenLabs",
            baseUrl = "https://api.elevenlabs.io/v1/speech-to-text/realtime",
            modelId = "scribe_v2_realtime",
            apiKeyAlias = "key.elevenlabs",
        )
        assertContains(EngineConfigValidator.validate(http, hasApiKey = true).single(), "wss://")

        val wss = http.copy(baseUrl = "wss://api.elevenlabs.io/v1/speech-to-text/realtime")
        assertTrue(EngineConfigValidator.isReady(wss, hasApiKey = true))
    }

    @Test
    fun `a local model engine only needs a model`() {
        val config = EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET)
        assertTrue(EngineConfigValidator.isReady(config, hasApiKey = false))

        val withoutModel = config.copy(modelId = null)
        assertContains(
            EngineConfigValidator.validate(withoutModel, hasApiKey = false).single(),
            "Modell",
        )
    }

    @Test
    fun `an implausible language tag is flagged but exotic valid ones are not`() {
        assertFalse(
            EngineConfigValidator.isReady(openAiCompatible(languageTag = "deutsch bitte"), true),
        )
        assertTrue(EngineConfigValidator.isReady(openAiCompatible(languageTag = "de"), true))
        assertTrue(EngineConfigValidator.isReady(openAiCompatible(languageTag = "zh-Hans-CN"), true))
    }

    @Test
    fun `an absent language tag means automatic detection, not an error`() {
        assertTrue(EngineConfigValidator.isReady(openAiCompatible(languageTag = null), true))
    }
}
