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
 * [later] takes over by itself once its model is on the phone (null: [now] stays). With
 * [nowMissing], [now] is active before its own model is there - nothing could stand in.
 */
data class QuickPlan(val now: EngineOption, val later: EngineOption?, val nowMissing: Boolean = false) {

    /** The one whose model has to be fetched, if any. */
    val download: EngineOption?
        get() = later ?: now.takeIf { nowMissing }
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

    /** The model is there and its recognition was the active one all along: nothing is left to fetch. */
    DONE,
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
            else -> QuickPlan(recommended, null, nowMissing = true)
        }
    }

    /** Whether the introduction has anything left to decide for the user: only on a new install. */
    fun applies(settings: AmethystSettings): Boolean = !settings.onboardingDone

    /**
     * The quick setup's choices written into [settings]: the [plan]'s recognition and the app
     * styles. The recognition only while nobody chose one - Android's, as installed, and no
     * other waiting; cleanup is left as it is, which on a new install is off. A model still to
     * be fetched is written down either way - for the recognition that waits, and for the one
     * made active at once - so its download outlives the process. [finish] also ends the
     * introduction (Skip).
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
            activate(settings, plan.now, phoneTags).let { chosen ->
                chosen.copy(
                    firstRun = chosen.firstRun.copy(
                        pendingEngine = plan.later?.name,
                        wantedModel = plan.now.name.takeIf { plan.later == null && plan.nowMissing },
                    ),
                )
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

    /**
     * The user's own choice in the introduction: [option] added if missing, with its model, and
     * made the active recognition. Whatever waited to take over waits no longer - also when the
     * choice is Android's recognition, the stand-in itself. A model the choice still has to
     * fetch is written down, so its download outlives the process like quick setup's.
     */
    fun use(settings: AmethystSettings, option: EngineOption, phoneTags: List<String>): AmethystSettings =
        activate(settings, option, phoneTags).let { chosen ->
            chosen.copy(firstRun = chosen.firstRun.copy(pendingEngine = null, wantedModel = option.name.takeIf { option.downloadBytes > 0 }))
        }

    /**
     * The user's own choice in Settings: the recognition [engineId] becomes the active one, and
     * nothing takes over from it later - also when it is Android's recognition, which stood in
     * until now. The model on its way stays wanted only if it is the chosen recognition's own.
     */
    fun chosen(settings: AmethystSettings, engineId: String): AmethystSettings {
        val active = settings.copy(activeEngineId = engineId)
        val own = wanted(settings)?.takeIf { stillWanted(active, it) }
        return active.copy(firstRun = active.firstRun.copy(pendingEngine = null, wantedModel = own?.name))
    }

    /** [option] added if missing, with its model, and made the active recognition. */
    private fun activate(settings: AmethystSettings, option: EngineOption, phoneTags: List<String>): AmethystSettings {
        val preset = EnginePresets.byId(option.presetId) ?: return settings
        val base = settings.engines.firstOrNull { it.id == preset.id }
            ?: EngineConfig.fromPreset(preset, languageTag = EngineConfig.defaultLanguageTag(preset.kind, phoneTags))
        val config = option.whisperModelId?.let { base.copy(modelId = it) } ?: base
        return settings.withEngine(config).copy(activeEngineId = config.id)
    }

    /** The recognition waiting for its model, if there is one and this build still knows it. */
    fun pending(settings: AmethystSettings): EngineOption? = downloadable(settings.firstRun.pendingEngine)

    /**
     * The recognition whose model this install still has to fetch: the one that waits
     * ([pending]), or the one made active before its model was there.
     */
    fun wanted(settings: AmethystSettings): EngineOption? = pending(settings) ?: downloadable(settings.firstRun.wantedModel)

    private fun downloadable(name: String?): EngineOption? =
        name?.let { EngineOption.entries.firstOrNull { option -> option.name == name && option.downloadBytes > 0 } }

    /** What to do about the model still to be fetched now, [ready] saying whether it is on the phone. */
    fun verdict(settings: AmethystSettings, ready: Boolean): PendingVerdict = when {
        settings.firstRun.pendingEngine != null -> when {
            pending(settings) == null -> PendingVerdict.DROP
            // Anything but the stand-in was chosen by the user; their choices end the wait
            // themselves ([use], [chosen]), this catches a change made any other way.
            settings.activeEngineId != STAND_IN -> PendingVerdict.DROP
            ready -> PendingVerdict.SWITCH
            else -> PendingVerdict.WAIT
        }
        settings.firstRun.wantedModel != null -> when {
            // Its recognition is no longer the active one: another was chosen meanwhile.
            wanted(settings)?.let { stillWanted(settings, it) } != true -> PendingVerdict.DROP
            ready -> PendingVerdict.DONE
            else -> PendingVerdict.WAIT
        }
        else -> PendingVerdict.NONE
    }

    /**
     * [settings] after the [verdict]: switched to the waiting recognition, or nothing wanted
     * any more. Decided again on the settings as they are when this is written, so a choice
     * made a moment before is not switched away from. Someone who made the waiting recognition
     * active themselves still gets its model: it stays written down as wanted.
     */
    fun settle(settings: AmethystSettings, ready: Boolean, phoneTags: List<String>): AmethystSettings {
        val cleared = settings.copy(firstRun = settings.firstRun.copy(pendingEngine = null, wantedModel = null))
        return when (verdict(settings, ready)) {
            PendingVerdict.NONE, PendingVerdict.WAIT -> settings
            PendingVerdict.DONE -> cleared
            PendingVerdict.DROP -> {
                val own = pending(settings)?.takeIf { !ready && stillWanted(settings, it) }
                cleared.copy(firstRun = cleared.firstRun.copy(wantedModel = own?.name))
            }
            PendingVerdict.SWITCH -> pending(settings)?.let { activate(cleared, it, phoneTags) } ?: cleared
        }
    }

    /**
     * Whether [option]'s download is still wanted once nothing waits for it: only if that very
     * recognition is the active one.
     */
    fun stillWanted(settings: AmethystSettings, option: EngineOption): Boolean =
        settings.activeEngine?.let(EngineNames::optionOf) == option
}
