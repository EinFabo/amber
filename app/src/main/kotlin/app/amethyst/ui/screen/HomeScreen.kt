package app.amethyst.ui.screen

import android.os.Build
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amethyst.R
import app.amethyst.ui.ShowsDictatedText
import app.amethyst.Texts
import app.amethyst.core.engine.DownloadNotice
import app.amethyst.core.engine.LanguagePack
import app.amethyst.core.onboarding.FirstRun
import app.amethyst.core.engine.ModelDownload
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.insert.InsertionStrategy
import app.amethyst.core.onboarding.AmberVariant
import app.amethyst.core.onboarding.OtherAmbers
import app.amethyst.core.onboarding.SettingsMaker
import app.amethyst.core.onboarding.SwitchOnPlan
import app.amethyst.core.onboarding.SwitchOnStep
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppCleanupMode
import app.amethyst.core.settings.AppEngineMode
import app.amethyst.core.settings.AppProfile
import app.amethyst.core.stats.StatsSummary
import app.amethyst.core.update.UpdateNotice
import app.amethyst.dictation.DictationState
import app.amethyst.ui.AccessibilityConsentDialog
import app.amethyst.ui.AmberIcons
import app.amethyst.ui.BatterySteps
import app.amethyst.ui.DictateOrb
import app.amethyst.ui.GhostButton
import app.amethyst.ui.recognitionRowName
import app.amethyst.ui.Hint
import app.amethyst.ui.NavRow
import app.amethyst.ui.PageGutter
import app.amethyst.ui.PrimaryButton
import app.amethyst.ui.ProgressLine
import app.amethyst.ui.RestrictedSettingsSteps
import app.amethyst.ui.RowDivider
import app.amethyst.ui.SectionCard
import app.amethyst.ui.SettingsGroup
import app.amethyst.ui.SetupActions
import app.amethyst.ui.Setup
import app.amethyst.ui.SetupState
import app.amethyst.ui.PendingModel
import app.amethyst.ui.PendingModelNote
import app.amethyst.ui.StatusRow
import app.amethyst.ui.TrustBadge
import app.amethyst.ui.onboarding.LanguagePackNote
import app.amethyst.ui.onboarding.LanguagePackWatch
import app.amethyst.ui.engineName
import app.amethyst.ui.theme.AmberPalette
import kotlinx.coroutines.flow.StateFlow

/**
 * Hearth: one quiet home. The engine on top, the flame alone in the middle, a row with today's
 * numbers that opens the statistics, and the test field. Setup shows above the flame until
 * it is done, reliability only while something is open; diagnostics live in the settings.
 */
@Composable
fun HomeScreen(
    setup: SetupState,
    actions: SetupActions,
    settings: AmethystSettings,
    dictationState: StateFlow<DictationState>,
    stats: StatsSummary?,
    /** The active engine's download, while it runs: the flame cannot dictate yet. */
    download: ModelDownload?,
    onStartTest: () -> Unit,
    onStopTest: () -> Unit,
    onOpenRecognition: () -> Unit,
    onOpenCleanup: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenActiveEngine: () -> Unit,
    onInstallLanguage: () -> Unit,
    onUseLocalWhisper: () -> Unit,
    modifier: Modifier = Modifier,
    /** A newer Amber on GitHub, until dismissed; only ever on the GitHub build. */
    update: UpdateNotice? = null,
    onDownloadUpdate: (UpdateNotice) -> Unit = {},
    onDismissUpdate: (UpdateNotice) -> Unit = {},
    /** A model the first run still fetches for this phone: the small one that stands in, or the better one after it. */
    pending: PendingModel? = null,
    /** Until the first dictation into another app: where to try the bubble for real. */
    nudge: app.amethyst.core.onboarding.RealAppNudge? = null,
    /**
     * The last dictation's words while they are kept ([app.amethyst.core.dictation.KeptDictation.lastDictation]):
     * ten minutes, also when they did not go in. Null: no card.
     */
    lastDictation: String? = null,
    /** How many dictations the history keeps now; null while it is off or in the security mode: no row. */
    historyCount: Int? = null,
    onOpenHistory: () -> Unit = {},
    /** Offers the history to an install from before it, once ([app.amethyst.core.history.HistoryOffer]). */
    historyOffer: Boolean = false,
    /** The offer answered: true "Turn on", false "No thanks" - either way for good. */
    onHistoryOffer: (Boolean) -> Unit = {},
    /** A failed dictation's recording is kept ([app.amethyst.core.dictation.KeptDictation]): "Try again" under the failure. */
    canTryAgain: Boolean = false,
    onTryAgain: () -> Unit = {},
    /**
     * A recording an earlier process left on disk when it was ended mid-dictation
     * ([app.amethyst.core.dictation.RecordingSpool]): "Recover last recording" recognizes it
     * into the test field, Delete throws it away.
     */
    leftoverRecording: Boolean = false,
    onRecoverRecording: () -> Unit = {},
    onDeleteRecording: () -> Unit = {},
    /** Home's card about the old Amber, dismissed: it stays away while that app's service is off. */
    onDismissOldAmber: () -> Unit = {},
    /**
     * Android's offline pack for the language its recognition hears, while that is the active
     * one and Android said ([app.amethyst.core.onboarding.FirstRun.languagePack]).
     */
    languagePack: LanguagePack? = null,
    /** Asks Android again about that pack; null where there is nothing to ask. */
    checkLanguagePack: suspend () -> LanguagePack? = { null },
    /** Whether the phone has a recognizer of Android's; null while it is read. */
    androidRecognizer: Boolean? = null,
    /** Opens the maker's extra battery step ([SwitchOnPlan.batteryExtra]); no return is watched. */
    onOpenBatteryExtra: (SwitchOnStep) -> Unit = {},
    /** That step marked done: its row goes for good. */
    onBatteryExtraDone: () -> Unit = {},
) {
    val testField = remember { FocusRequester() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PageGutter)
            .padding(bottom = 32.dp),
    ) {
        // First run: nothing else matters until these two are done.
        AnimatedVisibility(
            visible = !setup.ready,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut(tween(250)) + shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)),
        ) {
            Column {
                SetupCard(setup, actions, Modifier)
                // Back from Android's settings without the service, on an install Android may lock.
                if (setup.accessibilityTried && setup.restrictedSettings) RestrictedSettingsSteps(actions.openAppInfo)
            }
        }

        // Android lists the service as on, but it never connected: no bubble, no insertion, whatever "ready" says.
        if (setup.serviceNotRunning) ServiceNotRunningCard(actions.openAccessibilityList)

        if (update != null) UpdateCard(update, { onDownloadUpdate(update) }, { onDismissUpdate(update) })

        if (historyOffer && setup.ready) HistoryOfferCard({ onHistoryOffer(true) }, { onHistoryOffer(false) })

        if (leftoverRecording) {
            RecoverRecordingCard(
                onRecover = {
                    // The words go where a dictation on Home goes: the test field.
                    runCatching { testField.requestFocus() }
                    onRecoverRecording()
                },
                onDelete = onDeleteRecording,
            )
        }

        val context = LocalContext.current
        val others = remember(setup.oldAmbersInstalled, setup.otherAmbers, settings.ui.oldAmberDismissed) {
            OtherAmbers.notice(context.packageName, setup.oldAmbersInstalled, setup.otherAmbers, settings.ui.oldAmberDismissed)
        }
        if (others.uninstall.isNotEmpty()) {
            OldAmberCard(others.uninstall, { pkg -> Setup.openAppInfo(context, pkg) }, onDismissOldAmber)
        }
        if (others.alsoOn.isNotEmpty()) OtherAmbersCard(others.alsoOn, actions.openAccessibilityList)

        EngineGroup(settings, onOpenRecognition, onOpenCleanup, nothingRecognizes = FirstRun.nothingRecognizes(settings, androidRecognizer))

        // Said here too: someone who skipped the introduction never saw its last step.
        if (pending != null) PendingModelNote(pending, Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp))
        // Android's recognition without its language pack fails every dictation, and the toast
        // over other apps sends the user here: the way to the pack stays until it is there, not
        // only under a failure, which is gone after a few seconds. Asked again on every return.
        LanguagePackWatch(settings.activeEngine, checkLanguagePack)
        LanguagePackNote(languagePack, onInstallLanguage, onOpenRecognition, Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp))

        // Collected here, not at the root: the amplitude changes 50 times a second while
        // listening, and only the flame and its status line need to follow it.
        OrbArea(
            dictationState, setup.ready, download, testField, onStartTest, onStopTest, onInstallLanguage, onUseLocalWhisper, onOpenActiveEngine, nudge,
            onTryAgain = if (canTryAgain) {
                {
                    runCatching { testField.requestFocus() }
                    onTryAgain()
                }
            } else {
                null
            },
        )

        // Right under the flame and its status line: the first dictation is typed here.
        TestFieldCard(testField, Modifier)

        // What did not go in can be taken from here; what did, once more.
        if (lastDictation != null) LastDictationCard(lastDictation)

        HistoryAndToday(historyCount, onOpenHistory, settings.stats.keep, stats, onOpenStats)

        val reliable = setup.notificationsGranted && setup.batteryUnrestricted
        // Samsung's "Never sleeping apps", Xiaomi's "Autostart": a row of its own once the
        // battery is unrestricted - the return after "Unrestricted" would end a numbered step.
        val extra = SwitchOnPlan.batteryExtraShown(SettingsMaker.of(Build.MANUFACTURER), setup.batteryUnrestricted, settings.ui.batteryExtraDone)
        if (setup.ready && (!reliable || extra != null)) {
            ReliabilitySection(setup, actions, extra, { extra?.let(onOpenBatteryExtra) }, onBatteryExtraDone)
        }
    }
}

@Composable
private fun OrbArea(
    dictationState: StateFlow<DictationState>,
    ready: Boolean,
    download: ModelDownload?,
    testField: FocusRequester,
    onStartTest: () -> Unit,
    onStopTest: () -> Unit,
    onInstallLanguage: () -> Unit,
    onUseLocalWhisper: () -> Unit,
    onOpenActiveEngine: () -> Unit,
    nudge: app.amethyst.core.onboarding.RealAppNudge?,
    onTryAgain: (() -> Unit)?,
) {
    val dictation by dictationState.collectAsStateWithLifecycle()
    val running = dictation !is DictationState.Idle &&
        dictation !is DictationState.Failed &&
        dictation !is DictationState.Done &&
        dictation !is DictationState.Commanded
    // Why the last dictation failed stays under the flame until the next tap or until Home is
    // left (remember, not saved): the run shows its failure for a few seconds only, as the bubble
    // does, and the reason was gone before it was read. A new run ends it too.
    var failure by remember { mutableStateOf<DictationState.Failed?>(null) }
    LaunchedEffect(dictation) {
        when (val now = dictation) {
            is DictationState.Failed -> failure = now
            DictationState.Idle -> Unit
            else -> failure = null
        }
    }
    // Leaving the app counts as leaving Home: Home stays composed while Embersay is in the
    // background, but the state is not collected then - a bubble dictation meanwhile would go
    // unseen, and the old reason (and "Try again" for the newer recording) would still be shown.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { failure = null }
    val status = failure?.takeIf { dictation == DictationState.Idle } ?: dictation
    fun tapped(action: () -> Unit): () -> Unit = {
        failure = null
        action()
    }
    val view = LocalView.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DictateOrb(
            state = dictation,
            enabled = ready,
            size = 112.dp,
            onClick = tapped {
                if (running) {
                    onStopTest()
                } else {
                    // Felt as well as seen: the dictation starts.
                    view.performHapticFeedback(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
                    )
                    // The words are typed into whatever has focus, so make sure it is
                    // the test field and not nothing.
                    runCatching { testField.requestFocus() }
                    onStartTest()
                }
            },
        )
        StatusLine(
            state = status,
            ready = ready,
            download = download,
            onInstallLanguage = tapped(onInstallLanguage),
            onUseLocalWhisper = tapped(onUseLocalWhisper),
            onOpenActiveEngine = tapped(onOpenActiveEngine),
            onTryAgain = onTryAgain?.let(::tapped),
        )
        // Hidden while a dictation runs: the line above says what happens then.
        if (nudge != null && !running) {
            Text(
                Texts.realAppNudge(LocalContext.current, nudge),
                style = MaterialTheme.typography.bodyMedium,
                color = AmberPalette.Primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

/**
 * Which engine dictates and where the voice goes, and below it who tidies the text when anyone
 * does - it may go further than the recording. Each row leads to its own choice.
 */
@Composable
private fun EngineGroup(settings: AmethystSettings, onOpenRecognition: () -> Unit, onOpenCleanup: () -> Unit, nothingRecognizes: Boolean) {
    val active = settings.activeEngine
    val cleanup = settings.cleanupSummary(badged = true)
    val cleanupEngine = settings.cleanupEngine()
    // Apps with a cleanup model of their own send text where the rows above may not say.
    val ownModels = settings.profiles.filter { it.appCleanup.mode == AppCleanupMode.MODEL && settings.cleanupEngine(it.appCleanup.choice) != null }
    val ownWeakest = ownModels.mapNotNull { settings.cleanupEngine(it.appCleanup.choice)?.trustLevelForDisplay() }.maxByOrNull { it.strictness }
    // Apps with a speech recognition of their own send the voice where the first row may not say.
    fun ownEngine(profile: AppProfile) = settings.engines.firstOrNull { it.id == profile.appEngine.engineId }
    val ownEngines = settings.profiles.filter { it.appEngine.mode == AppEngineMode.ENGINE && ownEngine(it) != null }
    val ownEngineWeakest = ownEngines.mapNotNull { ownEngine(it)?.trustLevelForDisplay() }.maxByOrNull { it.strictness }
    SettingsGroup(label = null) {
        NavRow(
            // The badge under it says where it runs; the name need not.
            title = active?.let { recognitionRowName(it) } ?: stringResource(R.string.home_no_engine),
            // Android's recognition where it may send the voice to its maker - the quick setup
            // picks it where no model fits: said, not only badged, with the way to another a tap away.
            summary = when {
                // Said before anything about where the voice goes: nothing recognizes at all.
                nothingRecognizes -> stringResource(R.string.home_no_recognition)
                active.mayGoToMaker() -> stringResource(R.string.home_android_leaves)
                else -> null
            },
            summaryMaxLines = 3,
            icon = AmberIcons.Mic,
            titleColor = if (active == null || nothingRecognizes) AmberPalette.Error else AmberPalette.Text,
            below = active?.let { { TrustBadge(it.trustLevelForDisplay()) } },
            onClick = onOpenRecognition,
        )
        if (ownEngineWeakest != null) {
            RowDivider()
            NavRow(
                title = stringResource(R.string.home_own_recognition),
                summary = stringResource(R.string.home_own_cleanup_in, ownEngines.joinToString { it.label }),
                icon = AmberIcons.Tune,
                summaryMaxLines = 2,
                below = { TrustBadge(ownEngineWeakest) },
                onClick = onOpenRecognition,
            )
        }
        if (cleanup != null && cleanupEngine != null) {
            RowDivider()
            NavRow(
                title = stringResource(R.string.settings_cleanup),
                summary = cleanup,
                icon = AmberIcons.AutoFix,
                // Where the text goes: said in full, not cut off.
                summaryMaxLines = 2,
                below = { TrustBadge(cleanupEngine.trustLevelForDisplay()) },
                onClick = onOpenCleanup,
            )
        }
        if (ownWeakest != null) {
            RowDivider()
            NavRow(
                title = stringResource(R.string.home_own_cleanup),
                summary = stringResource(R.string.home_own_cleanup_in, ownModels.joinToString { it.label }),
                icon = AmberIcons.Subject,
                summaryMaxLines = 2,
                below = { TrustBadge(ownWeakest) },
                onClick = onOpenCleanup,
            )
        }
    }
}

/** A newer Amber is out: where to get it, or away with it until the next version. */
@Composable
private fun UpdateCard(notice: UpdateNotice, onDownload: () -> Unit, onDismiss: () -> Unit) {
    SettingsGroup(label = null) {
        NavRow(
            title = stringResource(R.string.update_available_title, notice.version),
            summary = stringResource(R.string.installs_over),
            icon = AmberIcons.Download,
            summaryMaxLines = 2,
            trailing = null,
            onClick = null,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GhostButton(stringResource(R.string.dismiss), onDismiss, color = AmberPalette.Muted)
            PrimaryButton(stringResource(R.string.download), onDownload)
        }
    }
}

/**
 * The history, offered once to an install from before it: its disclosure did not name it, so it
 * starts off ([app.amethyst.core.history.HistoryOffer]). Either answer ends the offer.
 */
@Composable
private fun HistoryOfferCard(onTurnOn: () -> Unit, onNoThanks: () -> Unit) {
    SettingsGroup(label = null) {
        NavRow(
            title = stringResource(R.string.settings_history),
            summary = stringResource(R.string.history_offer),
            icon = AmberIcons.History,
            summaryMaxLines = 3,
            trailing = null,
            onClick = null,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GhostButton(stringResource(R.string.history_offer_no), onNoThanks, color = AmberPalette.Muted)
            PrimaryButton(stringResource(R.string.history_offer_on), onTurnOn)
        }
    }
}

/**
 * Embersay was ended while a dictation recorded or was being written, and its recording is still
 * on disk: recovered, it is recognized into the test field; deleted, it is gone.
 */
@Composable
private fun RecoverRecordingCard(onRecover: () -> Unit, onDelete: () -> Unit) {
    SettingsGroup(label = null) {
        NavRow(
            title = stringResource(R.string.home_recover_title),
            summary = stringResource(R.string.home_recover_summary),
            icon = AmberIcons.Mic,
            summaryMaxLines = 4,
            trailing = null,
            onClick = null,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GhostButton(stringResource(R.string.home_recover_delete), onDelete, color = AmberPalette.Muted)
            PrimaryButton(stringResource(R.string.home_recover), onRecover, Modifier.weight(1f, fill = false))
        }
    }
}

/**
 * The old Amber (app.amber, from before the rename to Embersay) is still installed: two apps,
 * two bubbles once both services are on. Its app info has Uninstall - this app cannot remove
 * another itself. Dismissed, it comes back only while the old app's service is on.
 */
@Composable
private fun OldAmberCard(old: List<AmberVariant>, onOpenAppInfo: (String) -> Unit, onDismiss: () -> Unit) {
    SettingsGroup(label = null) {
        NavRow(
            title = stringResource(R.string.old_amber_title),
            summary = stringResource(R.string.old_amber_summary),
            icon = AmberIcons.Info,
            summaryMaxLines = 3,
            trailing = null,
            onClick = null,
        )
        // Usually one; with the old debug build too, one button each.
        old.forEach { variant ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (variant == old.last()) GhostButton(stringResource(R.string.dismiss), onDismiss, color = AmberPalette.Muted)
                PrimaryButton(Texts.uninstallOld(LocalContext.current, variant, named = old.size > 1), { onOpenAppInfo(variant.packageName) })
            }
        }
    }
}

/** The accessibility switch is on, the service is not running: off and on again brings it back. */
@Composable
private fun ServiceNotRunningCard(onOpenSettings: () -> Unit) {
    SettingsGroup(label = null) {
        NavRow(
            title = stringResource(R.string.service_not_running_title),
            summary = stringResource(R.string.service_not_running),
            icon = AmberIcons.Info,
            summaryMaxLines = 4,
            trailing = null,
            onClick = null,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            PrimaryButton(stringResource(R.string.open_accessibility_settings), onOpenSettings)
        }
    }
}

/**
 * Another Embersay's service is on beside this one (the debug build, say): both show a bubble and
 * both react to the keyboard and the field, so things go wrong at random. Home and Diagnostics.
 */
@Composable
fun OtherAmbersCard(others: List<AmberVariant>, onOpenSettings: () -> Unit) {
    val notice = Texts.otherAmbers(LocalContext.current, others) ?: return
    SettingsGroup(label = null) {
        NavRow(
            title = if (others.size == 1) stringResource(R.string.other_ambers_two)
            else stringResource(R.string.other_ambers_title, others.size + 1),
            summary = stringResource(R.string.other_ambers_summary, notice),
            icon = AmberIcons.Info,
            summaryMaxLines = 3,
            trailing = null,
            onClick = null,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            PrimaryButton(stringResource(R.string.open_accessibility_settings), onOpenSettings)
        }
    }
}

/**
 * The last dictation's words, with Copy: for text a field refused, which is pasted by hand. On
 * the clipboard marked sensitive, as the bubble's Copy last does; Android confirms a copy itself
 * from version 13.
 */
@Composable
private fun LastDictationCard(text: String) {
    val context = LocalContext.current
    ShowsDictatedText(true)
    SettingsGroup(label = null) {
        NavRow(
            title = stringResource(R.string.home_last_dictation),
            summary = text,
            icon = AmberIcons.Subject,
            summaryColor = AmberPalette.Text,
            summaryMaxLines = 3,
            trailing = {
                GhostButton(stringResource(R.string.copy), {
                    if (copySensitive(context, text, label = app.amethyst.core.insert.OwnClip.LABEL) && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, R.string.dictation_copied, Toast.LENGTH_SHORT).show()
                    }
                })
            },
            onClick = null,
        )
    }
}

/**
 * The history and today's numbers: one group of two rows, or - before the first dictation, when
 * both would only say "nothing yet" - one row that says where the dictations will show up.
 */
@Composable
private fun HistoryAndToday(
    historyCount: Int?,
    onOpenHistory: () -> Unit,
    keepStats: Boolean,
    stats: StatsSummary?,
    onOpenStats: () -> Unit,
) {
    val statsEmpty = stats == null || stats.isEmpty
    val historyEmpty = historyCount == null || historyCount == 0
    if (historyEmpty && (!keepStats || statsEmpty)) {
        // Nothing to open yet: one line, the door stays the history where there is one.
        if (historyCount != null || keepStats) {
            SettingsGroup(label = null) {
                NavRow(
                    stringResource(R.string.home_dictations_here),
                    null,
                    icon = AmberIcons.History,
                    onClick = if (historyCount != null) onOpenHistory else onOpenStats,
                )
            }
        }
        return
    }
    SettingsGroup(label = null) {
        if (historyCount != null) {
            NavRow(
                stringResource(R.string.settings_history),
                if (historyCount == 0) stringResource(R.string.history_empty) else pluralStringResource(R.plurals.history_count, historyCount, historyCount),
                icon = AmberIcons.History,
                onClick = onOpenHistory,
            )
        }
        if (keepStats) TodayRow(stats, onOpenStats)
    }
}

/** Today in one row - the door to the statistics. */
@Composable
private fun TodayRow(stats: StatsSummary?, onOpenStats: () -> Unit) {
    val (title, summary) = when {
        stats == null || stats.isEmpty -> stringResource(R.string.home_no_dictations) to stringResource(R.string.home_counted_here)
        stats.dictationsToday == 0 -> stringResource(R.string.home_nothing_today) to
            stringResource(R.string.home_words_so_far, formatCount(stats.totalWords), daysInARow(stats.streakDays))
        else -> stringResource(R.string.home_words_today, formatCount(stats.wordsToday)) to
            (pluralStringResource(R.plurals.home_dictations_today, stats.dictationsToday, stats.dictationsToday) + " · " + formatMinutesSaved(stats.minutesSavedToday))
    }
    NavRow(title, summary, icon = AmberIcons.BarChart, onClick = onOpenStats)
}

@Composable
private fun ReliabilitySection(
    setup: SetupState,
    actions: SetupActions,
    extra: SwitchOnStep?,
    onOpenExtra: () -> Unit,
    onExtraDone: () -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.settings_reliability),
        subtitle = stringResource(R.string.reliability_subtitle),
    ) {
        ReliabilityRows(setup, actions)
        if (extra != null) BatteryExtraRow(extra, onOpenExtra, onExtraDone)
    }
}

/**
 * The maker's extra battery step ([SwitchOnPlan.batteryExtraShown]): what to do and where,
 * "Open" for the closest screen there is, and "Done" - Android reports nothing that would
 * tell, so the user says when.
 */
@Composable
private fun BatteryExtraRow(step: SwitchOnStep, onOpen: () -> Unit, onDone: () -> Unit) {
    val (title, note) = Texts.switchOnStep(LocalContext.current, step, "")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusRow(label = title, description = note.orEmpty(), done = false, actionLabel = null, onAction = {})
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            GhostButton(stringResource(R.string.battery_extra_done), onDone, color = AmberPalette.Muted)
            GhostButton(stringResource(R.string.open), onOpen)
        }
    }
}

/** What keeps a dictation in another app working and said: notifications and the battery. */
@Composable
fun ReliabilityRows(setup: SetupState, actions: SetupActions) {
    StatusRow(
        label = stringResource(R.string.reliability_notifications),
        description = stringResource(R.string.reliability_notifications_note),
        done = setup.notificationsGranted,
        actionLabel = stringResource(R.string.allow),
        onAction = actions.requestNotifications,
    )
    StatusRow(
        label = stringResource(R.string.reliability_battery),
        description = stringResource(R.string.reliability_battery_note),
        done = setup.batteryUnrestricted,
        actionLabel = stringResource(R.string.open),
        onAction = actions.openBattery,
    )
    if (!setup.batteryUnrestricted) BatterySteps()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusLine(
    state: DictationState,
    ready: Boolean,
    download: ModelDownload?,
    onInstallLanguage: () -> Unit,
    onUseLocalWhisper: () -> Unit,
    onOpenActiveEngine: () -> Unit,
    onTryAgain: (() -> Unit)?,
) {
    // Said by a screen reader as it changes - "Listening", "Inserted", why it failed - without
    // taking the user's place; the words as they are heard are not, they change too often.
    val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    AnimatedContent(
        targetState = state,
        contentKey = { it::class },
        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
        label = "status",
    ) { current ->
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (current) {
                DictationState.Idle -> when {
                    !ready -> Hint(stringResource(R.string.home_finish_setup))
                    download != null -> {
                        Hint(Texts.download(LocalContext.current, download), AmberPalette.Text)
                        // A line only while bytes come - moving, when the size is not known yet.
                        // Waiting for Wi-Fi or for the next try, the words say it all.
                        if (download is ModelDownload.Running) {
                            ProgressLine(DownloadNotice.fraction(download), Modifier.padding(horizontal = 48.dp))
                        }
                    }
                    else -> {
                        Hint(stringResource(R.string.home_tap_and_speak))
                        // Home was left since the failure, or another run came between: the
                        // recording is still kept a while.
                        if (onTryAgain != null) GhostButton(stringResource(R.string.quick_try_again), onTryAgain)
                    }
                }

                DictationState.Starting -> Hint(stringResource(R.string.home_opening_mic), AmberPalette.Text, live)

                is DictationState.Listening -> {
                    Hint(stringResource(if (current.paused) R.string.home_paused else R.string.home_listening), AmberPalette.Text, live)
                    current.partial?.takeIf { it.isNotBlank() }?.let { partial ->
                        Text(
                            partial,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AmberPalette.Text,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                is DictationState.Transcribing -> Hint(stringResource(R.string.home_writing), AmberPalette.Text, live)

                is DictationState.Done -> {
                    Row(
                        live,
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TrustBadge(current.transcript.trustLevel)
                        // The timing and the way in are for Diagnostics; here only where the text went.
                        Hint(stringResource(if (current.strategy == InsertionStrategy.CLIPBOARD_ONLY) R.string.home_done_clipboard else R.string.home_done))
                    }
                }

                is DictationState.Commanded -> Hint(stringResource(R.string.home_command, current.did), AmberPalette.Text, live)

                is DictationState.Failed -> {
                    Text(
                        current.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AmberPalette.Error,
                        textAlign = TextAlign.Center,
                        modifier = live,
                    )
                    when (current.remedy) {
                        // A FlowRow: at 200 % font or in German the second button goes under the first.
                        SttFailure.Remedy.INSTALL_LANGUAGE -> FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PrimaryButton(stringResource(R.string.home_get_language_pack), onInstallLanguage)
                            GhostButton(stringResource(R.string.home_use_whisper), onUseLocalWhisper)
                        }

                        SttFailure.Remedy.DOWNLOAD_MODEL -> PrimaryButton(stringResource(R.string.home_download_model), onOpenActiveEngine)

                        // The recording is kept: once more, as from the quick menu.
                        null -> if (onTryAgain != null) PrimaryButton(stringResource(R.string.quick_try_again), onTryAgain)
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupCard(setup: SetupState, actions: SetupActions, modifier: Modifier) {
    // What the service reads and does, said before Android asks - and agreed to, not just scrolled past.
    var disclosing by remember { mutableStateOf(false) }
    if (disclosing) {
        AccessibilityConsentDialog(
            restricted = setup.restrictedSettings,
            onAgree = {
                disclosing = false
                actions.openAccessibility()
            },
            onDismiss = { disclosing = false },
        )
    }
    SectionCard(
        title = stringResource(R.string.setup_title),
        subtitle = stringResource(R.string.setup_subtitle),
        modifier = modifier,
    ) {
        StatusRow(
            label = stringResource(R.string.setup_microphone),
            description = stringResource(R.string.setup_microphone_note),
            done = setup.microphoneGranted,
            actionLabel = stringResource(R.string.allow),
            onAction = actions.requestMicrophone,
        )
        // Where the recording and its text go, and what is kept: the introduction says it on its
        // microphone step, and this card is the way for whoever skipped that step - here, with
        // the microphone, and not in the accessibility consent, which Play wants about the service alone.
        if (!setup.microphoneGranted) Hint(stringResource(R.string.mic_where))
        StatusRow(
            label = stringResource(R.string.setup_accessibility),
            description = if (setup.accessibilityTried) {
                stringResource(R.string.setup_accessibility_tried, stringResource(R.string.accessibility_label))
            } else {
                stringResource(R.string.setup_accessibility_note)
            },
            done = setup.accessibilityEnabled,
            actionLabel = stringResource(R.string.open),
            onAction = { disclosing = true },
        )
    }
}
