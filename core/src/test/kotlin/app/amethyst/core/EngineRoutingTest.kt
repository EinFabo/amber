package app.amethyst.core

import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.EngineRouting
import app.amethyst.core.engine.EngineRouting.NotUsable
import app.amethyst.core.engine.Fallback
import app.amethyst.core.engine.ReadyFallback
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppEngine
import app.amethyst.core.settings.AppEngineMode
import app.amethyst.core.settings.AppProfile
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.FallbackSettings
import app.amethyst.core.settings.SettingsCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineRoutingTest {

    private val android = EngineConfig.fromPreset(EnginePresets.ANDROID_ON_DEVICE, "de-DE")
    private val whisper = EngineConfig.fromPreset(EnginePresets.LOCAL_WHISPER, "de,en")
    private val groq = EngineConfig.fromPreset(EnginePresets.GROQ)
    private val allKeys: (String?) -> Boolean = { true }
    private val allModels: (EngineConfig) -> Boolean = { true }

    private fun settings(vararg profiles: AppProfile, active: String = android.id) = AmethystSettings.defaults(TEST_PHONE)
        .withEngine(android).withEngine(whisper).withEngine(groq)
        .copy(activeEngineId = active, profiles = profiles.toList())

    private fun keep(own: String?, mode: AppEngineMode = AppEngineMode.ENGINE) =
        AppProfile("com.google.android.keep", "Keep", appEngine = AppEngine(mode, own))

    private fun decide(
        settings: AmethystSettings,
        packageName: String? = "com.google.android.keep",
        hasKey: (String?) -> Boolean = allKeys,
        modelReady: (EngineConfig) -> Boolean = allModels,
    ) = EngineRouting.decide(settings, packageName, hasKey, modelReady)

    // --- the default ------------------------------------------------------------------------

    @Test
    fun `an app without a profile gets the speech recognition for every app`() {
        assertEquals(EngineRouting.Decision(android), decide(settings()))
    }

    @Test
    fun `no app known gets the default`() {
        assertEquals(EngineRouting.Decision(android), decide(settings(keep(whisper.id)), packageName = null))
    }

    @Test
    fun `an app set to as for every app gets the default, even with an engine remembered`() {
        assertEquals(EngineRouting.Decision(android), decide(settings(keep(whisper.id, AppEngineMode.DEFAULT))))
        assertEquals(EngineRouting.Decision(android), decide(settings(keep(null, AppEngineMode.DEFAULT))))
    }

    @Test
    fun `an own setting without an engine is the default`() {
        assertEquals(EngineRouting.Decision(android), decide(settings(keep(null))))
    }

    @Test
    fun `the default is taken as it is - its own problems are the dictation's to report`() {
        // Whisper as the default, its model not downloaded: still Whisper, which then says so.
        val decision = decide(settings(active = whisper.id), modelReady = { false })
        assertEquals(whisper, decision.engine)
        assertNull(decision.passedOver)
    }

    @Test
    fun `nothing chosen anywhere is no speech recognition`() {
        val none = settings().copy(activeEngineId = "gone")
        assertNull(decide(none).engine)
        assertNull(decide(none).line())
    }

    // --- the app's own ----------------------------------------------------------------------

    @Test
    fun `an app's own ready engine is used and says it came from the app`() {
        val decision = decide(settings(keep(whisper.id)))
        assertEquals(whisper, decision.engine)
        assertTrue(decision.fromApp)
        assertEquals("speech recognition ${whisper.displayName}, Keep's own", decision.line())
    }

    @Test
    fun `an app's own that is the default anyway still says so`() {
        val decision = decide(settings(keep(android.id)))
        assertEquals(android, decision.engine)
        assertTrue(decision.fromApp)
    }

    @Test
    fun `an app's own engine that was deleted falls back to the default and says so`() {
        val decision = decide(settings(keep("server-0a0b0c")))
        assertEquals(android, decision.engine)
        assertFalse(decision.fromApp)
        assertEquals(NotUsable.REMOVED, decision.passedOver)
        assertEquals("speech recognition ${android.displayName}: Keep's own was removed", decision.line())
    }

    @Test
    fun `an app's own engine without its key falls back to the default`() {
        val decision = decide(settings(keep(groq.id)), hasKey = { it != groq.apiKeyAlias })
        assertEquals(android, decision.engine)
        assertEquals(NotUsable.NOT_SET_UP, decision.passedOver)
        assertEquals("speech recognition ${android.displayName}: Keep's own (Groq) is not set up", decision.line())
    }

    @Test
    fun `an app's own model not on the phone falls back to the default`() {
        val decision = decide(settings(keep(whisper.id)), modelReady = { it.id != whisper.id })
        assertEquals(android, decision.engine)
        assertEquals(NotUsable.NO_MODEL, decision.passedOver)
    }

    @Test
    fun `an app's own engine switched off falls back to the default`() {
        val off = settings(keep(whisper.id)).withEngine(whisper.copy(enabled = false))
        val decision = decide(off)
        assertEquals(android, decision.engine)
        assertEquals(NotUsable.SWITCHED_OFF, decision.passedOver)
    }

    @Test
    fun `an app's own passed over with no default leaves none, and no line`() {
        val decision = decide(settings(keep("gone")).copy(activeEngineId = "gone too"))
        assertNull(decision.engine)
        assertNull(decision.line())
    }

    @Test
    fun `a log a release build keeps does not name the app`() {
        val line = decide(settings(keep(whisper.id))).line(namesApps = false)
        assertEquals("speech recognition ${whisper.displayName}, the app's own", line)
        assertFalse("Keep" in line!!)
        assertFalse("Keep" in decide(settings(keep("gone"))).line(namesApps = false)!!)
    }

    // --- the fallback works from the engine routed to ---------------------------------------

    @Test
    fun `the fallback for an app's own server is chosen against that server, not the default`() {
        // Keep dictates with Groq; the default is Android on the phone. Groq failing goes to
        // Whisper on the phone - the default would have had no fallback at all.
        val routed = decide(settings(keep(groq.id))).engine!!
        val trust = { config: EngineConfig -> if (config.id == groq.id) TrustLevel.CLOUD else TrustLevel.ON_DEVICE }
        val all = settings(keep(groq.id))
        assertEquals(listOf(whisper), Fallback.candidates(FallbackSettings(), all.engines, routed, trust))
        val prepared = ReadyFallback.engineToPrepare(all.copy(fallback = FallbackSettings(prepare = true)), routed, trust) { true }
        assertEquals(whisper, prepared)
    }

    // --- stored --------------------------------------------------------------------------------

    @Test
    fun `settings from before an app's own speech recognition decode to the default`() {
        // Schema 13, a profile written before the field existed.
        val old = """
            {"schemaVersion": 13, "activeEngineId": "android-on-device",
             "engines": [{"id": "android-on-device", "kind": "ANDROID_ON_DEVICE", "displayName": "Android"}],
             "profiles": [{"packageName": "com.whatsapp", "label": "WhatsApp", "style": "CASUAL"}]}
        """.trimIndent()
        val settings = SettingsCodec.decode(old, TEST_PHONE)
        assertEquals(AppEngine(), settings.profiles.single().appEngine)
        assertEquals("android-on-device", decide(settings, "com.whatsapp").engine?.id)
    }

    @Test
    fun `an app's own speech recognition survives the settings round trip`() {
        val settings = settings(keep(whisper.id))
        val back = SettingsCodec.decode(SettingsCodec.encode(settings), TEST_PHONE)
        assertEquals(AppEngine(AppEngineMode.ENGINE, whisper.id), back.profiles.single().appEngine)
    }
}
