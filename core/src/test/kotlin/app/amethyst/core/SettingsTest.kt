package app.amethyst.core

import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.settings.BubbleSettings
import app.amethyst.core.settings.CaptureSettings
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.AmethystSettings
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
}
