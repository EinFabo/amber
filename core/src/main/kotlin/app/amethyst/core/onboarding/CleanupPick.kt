package app.amethyst.core.onboarding

import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.EngineConfig

/**
 * The introduction's cleanup choice: off, Tinfoil - the one server that fits a first run, one
 * key away - or, on a replay, a cleanup set up elsewhere kept as it is. Continue on an unchanged
 * choice must never rewrite the setting.
 */
enum class CleanupPick {
    OFF, TINFOIL, KEEP;

    /** The settings once the step's Continue is pressed; [keyReady] says a Tinfoil key exists. */
    fun applyTo(settings: AmethystSettings, keyReady: Boolean): AmethystSettings = when {
        this == OFF -> settings.copy(cleanup = null)
        this == TINFOIL && keyReady -> {
            val tinfoil = EnginePresets.TINFOIL
            val engine = settings.engines.firstOrNull { it.id == tinfoil.id }
                ?: EngineConfig.fromPreset(tinfoil, languageTag = EngineConfig.defaultLanguageTag(tinfoil.kind))
            // A Tinfoil model chosen earlier stays; only a new Tinfoil cleanup gets the recommended one.
            val model = settings.cleanup?.takeIf { it.engineId == tinfoil.id }?.model
                ?: ServerProviders.TINFOIL.cleanupModels.first { it.recommended }.id
            settings.withEngine(engine).copy(cleanup = CleanupChoice(tinfoil.id, model))
        }
        // Tinfoil without a key, or a cleanup kept as it is: nothing changes.
        else -> settings
    }

    companion object {
        fun initial(settings: AmethystSettings): CleanupPick {
            val current = settings.cleanup ?: return OFF
            return if (current.engineId == EnginePresets.TINFOIL.id) TINFOIL else KEEP
        }
    }
}
