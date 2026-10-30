package app.amethyst.core

import app.amethyst.core.engine.CleanupRouting
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppCleanup
import app.amethyst.core.settings.AppCleanupMode
import app.amethyst.core.settings.AppProfile
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.EngineConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CleanupRoutingTest {

    private val tinfoil = EngineConfig.fromPreset(EnginePresets.TINFOIL)
    private val groq = EngineConfig.fromPreset(EnginePresets.GROQ)
    private val whisper = EngineConfig.fromPreset(EnginePresets.LOCAL_WHISPER)
    private val flash = CleanupChoice(tinfoil.id, "deepseek-v4-1-flash")
    private val gemma = CleanupChoice(tinfoil.id, "gemma4-31b")
    private val onGroq = CleanupChoice(groq.id, "openai/gpt-oss-20b")
    private val allKeys: (String?) -> Boolean = { true }

    private fun settings(global: CleanupChoice?, vararg profiles: AppProfile) = AmethystSettings.defaults()
        .withEngine(tinfoil).withEngine(groq).withEngine(whisper)
        .copy(cleanup = global, profiles = profiles.toList())

    private fun app(mode: AppCleanupMode, choice: CleanupChoice? = null) =
        AppProfile("md.obsidian", "Obsidian", appCleanup = AppCleanup(mode, choice))

    private fun decide(settings: AmethystSettings, fellBackFrom: String? = null, hasKey: (String?) -> Boolean = allKeys) =
        CleanupRouting.decide(settings, "md.obsidian", fellBackFrom, hasKey)

    @Test
    fun `an app without a profile gets the cleanup for every app`() {
        assertEquals(CleanupRouting.Decision(flash), decide(settings(flash)))
    }

    @Test
    fun `nothing chosen anywhere means no cleanup and nothing to say`() {
        assertEquals(CleanupRouting.Decision(null), decide(settings(null, app(AppCleanupMode.DEFAULT))))
    }

    @Test
    fun `off in an app overrides the cleanup for every app`() {
        val decision = decide(settings(flash, app(AppCleanupMode.OFF)))
        assertNull(decision.choice)
        assertEquals("cleanup off for Obsidian", decision.note)
    }

    @Test
    fun `an app's own model runs even when the cleanup for every app is off`() {
        assertEquals(CleanupRouting.Decision(gemma), decide(settings(null, app(AppCleanupMode.MODEL, gemma))))
        assertEquals(CleanupRouting.Decision(gemma), decide(settings(flash, app(AppCleanupMode.MODEL, gemma))))
    }

    @Test
    fun `an own model on a removed engine falls back to the default and says so`() {
        val decision = decide(settings(flash, app(AppCleanupMode.MODEL, CleanupChoice("gone", "x"))))
        assertEquals(flash, decision.choice)
        assertNotNull(decision.note)
        assertTrue("Obsidian" in decision.note!!)
    }

    @Test
    fun `an own model whose engine lost its key falls back to the default`() {
        val noGroqKey: (String?) -> Boolean = { it != groq.apiKeyAlias }
        val decision = decide(settings(flash, app(AppCleanupMode.MODEL, onGroq)), hasKey = noGroqKey)
        assertEquals(flash, decision.choice)
        assertNotNull(decision.note)
    }

    @Test
    fun `an own model that is not ready and no default means no cleanup, with a note`() {
        val decision = decide(settings(null, app(AppCleanupMode.MODEL, CleanupChoice("gone", "x"))))
        assertNull(decision.choice)
        assertNotNull(decision.note)
    }

    @Test
    fun `a default on an engine without a key is no cleanup, with a note`() {
        val decision = decide(settings(onGroq), hasKey = { false })
        assertNull(decision.choice)
        assertNotNull(decision.note)
    }

    @Test
    fun `a blank model or a local engine is not ready`() {
        val s = settings(null)
        assertFalse(CleanupRouting.ready(s, CleanupChoice(tinfoil.id, " "), allKeys))
        assertFalse(CleanupRouting.ready(s, CleanupChoice(whisper.id, "ggml-base"), allKeys))
        assertTrue(CleanupRouting.ready(s, flash, allKeys))
    }

    @Test
    fun `the server recognition fell back from is not asked to clean up`() {
        val decision = decide(settings(flash), fellBackFrom = tinfoil.id)
        assertNull(decision.choice)
        assertNotNull(decision.note)
        assertNull(decide(settings(null, app(AppCleanupMode.MODEL, gemma)), fellBackFrom = tinfoil.id).choice)
    }

    @Test
    fun `an own model on the server that just failed hands over to the default on another`() {
        val decision = decide(settings(onGroq, app(AppCleanupMode.MODEL, gemma)), fellBackFrom = tinfoil.id)
        assertEquals(onGroq, decision.choice)
        assertNotNull(decision.note)
    }

    @Test
    fun `another server still cleans up after a fallback`() {
        assertEquals(CleanupRouting.Decision(onGroq), decide(settings(onGroq), fellBackFrom = tinfoil.id))
    }
}
