package app.amethyst.core

import app.amethyst.core.engine.EngineConfigValidator
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.settings.BubbleSettings
import app.amethyst.core.settings.CaptureSettings
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppProfile
import app.amethyst.core.settings.AppProfiles
import app.amethyst.core.settings.WritingStyle
import app.amethyst.core.settings.SettingsCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsTest {

    @Test
    fun `defaults start on the engine that needs no setup`() {
        val settings = AmethystSettings.defaults()
        assertEquals(EnginePresets.ANDROID_ON_DEVICE.id, settings.activeEngineId)
        val active = assertNotNull(settings.activeEngine)
        assertEquals(EngineKind.ANDROID_ON_DEVICE, active.kind)
        assertNull(active.apiKeyAlias, "the on-device engine must not ask for a key")
    }

    @Test
    fun `presets that need a key get an alias, never the key itself`() {
        val config = EngineConfig.fromPreset(EnginePresets.TINFOIL)
        assertEquals("key.tinfoil-whisper", config.apiKeyAlias)
        assertEquals("https://inference.tinfoil.sh/v1", config.baseUrl)
        // Voxtral: more accurate here and about a hundredth of Whisper's per-request price.
        assertEquals("voxtral-small-24b", config.modelId)
    }

    @Test
    fun `settings survive a json round trip`() {
        val original = AmethystSettings.defaults()
            .withEngine(EngineConfig.fromPreset(EnginePresets.TINFOIL, "de-DE"))
            .copy(activeEngineId = EnginePresets.TINFOIL.id)

        val decoded = SettingsCodec.decode(SettingsCodec.encode(original))
        assertEquals(original, decoded)
        assertEquals(2, decoded.engines.size)
    }

    @Test
    fun `unknown fields from a newer build are ignored`() {
        val raw = """{"activeEngineId":"android-on-device","engines":[],"somethingNew":42}"""
        assertEquals("android-on-device", SettingsCodec.decode(raw).activeEngineId)
    }

    @Test
    fun `corrupt settings fall back to defaults instead of crashing`() {
        assertEquals(AmethystSettings.defaults(), SettingsCodec.decode("}{ not json"))
    }

    @Test
    fun `withEngine replaces an existing entry rather than duplicating it`() {
        val tinfoil = EngineConfig.fromPreset(EnginePresets.TINFOIL)
        val settings = AmethystSettings.defaults()
            .withEngine(tinfoil)
            .withEngine(tinfoil.copy(modelId = "whisper-large-v3"))

        assertEquals(2, settings.engines.size)
        assertEquals("whisper-large-v3", settings.engines.last().modelId)
    }

    @Test
    fun `auto-stop off pushes the silence timeout out of reach`() {
        val vad = CaptureSettings(autoStopOnSilence = false).toVadConfig()
        assertEquals(Int.MAX_VALUE, vad.silenceTimeoutMs)

        val auto = CaptureSettings(autoStopOnSilence = true, silenceTimeoutMs = 750).toVadConfig()
        assertEquals(750, auto.silenceTimeoutMs)
    }

    @Test
    fun `capture settings carry the pre-roll into the vad config`() {
        val vad = CaptureSettings(preRollMs = 420).toVadConfig(frameMs = 20)
        assertEquals(420, vad.preRollMs)
        assertTrue(vad.maxDurationMs > 0)
        // The pre-roll is also the calibration window: 420ms / 20ms per frame.
        assertEquals(21, vad.calibrationFrames)
    }

    @Test
    fun `calibration window stays within sane bounds`() {
        assertEquals(3, CaptureSettings(preRollMs = 0).toVadConfig().calibrationFrames)
        assertEquals(50, CaptureSettings(preRollMs = 5_000).toVadConfig().calibrationFrames)
    }
}

class CaptureTimeoutTest {

    @Test
    fun `the watchdog always outlasts the recording`() {
        // A two minute recording cap with a one minute watchdog would kill the dictation
        // just as it was about to be transcribed.
        val settings = CaptureSettings(maxDurationMs = 120_000, totalTimeoutMs = 60_000)
        assertEquals(180_000L, settings.effectiveTotalTimeoutMs())
    }

    @Test
    fun `a generous watchdog is kept as configured`() {
        val settings = CaptureSettings(maxDurationMs = 30_000, totalTimeoutMs = 300_000)
        assertEquals(300_000L, settings.effectiveTotalTimeoutMs())
    }

    @Test
    fun `defaults leave room for a slow server`() {
        val settings = CaptureSettings()
        assertTrue(
            settings.effectiveTotalTimeoutMs() >= settings.maxDurationMs + 60_000L,
            "got ${settings.effectiveTotalTimeoutMs()}",
        )
    }

    @Test
    fun `the bubble is on by default but can be switched off`() {
        assertTrue(BubbleSettings().enabled)
        assertFalse(BubbleSettings(enabled = false).enabled)
    }

    @Test
    fun `version 1 whisper engines stop being forced to german`() {
        val whisper = EngineConfig.fromPreset(EnginePresets.LOCAL_WHISPER, "de-DE")
        val android = EngineConfig.fromPreset(EnginePresets.ANDROID_ON_DEVICE, "de-DE")
        val old = AmethystSettings(engines = listOf(whisper, android), schemaVersion = 1)

        val migrated = SettingsCodec.decode(SettingsCodec.encode(old))

        assertEquals("de,en", migrated.engines.first { it.id == whisper.id }.languageTag)
        assertEquals("de-DE", migrated.engines.first { it.id == android.id }.languageTag)
        assertEquals(AmethystSettings.SCHEMA_VERSION, migrated.schemaVersion)
    }

    @Test
    fun `a language the user set on whisper survives the migration`() {
        val whisper = EngineConfig.fromPreset(EnginePresets.LOCAL_WHISPER, "fr")
        val old = AmethystSettings(engines = listOf(whisper), schemaVersion = 1)

        assertEquals("fr", SettingsCodec.decode(SettingsCodec.encode(old)).engines.single().languageTag)
    }

    @Test
    fun `a tinfoil engine on the retired per-model host moves to the router`() {
        val old = AmethystSettings(
            engines = listOf(
                EngineConfig.fromPreset(EnginePresets.TINFOIL)
                    .copy(baseUrl = "https://whisper-large-v3-turbo.inf.tinfoil.sh/v1", modelId = "whisper-large-v3-turbo"),
            ),
            schemaVersion = 2,
        )
        val migrated = SettingsCodec.decode(SettingsCodec.encode(old)).engines.single()
        assertEquals("https://inference.tinfoil.sh/v1", migrated.baseUrl)
        assertEquals("whisper-large-v3-turbo", migrated.modelId)
    }

    @Test
    fun `settings from before profiles get the default profiles`() {
        val old = """{"activeEngineId":"android-on-device","engines":[],"schemaVersion":3}"""
        val decoded = SettingsCodec.decode(old)
        assertEquals(AppProfiles.DEFAULTS, decoded.profiles)
        assertEquals(WritingStyle.CASUAL, AppProfiles.forPackage(decoded.profiles, "com.whatsapp")?.style)
    }

    @Test
    fun `a profile is replaced in place, a new one appended`() {
        val changed = AppProfiles.upsert(AppProfiles.DEFAULTS, AppProfile("com.whatsapp", "WhatsApp", WritingStyle.FORMAL))
        assertEquals(AppProfiles.DEFAULTS.size, changed.size)
        assertEquals(WritingStyle.FORMAL, AppProfiles.forPackage(changed, "com.whatsapp")?.style)
        assertEquals(AppProfiles.DEFAULTS.size + 1, AppProfiles.upsert(changed, AppProfile("x.y", "X")).size)
    }

    @Test
    fun `a tinfoil engine refuses any address but its enclave`() {
        val tinfoil = EngineConfig.fromPreset(EnginePresets.TINFOIL)
        assertTrue(tinfoil.requireEnclave)
        assertTrue(EngineConfigValidator.validate(tinfoil, hasApiKey = true).isEmpty())
        for (url in listOf("https://api.tinfoil.sh/v1", "http://inference.tinfoil.sh/v1", "https://inference.tinfoil.sh.evil.example/v1")) {
            assertFalse(EngineConfigValidator.isReady(tinfoil.copy(baseUrl = url), hasApiKey = true), url)
        }
    }

    @Test
    fun `tinfoil engines saved before version 5 get to require the enclave`() {
        val old = AmethystSettings(
            engines = listOf(EngineConfig.fromPreset(EnginePresets.TINFOIL).copy(requireEnclave = false)),
            schemaVersion = 4,
        )
        assertTrue(SettingsCodec.decode(SettingsCodec.encode(old)).engines.single().requireEnclave)
    }

    @Test
    fun `stored elevenlabs engines are dropped, and the active one falls back`() {
        val old = AmethystSettings(
            engines = listOf(EngineConfig.fromPreset(EnginePresets.ANDROID_ON_DEVICE), EngineConfig.fromPreset(EnginePresets.ELEVENLABS)),
            activeEngineId = EnginePresets.ELEVENLABS.id,
            schemaVersion = 5,
        )
        val migrated = SettingsCodec.decode(SettingsCodec.encode(old))
        assertEquals(listOf(EnginePresets.ANDROID_ON_DEVICE.id), migrated.engines.map { it.id })
        assertEquals(EnginePresets.ANDROID_ON_DEVICE.id, migrated.activeEngineId)
        assertTrue(EnginePresets.all.none { it.id == EnginePresets.ELEVENLABS.id }, "no longer offered")
    }

    @Test
    fun `plain http only inside the own network`() {
        val own = EngineConfig.fromPreset(EnginePresets.SELF_HOSTED).copy(modelId = "whisper")
        for (url in listOf("http://192.168.1.20:8000/v1", "http://10.0.0.5/v1", "http://nas.local:9000", "http://localhost:8080")) {
            assertTrue(EngineConfigValidator.isReady(own.copy(baseUrl = url), hasApiKey = false), url)
        }
        for (url in listOf("http://example.com/v1", "http://8.8.8.8/v1")) {
            assertFalse(EngineConfigValidator.isReady(own.copy(baseUrl = url), hasApiKey = false), url)
        }
        assertTrue(EngineConfigValidator.isReady(own.copy(baseUrl = "https://example.com/v1"), hasApiKey = false))
    }

    @Test
    fun `server providers are recognised by host`() {
        assertEquals(ServerProviders.TINFOIL, ServerProviders.forUrl(EnginePresets.TINFOIL_BASE_URL))
        assertEquals(ServerProviders.GROQ, ServerProviders.forUrl(EnginePresets.GROQ.baseUrl))
        assertEquals(ServerProviders.OPENAI, ServerProviders.forUrl(EnginePresets.OPENAI.baseUrl))
        assertEquals(ServerProviders.CUSTOM, ServerProviders.forUrl("http://192.168.1.20:8000/v1"))
        // Every preset's default model is one its provider offers.
        for (preset in listOf(EnginePresets.TINFOIL, EnginePresets.GROQ, EnginePresets.OPENAI)) {
            assertNotNull(ServerProviders.forUrl(preset.baseUrl).speechModel(preset.modelId), preset.id)
        }
    }

    @Test
    fun `a host name starting like an ipv6 prefix is not private`() {
        for (host in listOf("fdn-transcribe.example.com", "fe80.example.org", "fc.example.net", "8.8.8.8", "example.com")) {
            assertFalse(ServerProviders.isPrivateHost(host), host)
        }
        for (host in listOf("nas", "raspberrypi", "box.ts.net", "fd12:3456::1", "fe80::1", "::1", "100.101.1.2", "server.home")) {
            assertTrue(ServerProviders.isPrivateHost(host), host)
        }
    }
}
