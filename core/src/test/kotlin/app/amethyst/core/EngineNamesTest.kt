package app.amethyst.core

import app.amethyst.core.engine.EngineOption
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.onboarding.EngineName
import app.amethyst.core.onboarding.EngineNames
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.SettingsSummaries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EngineNamesTest {

    private val whisper = EngineConfig.fromPreset(EnginePresets.LOCAL_WHISPER)

    @Test
    fun `Parakeet goes by the name Settings gives it`() {
        assertEquals(EngineName.Plain("Parakeet TDT"), EngineNames.of(EngineOption.PARAKEET))
        assertEquals(EngineName.Plain(SettingsSummaries.shortName(EnginePresets.LOCAL_PARAKEET.displayName)), EngineNames.of(EngineOption.PARAKEET))
    }

    @Test
    fun `the summary names every offered engine as the engine step did`() {
        val picked = mapOf(
            EngineOption.PARAKEET to EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET),
            EngineOption.ACFT_LARGE to whisper.copy(modelId = EngineOption.ACFT_LARGE.whisperModelId),
            EngineOption.FUTO_244 to whisper.copy(modelId = EngineOption.FUTO_244.whisperModelId),
            EngineOption.FUTO_74 to whisper.copy(modelId = EngineOption.FUTO_74.whisperModelId),
            EngineOption.ANDROID to EngineConfig.fromPreset(EnginePresets.ANDROID_ON_DEVICE),
        )
        picked.forEach { (option, engine) ->
            assertEquals(option, EngineNames.optionOf(engine))
            assertEquals(EngineNames.of(option), EngineNames.of(engine))
        }
    }

    @Test
    fun `Whisper without a model is the default model`() {
        assertEquals(EngineName.Plain("Whisper base"), EngineNames.of(whisper.copy(modelId = null)))
    }

    @Test
    fun `an engine the step does not offer keeps its own name`() {
        val futo39 = whisper.copy(modelId = "futo-multilingual-39")
        assertNull(EngineNames.optionOf(futo39))
        assertEquals(EngineName.Own(futo39), EngineNames.of(futo39))
        val groq = EngineConfig.fromPreset(EnginePresets.GROQ)
        assertEquals(EngineName.Own(groq), EngineNames.of(groq))
    }
}
