package app.amethyst.core

import app.amethyst.core.engine.Advice
import app.amethyst.core.engine.EngineOption
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.onboarding.EngineCard
import app.amethyst.core.settings.EngineConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineCardTest {

    private val advice = Advice(EngineOption.PARAKEET, listOf(EngineOption.FUTO_244, EngineOption.ANDROID, EngineOption.SERVER))
    private val android = EngineConfig.fromPreset(EnginePresets.ANDROID_ON_DEVICE)
    private val parakeet = EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET)
    private val groq = EngineConfig.fromPreset(EnginePresets.GROQ)
    private val futo39 = EngineConfig.fromPreset(EnginePresets.LOCAL_WHISPER).copy(modelId = "futo-multilingual-39")

    @Test
    fun `a fresh install is offered the recommendation`() {
        assertEquals(EngineCard.Offer(EngineOption.PARAKEET), EngineCard.of(picked = null, active = android, advice = advice, replay = false))
    }

    @Test
    fun `a server set up from the step is kept`() {
        assertEquals(EngineCard.Keep(groq.id), EngineCard.of(picked = null, active = groq, advice = advice, replay = false))
    }

    @Test
    fun `a replay keeps an engine the step does not offer`() {
        assertEquals(EngineCard.Keep(futo39.id), EngineCard.of(picked = null, active = futo39, advice = advice, replay = true))
        assertEquals(EngineCard.Keep(android.id), EngineCard.of(picked = null, active = android, advice = advice, replay = true))
    }

    @Test
    fun `an engine the step offers is shown as that offer`() {
        assertEquals(EngineCard.Offer(EngineOption.PARAKEET), EngineCard.of(picked = null, active = parakeet, advice = advice, replay = true))
        val futo244 = futo39.copy(modelId = "futo-multilingual-244")
        assertEquals(EngineCard.Offer(EngineOption.FUTO_244), EngineCard.of(picked = null, active = futo244, advice = advice, replay = false))
    }

    @Test
    fun `a pick from the other options wins`() {
        assertEquals(EngineCard.Offer(EngineOption.FUTO_244), EngineCard.of(picked = EngineOption.FUTO_244, active = groq, advice = advice, replay = true))
    }
}
