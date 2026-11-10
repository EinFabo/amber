package app.amethyst.core.engine

import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppEngineMode
import app.amethyst.core.settings.AppProfiles
import app.amethyst.core.settings.EngineConfig

/**
 * Which speech recognition hears one dictation: the app's own, while it is there and ready,
 * else the one for every app ([AmethystSettings.activeEngine]). An app's own that is not usable
 * any more - removed, switched off, its key gone, its model not on the phone - falls back to the
 * default, and the diagnostics say so instead of a silent change of speech recognition. The
 * default itself is taken as it is: what is wrong with it is what the dictation reports.
 */
object EngineRouting {

    /** Why an app's own speech recognition was passed over. */
    enum class NotUsable(val words: String) {
        REMOVED("was removed"),
        SWITCHED_OFF("is switched off"),
        NOT_SET_UP("is not set up"),
        NO_MODEL("has no model on the phone yet"),
    }

    /**
     * The speech recognition to use - null when none is chosen at all. [app] is the label of the
     * app whose own setting was asked: set when its own is used ([fromApp]) or passed over
     * ([passedOver], [ownName]).
     */
    data class Decision(
        val engine: EngineConfig?,
        val fromApp: Boolean = false,
        val app: String? = null,
        val passedOver: NotUsable? = null,
        val ownName: String? = null,
    ) {
        /**
         * The diagnostics line: which speech recognition, and whether the app's own setting
         * chose it or why it did not. [namesApps] false says "the app" for its label, for a log
         * a release build keeps. Null when there is none. Names and reasons only, no words.
         */
        fun line(namesApps: Boolean = true): String? {
            val name = engine?.displayName ?: return null
            val whose = if (namesApps && app != null) "$app's" else "the app's"
            return when {
                fromApp -> "speech recognition $name, $whose own"
                passedOver != null -> {
                    val own = ownName?.let { " ($it)" }.orEmpty()
                    "speech recognition $name: $whose own$own ${passedOver.words}"
                }
                else -> "speech recognition $name"
            }
        }
    }

    /**
     * Why [engine] cannot hear a dictation now, or null when it can. [hasKey] says whether a key
     * is stored under an alias; [modelReady] whether a model on the phone is downloaded (true for
     * an engine elsewhere).
     */
    fun notUsable(
        engine: EngineConfig,
        hasKey: (String?) -> Boolean,
        modelReady: (EngineConfig) -> Boolean,
    ): NotUsable? = when {
        !engine.enabled -> NotUsable.SWITCHED_OFF
        !EngineConfigValidator.isReady(engine, hasKey(engine.apiKeyAlias)) -> NotUsable.NOT_SET_UP
        !modelReady(engine) -> NotUsable.NO_MODEL
        else -> null
    }

    /** The speech recognition for a dictation into [packageName] (null: no app known). */
    fun decide(
        settings: AmethystSettings,
        packageName: String?,
        hasKey: (String?) -> Boolean,
        modelReady: (EngineConfig) -> Boolean,
    ): Decision {
        val default = settings.activeEngine
        val profile = AppProfiles.forPackage(settings.profiles, packageName) ?: return Decision(default)
        val own = profile.appEngine
        if (own.mode != AppEngineMode.ENGINE || own.engineId == null) return Decision(default)
        val engine = settings.engines.firstOrNull { it.id == own.engineId }
            ?: return Decision(default, app = profile.label, passedOver = NotUsable.REMOVED)
        val problem = notUsable(engine, hasKey, modelReady)
            ?: return Decision(engine, fromApp = true, app = profile.label)
        return Decision(default, app = profile.label, passedOver = problem, ownName = engine.displayName)
    }
}
