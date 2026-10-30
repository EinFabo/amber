package app.amethyst.core

import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.onboarding.CleanupPick
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.EngineConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CleanupPickTest {

    private val tinfoil = EnginePresets.TINFOIL.id
    private val parakeet = EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET)
    private val local = AmethystSettings.defaults().withEngine(parakeet).copy(activeEngineId = parakeet.id)
    private val onGemma = local
        .withEngine(EngineConfig.fromPreset(EnginePresets.TINFOIL))
        .copy(cleanup = CleanupChoice(tinfoil, "gemma-4-31b"))
    private val onGroq = local
        .withEngine(EngineConfig.fromPreset(EnginePresets.GROQ))
        .copy(cleanup = CleanupChoice(EnginePresets.GROQ.id, "openai/gpt-oss-20b"))

    @Test
    fun `the first pick follows the current cleanup`() {
        assertEquals(CleanupPick.OFF, CleanupPick.initial(local))
        assertEquals(CleanupPick.TINFOIL, CleanupPick.initial(onGemma))
        assertEquals(CleanupPick.KEEP, CleanupPick.initial(onGroq))
    }

    @Test
    fun `off switches the cleanup off`() {
        assertNull(CleanupPick.OFF.applyTo(onGroq, keyReady = false).cleanup)
    }

    @Test
    fun `tinfoil with a key adds the engine and the recommended model`() {
        val after = CleanupPick.TINFOIL.applyTo(local, keyReady = true)
        assertEquals(CleanupChoice(tinfoil, "deepseek-v4-1-flash"), after.cleanup)
        assertTrue(after.engines.any { it.id == tinfoil })
        assertEquals(parakeet.id, after.activeEngineId)
    }

    @Test
    fun `a tinfoil cleanup on another model keeps its model`() {
        assertEquals(CleanupChoice(tinfoil, "gemma-4-31b"), CleanupPick.TINFOIL.applyTo(onGemma, keyReady = true).cleanup)
    }

    @Test
    fun `tinfoil without a key and keep change nothing`() {
        assertEquals(local, CleanupPick.TINFOIL.applyTo(local, keyReady = false))
        assertEquals(onGroq, CleanupPick.KEEP.applyTo(onGroq, keyReady = false))
    }
}
