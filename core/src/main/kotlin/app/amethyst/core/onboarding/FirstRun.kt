package app.amethyst.core.onboarding

import app.amethyst.core.engine.EngineAdvice
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.EngineOption
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.LanguagePack
import app.amethyst.core.engine.ModelDownload
import app.amethyst.core.engine.PhoneFacts
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppProfiles
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.LanguageList

/**
 * The speech recognition the quick setup decides on: [now] hears the first dictation, and
 * [later] takes over by itself once its model is on the phone (null: [now] stays). With
 * [nowMissing], [now]'s own model is not on the phone yet: a dictation waits for it.
 */
data class QuickPlan(val now: EngineOption, val later: EngineOption?, val nowMissing: Boolean = false) {

    /** The models to fetch, in order: [now]'s first, if it is missing, then [later]'s. */
    val downloads: List<EngineOption>
        get() = listOfNotNull(now.takeIf { nowMissing }, later)
}

/** What becomes of a recognition that waits for its model ([FirstRun.verdict]). */
enum class PendingVerdict {
    /** Nothing waits. */
    NONE,

    /** The model is still on its way. */
    WAIT,

    /**
     * The small model that stands in is on the phone: it hears the dictations now, and the
     * waiting recognition's download comes next.
     */
    STANDS_IN,

    /** The model is there and the stand-in is still the active recognition: time to switch. */
    SWITCH,

    /** The user chose a recognition meanwhile, or the waiting one is unknown: theirs stays. */
    DROP,

    /** The model is there and its recognition was the active one all along: nothing is left to fetch. */
    DONE,
}

/** Where a first run's download stands, for the line that says what is happening ([FirstRun.stage]). */
enum class PendingStage {
    /** The small model is on its way, the better one follows; a dictation waits for the small one. */
    SMALL_FIRST,

    /** The small model hears the dictations while the better one's model downloads. */
    SMALL_STANDS_IN,

    /** Android's recognition stands in while the model downloads - settings from 0.33. */
    ANDROID_STANDS_IN,

    /** The active recognition's own model is on its way; a dictation waits for it. */
    OWN,
}

/** What the introduction's last step says: whether a dictation works now, and if not, what it waits for ([FirstRun.atDone]). */
enum class DoneState {
    /** "All set". */
    READY,

    /**
     * The bubble cannot work yet: the service is off or not running, or the microphone is not
     * allowed ([FirstRun.setupMissing]). Ranked before everything else: no model makes up for it.
     */
    SETUP_MISSING,

    /**
     * Nothing on this phone can recognize speech yet: Android's recognition is the active one
     * and the phone has none (de-Googled). A model or a server is the way. Ranked before the
     * model and language-pack states: those wait for something that would not help.
     */
    NO_RECOGNITION,

    /** The model a dictation needs is still on its way. */
    MODEL_MISSING,

    /** Android's recognition has no offline pack for its language yet; Android can fetch it. */
    LANGUAGE_PACK_MISSING,

    /** Android is fetching that pack. */
    LANGUAGE_PACK_DOWNLOADING,

    /** Android's recognition has no pack for this language at all: another recognition is the way. */
    LANGUAGE_UNSUPPORTED,
}

/** What Android said about the offline pack for [tag] - asked for the language the active recognition hears ([FirstRun.languagePackTag]). */
data class LanguagePackAnswer(val tag: String, val pack: LanguagePack)

/**
 * What a new install is given without being asked - by the quick setup, and by skipping the
 * introduction: the speech recognition that fits the phone ([EngineAdvice.recommend]), styles
 * for the known apps that are installed, no cleanup. Nothing here touches what a user chose:
 * a replay of the introduction (the flag set) gets its settings back unchanged.
 *
 * Where the recommended recognition needs a big download, a small Whisper model ([SMALL],
 * about 80 MB, seconds on Wi-Fi) is fetched first - over mobile data too - and hears the
 * dictations until the big one is there; then Amber switches by itself and deletes the small
 * model, unless the user chose it. Android's recognition no longer stands in: it may not keep
 * the voice on the phone, and it fails on a phone without the offline pack for its language.
 */
object FirstRun {

    /** The recognition nobody chose: what a new install starts on, and the stand-in of settings from 0.33. */
    private val UNTOUCHED = EnginePresets.ANDROID_ON_DEVICE.id

    /** The small model that stands in while a big one downloads. */
    val SMALL = EngineOption.FUTO_74

    /**
     * The recommended recognition at once if it needs no download ([ready] says whether a
     * model is on the phone already). One with a big download gets the [SMALL] model in front
     * of it, where that fits beside it; without room or a CPU for the small one - and for the
     * small one itself - the recommended one is active from the start and a dictation waits
     * for its download.
     */
    fun plan(phone: PhoneFacts, ready: (EngineOption) -> Boolean): QuickPlan {
        val recommended = EngineAdvice.recommend(phone).recommended
        return when {
            recommended.downloadBytes <= 0 || ready(recommended) -> QuickPlan(recommended, null)
            recommended != SMALL && smallFits(phone, recommended) -> QuickPlan(SMALL, recommended, nowMissing = !ready(SMALL))
            else -> QuickPlan(recommended, null, nowMissing = true)
        }
    }

    /** Room and a CPU for the small model beside [recommended]: both are on the phone until the switch. */
    private fun smallFits(phone: PhoneFacts, recommended: EngineOption): Boolean =
        EngineAdvice.fits(SMALL, phone) &&
            (SMALL.downloadBytes + recommended.downloadBytes) / 1_000_000 + EngineAdvice.STORAGE_MARGIN_MB <= phone.freeStorageMb

    /** Whether the introduction has anything left to decide for the user: only on a new install. */
    fun applies(settings: AmethystSettings): Boolean = !settings.onboardingDone

    /**
     * Whether the introduction counts as done at [step]: from the practice step on, once the
     * bubble can work ([setupReady]: the microphone allowed and the service really running).
     * Leaving there brings Home, not the welcome again. Not before - and not while setup is
     * missing, so a replay or a lost permission comes back to the introduction.
     */
    fun finished(step: OnboardingStep, setupReady: Boolean): Boolean =
        setupReady && step.ordinal >= OnboardingStep.PRACTICE.ordinal

    /**
     * The quick setup's choices written into [settings]: the [plan]'s recognition and the app
     * styles. The recognition only while nobody chose one - Android's, as installed, and no
     * other waiting; cleanup is left as it is, which on a new install is off. The models still
     * to be fetched are written down - the one that waits, and the active one's own if it is
     * missing - so their downloads outlive the process. [finish] also ends the introduction
     * (Skip).
     */
    fun quick(
        settings: AmethystSettings,
        plan: QuickPlan,
        installed: Set<String>,
        phoneTags: List<String>,
        finish: Boolean = false,
    ): AmethystSettings {
        if (!applies(settings)) return settings
        val untouched = settings.activeEngineId == UNTOUCHED
        val withEngine = if (untouched) {
            activate(settings, plan.now, phoneTags).let { chosen ->
                chosen.copy(
                    firstRun = chosen.firstRun.copy(
                        pendingEngine = plan.later?.name,
                        wantedModel = plan.now.name.takeIf { plan.nowMissing },
                        standIn = plan.now.name.takeIf { plan.later != null && plan.now.downloadBytes > 0 },
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
     * choice is the stand-in itself, which is then theirs and stays. A model the choice still
     * has to fetch is written down, so its download outlives the process like quick setup's.
     */
    fun use(settings: AmethystSettings, option: EngineOption, phoneTags: List<String>): AmethystSettings =
        activate(settings, option, phoneTags).let { chosen ->
            chosen.copy(
                firstRun = chosen.firstRun.copy(
                    pendingEngine = null,
                    wantedModel = option.name.takeIf { option.downloadBytes > 0 },
                    standIn = settings.firstRun.standIn.takeIf { it != option.name },
                ),
            )
        }

    /**
     * The user's own choice in Settings: the recognition [engineId] becomes the active one, and
     * nothing takes over from it later - also when it is the one that stood in until now, which
     * is then theirs and stays. A model on its way stays wanted only if it is the chosen
     * recognition's own.
     */
    fun chosen(settings: AmethystSettings, engineId: String): AmethystSettings {
        val active = settings.copy(activeEngineId = engineId)
        val own = onTheirWay(settings).firstOrNull { stillWanted(active, it) }
        val theirs = active.activeEngine?.let(EngineNames::optionOf)
        return active.copy(
            firstRun = active.firstRun.copy(
                pendingEngine = null,
                wantedModel = own?.name,
                standIn = settings.firstRun.standIn.takeIf { it != theirs?.name },
            ),
        )
    }

    /**
     * The Speech recognition page's Undo after [chosen]: the recognition active [before] the tap
     * again, with the first run's plan as it was then - a model still on its way wanted again,
     * the stand-in still standing in. One removed meanwhile is not brought back: the chosen one stays.
     */
    fun unchosen(settings: AmethystSettings, before: AmethystSettings): AmethystSettings {
        if (settings.engines.none { it.id == before.activeEngineId }) return settings
        return settings.copy(activeEngineId = before.activeEngineId, firstRun = before.firstRun)
    }

    /**
     * Whether a tap on [engineId] in Settings can be taken back by [unchosen]: not when it leaves
     * the small stand-in with nothing to do - its recognition and its file go at once
     * ([leftover], [FirstRunWatch]), and there would be nothing to go back to.
     */
    fun canUnchoose(settings: AmethystSettings, engineId: String): Boolean = leftover(chosen(settings, engineId)) == null

    /**
     * Whether a tap on [engineId] in Settings changes anything ([chosen]). A tap on the row
     * already in use usually does not - but on the small model while it stands in it makes the
     * model theirs and lets the better one's download go, which is worth an Undo too.
     */
    fun changes(settings: AmethystSettings, engineId: String): Boolean = chosen(settings, engineId) != settings

    /**
     * What the introduction's last step says. "All set" only once a dictation works: not while
     * it still waits for a model - the one the first run fetches ([stage]) when no other
     * recognition stands in, or the active recognition's own download ([activeDownload]),
     * running or held - nor while Android's recognition lacks the offline pack for its
     * language ([pack], as [languagePack] reads it; null where nobody could ask). Before all
     * of those: [nothingRecognizes] ([nothingRecognizes]) - then no model or pack on its way
     * makes this recognition work. And before that, [setupMissing]: the bubble itself does not
     * work yet. Add states that outrank it above it.
     */
    fun atDone(
        stage: PendingStage?,
        activeDownload: ModelDownload?,
        pack: LanguagePack? = null,
        nothingRecognizes: Boolean = false,
        setupMissing: Boolean = false,
    ): DoneState {
        val modelThere = when (stage) {
            null -> activeDownload == null
            PendingStage.SMALL_STANDS_IN, PendingStage.ANDROID_STANDS_IN -> true
            PendingStage.SMALL_FIRST, PendingStage.OWN -> false
        }
        return when {
            setupMissing -> DoneState.SETUP_MISSING
            nothingRecognizes -> DoneState.NO_RECOGNITION
            !modelThere -> DoneState.MODEL_MISSING
            pack == LanguagePack.MISSING -> DoneState.LANGUAGE_PACK_MISSING
            pack == LanguagePack.DOWNLOADING -> DoneState.LANGUAGE_PACK_DOWNLOADING
            pack == LanguagePack.UNSUPPORTED -> DoneState.LANGUAGE_UNSUPPORTED
            else -> DoneState.READY
        }
    }

    /**
     * The steps of the introduction whose work is not done, in the order they come: the microphone
     * and the service (off, or on without ever connecting - a service still
     * [ServiceHealth.STARTING] is not counted missing). Empty: the bubble can work.
     */
    fun setupMissing(microphone: Boolean, service: ServiceHealth): List<OnboardingStep> = listOfNotNull(
        OnboardingStep.MICROPHONE.takeIf { !microphone },
        OnboardingStep.ACCESSIBILITY.takeIf { service == ServiceHealth.OFF || service == ServiceHealth.NOT_RUNNING },
    )

    /**
     * Whether the active recognition is Android's and the phone has none of it
     * ([androidRecognizer] false; null where nobody could ask: no claim). Dictating would fail
     * every time, so the screens say so and point at a model or a server.
     */
    fun nothingRecognizes(settings: AmethystSettings, androidRecognizer: Boolean?): Boolean =
        androidRecognizer == false && settings.activeEngine?.kind == EngineKind.ANDROID_ON_DEVICE

    /**
     * The language whose offline pack Android is asked about: the one the active recognition
     * hears, when that is Android's - [defaultTag], the phone's, where none is set for it, as
     * the recognition itself does. Null for any other recognition: it needs no pack of Android's.
     */
    fun languagePackTag(settings: AmethystSettings, defaultTag: String): String? {
        val active = settings.activeEngine?.takeIf { it.kind == EngineKind.ANDROID_ON_DEVICE } ?: return null
        return LanguageList.forDictation(active) ?: defaultTag
    }

    /**
     * The pack the active recognition needs, by [answer] - only if the answer is about what it
     * hears now: one asked before another recognition or language was chosen says nothing.
     */
    fun languagePack(settings: AmethystSettings, answer: LanguagePackAnswer?, defaultTag: String): LanguagePack? =
        answer?.takeIf { it.tag == languagePackTag(settings, defaultTag) }?.pack

    /**
     * Whether the quick setup starts the language pack's download at once: it picked Android's
     * recognition - where no model fits - and Android can fetch the pack it lacks. Without it
     * every dictation would fail ("This language isn't installed"), so it is not left for Home
     * to offer after the first failure. Android asks before it downloads.
     */
    fun fetchesLanguagePack(plan: QuickPlan, pack: LanguagePack?): Boolean =
        plan.now == EngineOption.ANDROID && pack == LanguagePack.MISSING

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
     * The small model that stands in, while its own download comes first: fetched before the
     * waiting recognition's, and over mobile data too - it is small, and nothing can be
     * dictated without it.
     */
    fun smallFirst(settings: AmethystSettings): EngineOption? =
        if (pending(settings) != null) downloadable(settings.firstRun.wantedModel) else null

    /**
     * The model this install has to fetch now: the stand-in's own first ([smallFirst]), then
     * the one that waits ([pending]), or the one made active before its model was there.
     */
    fun wanted(settings: AmethystSettings): EngineOption? =
        smallFirst(settings) ?: pending(settings) ?: downloadable(settings.firstRun.wantedModel)

    /** Whether [option]'s download may go over mobile data without asking: only the small model's, fetched first. */
    fun anyNetwork(settings: AmethystSettings, option: EngineOption): Boolean = smallFirst(settings) == option

    /** Where the first run's download stands; null when nothing is on its way. */
    fun stage(settings: AmethystSettings): PendingStage? = when {
        wanted(settings) == null -> null
        smallFirst(settings) != null -> PendingStage.SMALL_FIRST
        pending(settings) != null -> if (smallStandIn(settings) != null) PendingStage.SMALL_STANDS_IN else PendingStage.ANDROID_STANDS_IN
        else -> PendingStage.OWN
    }

    /** Every model written down as on its way, the waiting recognition's first. */
    private fun onTheirWay(settings: AmethystSettings): List<EngineOption> =
        listOfNotNull(pending(settings), downloadable(settings.firstRun.wantedModel))

    private fun smallStandIn(settings: AmethystSettings): EngineOption? = downloadable(settings.firstRun.standIn)

    /** Whether the recognition that stands in is still the active one - nobody chose another meanwhile. */
    private fun standingIn(settings: AmethystSettings): Boolean = when (val small = smallStandIn(settings)) {
        null -> settings.activeEngineId == UNTOUCHED
        else -> stillWanted(settings, small)
    }

    private fun downloadable(name: String?): EngineOption? =
        name?.let { EngineOption.entries.firstOrNull { option -> option.name == name && option.downloadBytes > 0 } }

    /** What to do about the model still to be fetched now ([wanted]), [ready] saying whether it is on the phone. */
    fun verdict(settings: AmethystSettings, ready: Boolean): PendingVerdict = when {
        settings.firstRun.pendingEngine != null -> when {
            pending(settings) == null -> PendingVerdict.DROP
            // Anything but the stand-in was chosen by the user; their choices end the wait
            // themselves ([use], [chosen]), this catches a change made any other way.
            !standingIn(settings) -> PendingVerdict.DROP
            smallFirst(settings) != null -> if (ready) PendingVerdict.STANDS_IN else PendingVerdict.WAIT
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
     * [settings] after the [verdict]: the small model taken as the stand-in, switched to the
     * waiting recognition, or nothing wanted any more. Decided again on the settings as they
     * are when this is written, so a choice made a moment before is not switched away from.
     * Someone who made a recognition on its way active themselves still gets its model: it
     * stays written down as wanted.
     */
    fun settle(settings: AmethystSettings, ready: Boolean, phoneTags: List<String>): AmethystSettings {
        val cleared = settings.copy(firstRun = settings.firstRun.copy(pendingEngine = null, wantedModel = null))
        return when (verdict(settings, ready)) {
            PendingVerdict.NONE, PendingVerdict.WAIT -> settings
            PendingVerdict.STANDS_IN -> settings.copy(firstRun = settings.firstRun.copy(wantedModel = null))
            PendingVerdict.DONE -> cleared
            PendingVerdict.DROP -> {
                val there = wanted(settings).takeIf { ready }
                val own = onTheirWay(settings).firstOrNull { it != there && stillWanted(settings, it) }
                cleared.copy(firstRun = cleared.firstRun.copy(wantedModel = own?.name))
            }
            PendingVerdict.SWITCH -> pending(settings)?.let { activate(cleared, it, phoneTags) } ?: cleared
        }
    }

    /**
     * The small model Amber fetched to stand in, once nothing needs it: nothing waits or is
     * fetched any more, it is not the active recognition, and no app and not the fallback uses
     * it. Its file may be deleted - after [tidy] has forgotten it, decided on the same settings.
     * Someone who chose it themselves keeps it: their choice cleared the mark.
     */
    fun leftover(settings: AmethystSettings): EngineOption? =
        idleStandIn(settings)?.takeIf { !usedElsewhere(settings, it) }

    /**
     * [settings] once the stand-in's work is done: the [leftover] model forgotten - the mark
     * gone, and its recognition gone from the list too, so Settings does not offer a model that
     * is not there; or, when an app or the fallback uses the small model, the mark alone gone:
     * it is theirs now, and its file stays.
     */
    fun tidy(settings: AmethystSettings): AmethystSettings {
        val small = idleStandIn(settings) ?: return settings
        val unmarked = settings.copy(firstRun = settings.firstRun.copy(standIn = null))
        if (usedElsewhere(settings, small)) return unmarked
        return unmarked.copy(engines = settings.engines.filterNot { it.id != settings.activeEngineId && EngineNames.optionOf(it) == small })
    }

    /** The small model that stood in, once nothing waits or is fetched any more and it is not the active recognition. */
    private fun idleStandIn(settings: AmethystSettings): EngineOption? {
        val small = smallStandIn(settings) ?: return null
        val idle = settings.firstRun.pendingEngine == null && settings.firstRun.wantedModel == null
        return small.takeIf { idle && !stillWanted(settings, it) }
    }

    /**
     * Whether an app's own recognition or the fallback is a recognition with [small]'s model -
     * a Whisper that has another model by now does not count.
     */
    private fun usedElsewhere(settings: AmethystSettings, small: EngineOption): Boolean =
        settings.engines.any { config ->
            EngineNames.optionOf(config) == small &&
                (config.id == settings.fallback.engineId || settings.profiles.any { it.appEngine.engineId == config.id })
        }

    /**
     * Whether [option]'s download is still wanted once nothing waits for it: only if that very
     * recognition is the active one.
     */
    fun stillWanted(settings: AmethystSettings, option: EngineOption): Boolean =
        settings.activeEngine?.let(EngineNames::optionOf) == option
}
