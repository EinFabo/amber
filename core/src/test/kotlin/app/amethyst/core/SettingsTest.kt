package app.amethyst.core

import app.amethyst.core.engine.AmberCatalog
import app.amethyst.core.engine.EngineConfigValidator
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.OpenAiCompatibleEngine
import app.amethyst.core.settings.BubbleSettings
import app.amethyst.core.settings.CaptureSettings
import app.amethyst.core.settings.FallbackMode
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppCleanup
import app.amethyst.core.settings.AppCleanupMode
import app.amethyst.core.settings.AppProfile
import app.amethyst.core.settings.AppProfiles
import app.amethyst.core.settings.WritingStyle
import app.amethyst.core.settings.SettingsCodec
import app.amethyst.core.settings.StoredCatalog
import app.amethyst.core.settings.UpdateSettings
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
        assertTrue(settings.effectiveTotalTimeoutMs() > 120_000L)
    }

    @Test
    fun `a generous watchdog is kept as configured`() {
        val settings = CaptureSettings(maxDurationMs = 10_000, totalTimeoutMs = 300_000)
        assertEquals(300_000L, settings.effectiveTotalTimeoutMs())
    }

    @Test
    fun `a server that times out still leaves the phone time to take over`() {
        // Recording, the server's whole time limit, then a cold local model: a minute of it.
        for (max in listOf(30_000, 60_000, 120_000)) {
            val settings = CaptureSettings(maxDurationMs = max)
            val serverGivesUp = max + OpenAiCompatibleEngine.requestTimeoutMs(max.toLong())
            assertTrue(settings.effectiveTotalTimeoutMs() >= serverGivesUp + 60_000L, "max $max: ${settings.effectiveTotalTimeoutMs()}")
        }
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
    fun `schema 9 turns the per-app cleanup switch into a choice`() {
        val old = """{"activeEngineId":"android-on-device","engines":[],"profiles":[
            {"packageName":"a","label":"A","cleanup":false},
            {"packageName":"b","label":"B","cleanup":true},
            {"packageName":"c","label":"C"}],"schemaVersion":8}"""
        val profiles = SettingsCodec.decode(old).profiles
        assertEquals(listOf(AppCleanupMode.OFF, AppCleanupMode.DEFAULT, AppCleanupMode.DEFAULT), profiles.map { it.appCleanup.mode })
        @Suppress("DEPRECATION")
        assertTrue(profiles.all { it.cleanup == null })
    }

    @Test
    fun `schema 10 keeps amber catalogs, and older settings start without`() {
        assertEquals(10, AmethystSettings.SCHEMA_VERSION)
        val old = """{"activeEngineId":"self-hosted","engines":[{"id":"self-hosted","kind":"OPENAI_COMPATIBLE",
            "displayName":"Own server","baseUrl":"http://192.168.1.20:8765/k3y/v1","modelId":"acft-large-v3-turbo-q8_0"}],"schemaVersion":9}"""
        val migrated = SettingsCodec.decode(old)
        assertTrue(migrated.serverCatalogs.isEmpty())
        assertEquals(AmethystSettings.SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals("acft-large-v3-turbo-q8_0", migrated.engines.single().modelId)
    }

    @Test
    fun `a stored amber catalog survives a round trip, so it works offline and on a cold start`() {
        val own = EngineConfig.fromPreset(EnginePresets.SELF_HOSTED).copy(baseUrl = "http://192.168.1.20:8765/k3y/v1")
        val pc = assertNotNull(AmberCatalog.parse(AMBER_PC_MODELS))
        val settings = AmethystSettings.defaults().withEngine(own)
            .copy(serverCatalogs = mapOf(own.id to StoredCatalog.of(own.baseUrl!!, pc)))
        val decoded = SettingsCodec.decode(SettingsCodec.encode(settings))
        assertEquals(settings, decoded)
        assertEquals(pc, decoded.catalogFor(own))
    }

    @Test
    fun `a catalog stored before upload formats takes wav, with nothing to migrate`() {
        assertEquals(10, AmethystSettings.SCHEMA_VERSION)
        val baseUrl = "http://192.168.1.20:8765/k3y/v1"
        val raw = """{"activeEngineId":"self-hosted","engines":[{"id":"self-hosted","kind":"OPENAI_COMPATIBLE",
            "displayName":"Own server","baseUrl":"$baseUrl","modelId":"acft-large-v3-turbo-q8_0"}],
            "serverCatalogs":{"self-hosted":{"address":"${AmberCatalog.fingerprint(baseUrl)}","service":"amber-pc",
            "version":"0.4.2","speech":[{"id":"acft-large-v3-turbo-q8_0","label":"Large v3 Turbo","recommended":true}]}},
            "schemaVersion":10}"""
        val decoded = SettingsCodec.decode(raw)
        val catalog = assertNotNull(decoded.catalogFor(decoded.engines.single()))
        assertEquals(listOf("wav"), catalog.audio)
        assertFalse(catalog.takesOpus)
        assertEquals("acft-large-v3-turbo-q8_0", catalog.speech.single().id)
    }

    @Test
    fun `the upload formats a server takes survive a round trip`() {
        val own = EngineConfig.fromPreset(EnginePresets.SELF_HOSTED).copy(baseUrl = "http://192.168.1.20:8765/k3y/v1")
        val pc = assertNotNull(AmberCatalog.parse(AMBER_PC_MODELS)).copy(audio = listOf("wav", "opus"))
        val settings = AmethystSettings.defaults().withEngine(own)
            .copy(serverCatalogs = mapOf(own.id to StoredCatalog.of(own.baseUrl!!, pc)))
        val decoded = SettingsCodec.decode(SettingsCodec.encode(settings))
        assertEquals(listOf("wav", "opus"), assertNotNull(decoded.catalogFor(own)).audio)
    }

    @Test
    fun `a per-app cleanup model survives a round trip`() {
        val choice = CleanupChoice("tinfoil", "gemma4-31b")
        val settings = AmethystSettings.defaults().copy(
            profiles = listOf(AppProfile("md.obsidian", "Obsidian", appCleanup = AppCleanup(AppCleanupMode.MODEL, choice))),
        )
        val decoded = SettingsCodec.decode(SettingsCodec.encode(settings))
        assertEquals(AppCleanup(AppCleanupMode.MODEL, choice), decoded.profiles.single().appCleanup)
    }

    @Test
    fun `the cleanup addition for every app survives a round trip, and older settings start without`() {
        val settings = AmethystSettings.defaults().copy(cleanupInstruction = "Zahlen als Ziffern.")
        assertEquals("Zahlen als Ziffern.", SettingsCodec.decode(SettingsCodec.encode(settings)).cleanupInstruction)
        assertNull(SettingsCodec.decode("""{"activeEngineId":"android-on-device","engines":[],"schemaVersion":10}""").cleanupInstruction)
        assertEquals(10, AmethystSettings.SCHEMA_VERSION, "a new field with a default needs no schema step")
    }

    @Test
    fun `editing a profile changes only that one`() {
        val edited = AppProfiles.edit(AppProfiles.DEFAULTS, "com.whatsapp") { it.copy(style = WritingStyle.FORMAL) }
        assertEquals(WritingStyle.FORMAL, AppProfiles.forPackage(edited, "com.whatsapp")?.style)
        assertEquals(AppProfiles.DEFAULTS.drop(1), edited.drop(1))
    }

    @Test
    fun `editing a removed profile does not bring it back`() {
        val removed = AppProfiles.DEFAULTS.filterNot { it.packageName == "com.whatsapp" }
        assertEquals(removed, AppProfiles.edit(removed, "com.whatsapp") { it.copy(instruction = "late save") })
    }

    @Test
    fun `settings from before the update check check by default, with nothing to migrate`() {
        assertEquals(10, AmethystSettings.SCHEMA_VERSION)
        for (raw in listOf(
            """{"activeEngineId":"android-on-device","engines":[],"schemaVersion":10}""",
            """{"activeEngineId":"android-on-device","engines":[],"schemaVersion":7}""",
        )) {
            val updates = SettingsCodec.decode(raw).updates
            assertEquals(UpdateSettings(), updates)
            assertTrue(updates.enabled)
            assertEquals(0L, updates.lastCheckedAt)
            assertFalse(updates.lastCheckOffline)
            assertNull(updates.latestVersion)
        }
    }

    @Test
    fun `the update check's state survives a round trip`() {
        val updates = UpdateSettings(
            enabled = false,
            lastCheckedAt = 1_800_000_000_000L,
            lastCheckFailed = true,
            lastCheckOffline = true,
            latestVersion = "0.24.0",
            latestUrl = "https://github.com/EinFabo/amber/releases/tag/v0.24.0",
            notifiedVersion = "0.24.0",
            dismissedVersion = "0.24.0",
        )
        val settings = AmethystSettings.defaults().copy(updates = updates)
        assertEquals(updates, SettingsCodec.decode(SettingsCodec.encode(settings)).updates)
    }

    @Test
    fun `voice commands start switched off, for old settings too`() {
        assertFalse(AmethystSettings.defaults().commands.enabled)
        val old = """{"activeEngineId":"android-on-device","engines":[],"schemaVersion":9}"""
        assertFalse(SettingsCodec.decode(old).commands.enabled)
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

    @Test
    fun `the own server keeps its address field even when it names a known host`() {
        val groq = EngineConfig.fromPreset(EnginePresets.GROQ)
        assertTrue(ServerProviders.addressFixed(groq))
        val own = EngineConfig.fromPreset(EnginePresets.SELF_HOSTED).copy(baseUrl = groq.baseUrl)
        assertFalse(ServerProviders.addressFixed(own))
        // Its models and prices still are the host's, as in the factory and the statistics.
        assertEquals(ServerProviders.GROQ, ServerProviders.forUrl(own.baseUrl))
    }

    @Test
    fun `an enclave host is verified on any scheme but only meets the requirement over https`() {
        assertEquals("tinfoilsh/confidential-model-router", EnginePresets.enclaveRepoFor("http://Inference.Tinfoil.sh/v1"))
        assertFalse(EnginePresets.isEnclaveHost("http://inference.tinfoil.sh/v1"))
        assertTrue(EnginePresets.isEnclaveHost(" https://inference.tinfoil.sh/v1"))
        assertNull(EnginePresets.enclaveRepoFor("https://api.groq.com/openai/v1"))
    }

    @Test
    fun `ipv6 addresses are judged by their groups, not their spelling`() {
        // fd::1 is 00fd::1 and fe8::1 is 0fe8::1: both on the internet.
        for (host in listOf("fd::1", "fe8::1", "2001:db8::1", "::ffff:8.8.8.8", "1:2:3:4:5:6:7:8:9", "fd00:::1", "fd00::g")) {
            assertFalse(ServerProviders.isPrivateHost(host), host)
        }
        for (host in listOf("[fd00::5]", "FE80::1%wlan0", "0:0:0:0:0:0:0:1", "::ffff:192.168.1.10", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")) {
            assertTrue(ServerProviders.isPrivateHost(host), host)
        }
    }

    @Test
    fun `schema 7 moves the active engine's cleanup out of the engine and keeps the fallback switch`() {
        val groq = EngineConfig.fromPreset(EnginePresets.GROQ).copy(cleanupModel = "openai/gpt-oss-20b")
        val old = AmethystSettings(
            activeEngineId = groq.id,
            engines = listOf(groq, EngineConfig.fromPreset(EnginePresets.LOCAL_WHISPER)),
            capture = CaptureSettings(localFallback = false),
            schemaVersion = 6,
        )
        val migrated = SettingsCodec.decode(SettingsCodec.encode(old))

        assertEquals(CleanupChoice(groq.id, "openai/gpt-oss-20b"), migrated.cleanup)
        assertEquals(FallbackMode.OFF, migrated.fallback.mode)
        assertTrue(migrated.engines.all { it.cleanupModel == null }, "the old per-engine field is emptied")
        assertEquals(AmethystSettings.SCHEMA_VERSION, migrated.schemaVersion)
    }

    @Test
    fun `an active local engine had no cleanup, and an untouched switch means automatic`() {
        val old = AmethystSettings(
            activeEngineId = EnginePresets.LOCAL_WHISPER.id,
            engines = listOf(EngineConfig.fromPreset(EnginePresets.LOCAL_WHISPER), EngineConfig.fromPreset(EnginePresets.GROQ).copy(cleanupModel = "x")),
            schemaVersion = 6,
        )
        val migrated = SettingsCodec.decode(SettingsCodec.encode(old))
        assertNull(migrated.cleanup)
        assertEquals(FallbackMode.AUTOMATIC, migrated.fallback.mode)
    }

    @Test
    fun `a fresh install has not seen the introduction`() {
        assertFalse(AmethystSettings.defaults().onboardingDone)
        assertFalse(SettingsCodec.decode("").onboardingDone)
    }

    @Test
    fun `settings from before version 8 count as introduced`() {
        val old = """{"activeEngineId":"android-on-device","engines":[],"schemaVersion":7}"""
        val migrated = SettingsCodec.decode(old)
        assertTrue(migrated.onboardingDone)
        assertEquals(AmethystSettings.SCHEMA_VERSION, migrated.schemaVersion)
    }

    @Test
    fun `version 8 settings keep their flag`() {
        val pending = SettingsCodec.encode(AmethystSettings.defaults())
        assertFalse(SettingsCodec.decode(pending).onboardingDone)
    }
}
