package app.amethyst.core.onboarding

import app.amethyst.core.engine.EngineAdvice
import app.amethyst.core.engine.EngineOption
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.PhoneFacts
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppProfiles
import app.amethyst.core.settings.EngineConfig

/**
 * The speech recognition the quick setup decides on: [now] hears the first dictation, and
 * [later] takes over by itself once its model is on the phone (null: [now] stays).
 */
data class QuickPlan(val now: EngineOption, val later: EngineOption?) {

    /** The one whose model has to be fetched, if any. */
    val download: EngineOption?
        get() = later ?: now.takeIf { it.downloadBytes > 0 }
}

/** What becomes of a recognition that waits for its model ([FirstRun.verdict]). */
enum class PendingVerdict {
    /** Nothing waits. */
    NONE,

    /** The model is still on its way. */
    WAIT,

    /** The model is there and Android's recognition still stands in: time to switch. */
    SWITCH,

    /** The user chose a recognition meanwhile, or the waiting one is unknown: theirs stays. */
    DROP,
}

/**
 * What a new install is given without being asked - by the quick setup, and by skipping the
 * introduction: the speech recognition that fits the phone ([EngineAdvice.recommend]), styles
 * for the known apps that are installed, no cleanup. Nothing here touches what a user chose:
 * a replay of the introduction (the flag set) gets its settings back unchanged.
 */
object FirstRun {

    /** The recognition nobody chose: what a new install starts on and what stands in during a download. */
    private val STAND_IN = EnginePresets.ANDROID_ON_DEVICE.id

    /**
     * The recommended recognition at once if it needs no download ([ready] says whether a
     * model is on the phone already). Otherwise Android's recognition until the model is
     * there - but only where that keeps the voice on the phone, as the introduction promises;
     * elsewhere, and on a phone without Android's recognition, the recommended one is active
     * from the start and a dictation waits for its download.
     */
    fun plan(phone: PhoneFacts, ready: (EngineOption) -> Boolean): QuickPlan {
        val recommended = EngineAdvice.recommend(phone).recommended
        val standIn = phone.androidRecognizer && phone.androidOnDevice
        return when {
            recommended.downloadBytes <= 0 || ready(recommended) -> QuickPlan(recommended, null)
            standIn -> QuickPlan(EngineOption.ANDROID, recommended)
            else -> QuickPlan(recommended, null)
        }
    }

    /** Whether the introduction has anything left to decide for the user: only on a new install. */
    fun applies(settings: AmethystSettings): Boolean = !settings.onboardingDone

    /**
     * The quick setup's choices written into [settings]: the [plan]'s recognition and the app
     * styles. The recognition only while nobody chose one - Android's, as installed, and no
     * other waiting; cleanup is left as it is, which on a new install is off. [finish] also
     * ends the introduction (Skip).
     */
    fun quick(
        settings: AmethystSettings,
        plan: QuickPlan,
        installed: Set<String>,
        phoneTags: List<String>,
        finish: Boolean = false,
    ): AmethystSettings {
        if (!applies(settings)) return settings
        val untouched = settings.activeEngineId == STAND_IN
        val withEngine = if (untouched) {
            use(settings, plan.now, phoneTags).let { chosen ->
                chosen.copy(firstRun = chosen.firstRun.copy(pendingEngine = plan.later?.name))
            }
        } else {
            settings
        }
        return appStyles(withEngine, installed).copy(onboardingDone = finish)
    }

    /**
     * Styles for the known apps on the phone, once per install: asked again (the welcome step
     * chosen twice, a restart halfway) it changes nothing, so a style switched off stays off.
     */
    fun appStyles(settings: AmethystSettings, installed: Set<String>): AmethystSettings {
        if (!applies(settings) || settings.firstRun.appStylesGiven) return settings
        return settings.copy(
            profiles = AppProfiles.withDefaults(settings.profiles, installed),
            firstRun = settings.firstRun.copy(appStylesGiven = true),
        )
    }

    /** [option] added if missing, with its model, and made the active recognition. */
    fun use(settings: AmethystSettings, option: EngineOption, phoneTags: List<String>): AmethystSettings {
        val preset = EnginePresets.byId(option.presetId) ?: return settings
        val base = settings.engines.firstOrNull { it.id == preset.id }
            ?: EngineConfig.fromPreset(preset, languageTag = EngineConfig.defaultLanguageTag(preset.kind, phoneTags))
        val config = option.whisperModelId?.let { base.copy(modelId = it) } ?: base
        return settings.withEngine(config).copy(activeEngineId = config.id)
    }

    /** The recognition waiting for its model, if there is one and this build still knows it. */
    fun pending(settings: AmethystSettings): EngineOption? =
        settings.firstRun.pendingEngine?.let { name -> EngineOption.entries.firstOrNull { it.name == name && it.downloadBytes > 0 } }

    /** What to do about the waiting recognition now, [ready] saying whether its model is on the phone. */
    fun verdict(settings: AmethystSettings, ready: Boolean): PendingVerdict = when {
        settings.firstRun.pendingEngine == null -> PendingVerdict.NONE
        pending(settings) == null -> PendingVerdict.DROP
        // Anything but the stand-in was chosen by the user, in the introduction or in Settings.
        settings.activeEngineId != STAND_IN -> PendingVerdict.DROP
        ready -> PendingVerdict.SWITCH
        else -> PendingVerdict.WAIT
    }

    /**
     * [settings] after the [verdict]: switched to the waiting recognition, or its wait dropped.
     * Decided again on the settings as they are when this is written, so a choice made a
     * moment before is not switched away from.
     */
    fun settle(settings: AmethystSettings, ready: Boolean, phoneTags: List<String>): AmethystSettings {
        val cleared = settings.copy(firstRun = settings.firstRun.copy(pendingEngine = null))
        return when (verdict(settings, ready)) {
            PendingVerdict.NONE, PendingVerdict.WAIT -> settings
            PendingVerdict.DROP -> cleared
            PendingVerdict.SWITCH -> pending(settings)?.let { use(cleared, it, phoneTags) } ?: cleared
        }
    }

    /**
     * Whether a download held for [option] is still wanted once its wait was dropped: only if
     * the user made that very recognition the active one themselves.
     */
    fun stillWanted(settings: AmethystSettings, option: EngineOption): Boolean =
        settings.activeEngine?.let(EngineNames::optionOf) == option
}
