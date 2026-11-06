package app.amethyst.core.onboarding

import app.amethyst.core.engine.EngineOption
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.whisper.WhisperModels
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.SettingsSummaries

/**
 * One name per engine in the introduction: the engine step's card, its "Other options" list and
 * the summary at the end all say the same. Parakeet goes by the name Settings gives it
 * ("Parakeet TDT"; its "(local)" is what the trust badge beside it says). The Whisper sizes keep
 * their own names - Settings has one name for all of them - and Android's recognizer stays that,
 * since on a phone without on-device recognition it is not "on device".
 */
object EngineNames {

    fun of(option: EngineOption): String = when (option) {
        EngineOption.PARAKEET -> SettingsSummaries.shortName(EnginePresets.LOCAL_PARAKEET.displayName)
        EngineOption.ACFT_LARGE -> "Whisper large"
        EngineOption.FUTO_244 -> "Whisper small"
        EngineOption.FUTO_74 -> "Whisper base"
        EngineOption.ANDROID -> "Android's recognizer"
        EngineOption.SERVER -> "A server with your own key"
    }

    /** [engine] by the name the engine step gave it, or its own name if the step does not offer it. */
    fun of(engine: EngineConfig): String = optionOf(engine)?.let(::of) ?: engine.displayName

    /** The engine step's option that [engine] is - the engine and, for Whisper, its model - if any. */
    fun optionOf(engine: EngineConfig): EngineOption? = EngineOption.entries.firstOrNull { option ->
        option != EngineOption.SERVER && option.presetId == engine.id &&
            (option.whisperModelId == null || option.whisperModelId == (engine.modelId ?: WhisperModels.default.id))
    }
}
