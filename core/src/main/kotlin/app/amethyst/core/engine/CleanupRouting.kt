package app.amethyst.core.engine

import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppCleanupMode
import app.amethyst.core.settings.AppProfiles
import app.amethyst.core.settings.CleanupChoice

/**
 * Which cleanup rewrites one dictation: the app's own choice (off, or a model of its own),
 * else the one for every app. An app's model that is not usable any more - its engine removed,
 * its key gone - falls back to the default, and the diagnostics say so instead of a silent
 * change of model.
 */
object CleanupRouting {

    /** The cleanup to run, null for none; [note] is the diagnostics line when there is a story. */
    data class Decision(val choice: CleanupChoice?, val note: String? = null)

    /** Whether [choice] can run: a chat model on a configured server engine that is set up. */
    fun ready(settings: AmethystSettings, choice: CleanupChoice, hasKey: (String?) -> Boolean): Boolean {
        val engine = settings.engines.firstOrNull { it.id == choice.engineId } ?: return false
        return engine.kind == EngineKind.OPENAI_COMPATIBLE && choice.model.isNotBlank() &&
            EngineConfigValidator.isReady(engine, hasKey(engine.apiKeyAlias))
    }

    /**
     * The cleanup for a dictation into [packageName]. [fellBackFrom] is the server whose
     * recording a fallback engine took: it would only add a timeout, so it is not asked.
     */
    fun decide(
        settings: AmethystSettings,
        packageName: String?,
        fellBackFrom: String?,
        hasKey: (String?) -> Boolean,
    ): Decision {
        val profile = AppProfiles.forPackage(settings.profiles, packageName)
        val own = profile?.appCleanup
        val decision = when (own?.mode) {
            AppCleanupMode.OFF -> return Decision(null, "cleanup off for ${profile.label}")
            AppCleanupMode.MODEL -> own.choice?.takeIf { ready(settings, it, hasKey) }?.let { Decision(it) }
                ?: default(settings, hasKey).let { fallback ->
                    val what = own.choice?.let { " (${describe(settings, it)})" }.orEmpty()
                    val instead = if (fallback.choice != null) "the default is used" else "no cleanup"
                    Decision(fallback.choice, "${profile.label}'s own cleanup$what is not ready - $instead")
                }
            AppCleanupMode.DEFAULT, null -> default(settings, hasKey)
        }
        val choice = decision.choice ?: return decision
        if (choice.engineId == fellBackFrom) {
            val engine = settings.engines.firstOrNull { it.id == fellBackFrom }?.displayName ?: fellBackFrom
            // An app's own model on that server: the default on another one may well answer.
            val instead = default(settings, hasKey).choice?.takeIf { own?.mode == AppCleanupMode.MODEL && it.engineId != fellBackFrom }
            return Decision(instead, "cleanup skipped: $engine did not answer" + if (instead != null) " - the default is used" else "")
        }
        return decision
    }

    private fun default(settings: AmethystSettings, hasKey: (String?) -> Boolean): Decision {
        val choice = settings.cleanup ?: return Decision(null)
        return if (ready(settings, choice, hasKey)) {
            Decision(choice)
        } else {
            Decision(null, "cleanup ${describe(settings, choice)} is not ready")
        }
    }

    private fun describe(settings: AmethystSettings, choice: CleanupChoice): String {
        val engine = settings.engines.firstOrNull { it.id == choice.engineId }?.displayName ?: "a removed engine"
        return "${choice.model} on $engine"
    }
}
