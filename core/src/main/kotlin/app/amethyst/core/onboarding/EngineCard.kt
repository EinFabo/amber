package app.amethyst.core.onboarding

import app.amethyst.core.engine.Advice
import app.amethyst.core.engine.EngineOption
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.whisper.WhisperModels
import app.amethyst.core.settings.EngineConfig

/** What the introduction's engine step shows on its card. */
sealed interface EngineCard {

    /** One of the engines the step offers, to download or to use. */
    data class Offer(val option: EngineOption) : EngineCard

    /**
     * An engine the user already has and the step does not offer - a server set up from it, or
     * on a replay anything else - kept as it is: the step must never force a new engine.
     */
    data class Keep(val engineId: String) : EngineCard

    companion object {

        fun of(picked: EngineOption?, active: EngineConfig?, advice: Advice, replay: Boolean): EngineCard {
            if (picked != null) return Offer(picked)
            if (active == null) return Offer(advice.recommended)
            offered(active)?.let { return Offer(it) }
            // A fresh install still sits on the default engine nobody chose; everything else was chosen.
            return if (replay || active.id != EnginePresets.ANDROID_ON_DEVICE.id) Keep(active.id) else Offer(advice.recommended)
        }

        /** The download option [engine] is, if the step offers it. */
        private fun offered(engine: EngineConfig): EngineOption? = EngineOption.entries.firstOrNull { option ->
            option.downloadBytes > 0 && option.presetId == engine.id &&
                (option.whisperModelId == null || option.whisperModelId == (engine.modelId ?: WhisperModels.default.id))
        }
    }
}
