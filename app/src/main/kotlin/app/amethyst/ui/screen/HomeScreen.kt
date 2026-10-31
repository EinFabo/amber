package app.amethyst.ui.screen

import androidx.compose.ui.res.stringResource
import app.amethyst.R
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.amethyst.core.engine.DownloadNotice
import app.amethyst.core.engine.ModelDownload
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppCleanupMode
import app.amethyst.dictation.DictationState
import app.amethyst.ui.AccessibilityConsentDialog
import app.amethyst.ui.AmberIcons
import app.amethyst.ui.NavRow
import app.amethyst.ui.PageGutter
import app.amethyst.ui.RowDivider
import app.amethyst.ui.SettingsGroup
import app.amethyst.core.stats.StatsSummary
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amethyst.ui.DictateOrb
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.PrimaryButton
import app.amethyst.ui.ProgressLine
import app.amethyst.ui.RestrictedSettingsSteps
import app.amethyst.ui.SectionCard
import app.amethyst.ui.SetupActions
import app.amethyst.ui.SetupState
import app.amethyst.ui.StatusRow
import app.amethyst.ui.TrustBadge
import app.amethyst.ui.theme.AmberPalette

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

        EngineGroup(settings, onOpenRecognition, onOpenCleanup)

        // Collected here, not at the root: the amplitude changes 50 times a second while
        // listening, and only the flame and its status line need to follow it.
        OrbArea(dictationState, setup.ready, download, testField, onStartTest, onStopTest, onInstallLanguage, onUseLocalWhisper, onOpenActiveEngine)

        if (settings.stats.keep) TodayRow(stats, onOpenStats)

        TestFieldCard(testField, Modifier)

        val reliable = setup.micServiceRunning && setup.notificationsGranted && setup.batteryUnrestricted
        if (setup.ready && !reliable) ReliabilitySection(setup, actions)
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
) {
    val dictation by dictationState.collectAsStateWithLifecycle()
    val running = dictation !is DictationState.Idle &&
        dictation !is DictationState.Failed &&
        dictation !is DictationState.Done &&
        dictation !is DictationState.Commanded
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
            onClick = {
                if (running) {
                    onStopTest()
                } else {
                    // The words are typed into whatever has focus, so make sure it is
                    // the test field and not nothing.
                    runCatching { testField.requestFocus() }
                    onStartTest()
                }
            },
        )
        StatusLine(
            state = dictation,
            ready = ready,
            download = download,
            onInstallLanguage = onInstallLanguage,
            onUseLocalWhisper = onUseLocalWhisper,
            onOpenActiveEngine = onOpenActiveEngine,
        )
    }
}

/**
 * Which engine dictates and where the voice goes, and below it who tidies the text when anyone
 * does - it may go further than the recording. Each row leads to its own choice.
 */
@Composable
private fun EngineGroup(settings: AmethystSettings, onOpenRecognition: () -> Unit, onOpenCleanup: () -> Unit) {
    val active = settings.activeEngine
    val cleanup = settings.cleanupSummary()
    val cleanupEngine = settings.cleanupEngine()
    // Apps with a cleanup model of their own send text where the rows above may not say.
    val ownModels = settings.profiles.filter { it.appCleanup.mode == AppCleanupMode.MODEL && settings.cleanupEngine(it.appCleanup.choice) != null }
    val ownWeakest = ownModels.mapNotNull { settings.cleanupEngine(it.appCleanup.choice)?.trustLevelForDisplay() }.maxByOrNull { it.strictness }
    SettingsGroup(label = null) {
        NavRow(
            title = active?.displayName ?: "No engine selected",
            summary = null,
            icon = AmberIcons.Mic,
            titleColor = if (active == null) AmberPalette.Error else AmberPalette.Text,
            below = active?.let { { TrustBadge(it.trustLevelForDisplay()) } },
            onClick = onOpenRecognition,
        )
        if (cleanup != null && cleanupEngine != null) {
            RowDivider()
            NavRow(
                title = "Cleanup",
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
                title = "Own cleanup model",
                summary = "In ${ownModels.joinToString { it.label }}",
                icon = AmberIcons.Palette,
                summaryMaxLines = 2,
                below = { TrustBadge(ownWeakest) },
                onClick = onOpenCleanup,
            )
        }
    }
}

/** Today in one row - the door to the statistics. */
@Composable
private fun TodayRow(stats: StatsSummary?, onOpenStats: () -> Unit) {
    val (title, summary) = when {
        stats == null || stats.isEmpty -> "No dictations yet" to "Counted here, on this phone only."
        stats.dictationsToday == 0 -> "Nothing dictated today" to "${formatCount(stats.totalWords)} words so far · ${stats.streakDays.daysInARow()}"
        else -> "${formatCount(stats.wordsToday)} words today" to
            "${stats.dictationsToday} ${if (stats.dictationsToday == 1) "dictation" else "dictations"} · ${formatMinutesSaved(stats.minutesSavedToday)}"
    }
    SettingsGroup(label = null) {
        NavRow(title, summary, icon = AmberIcons.BarChart, onClick = onOpenStats)
    }
}

@Composable
private fun ReliabilitySection(setup: SetupState, actions: SetupActions) {
    SectionCard(
        title = "Reliability",
        subtitle = "Optional. Keeps Android from cutting off the microphone.",
    ) {
        ReliabilityRows(setup, actions)
    }
}

/** The three things that keep the microphone available in the background. */
@Composable
fun ReliabilityRows(setup: SetupState, actions: SetupActions) {
    StatusRow(
        label = "Microphone service",
        description = "Keeps the microphone ready in other apps.",
        done = setup.micServiceRunning,
        actionLabel = "Start",
        onAction = actions.startMicService,
    )
    StatusRow(
        label = "Notifications",
        description = "Only for the silent \"Amber is ready\" notice.",
        done = setup.notificationsGranted,
        actionLabel = "Allow",
        onAction = actions.requestNotifications,
    )
    StatusRow(
        label = "Unrestricted battery",
        description = "So the phone does not stop the service.",
        done = setup.batteryUnrestricted,
        actionLabel = "Open",
        onAction = actions.openBattery,
    )
}

@Composable
private fun StatusLine(
    state: DictationState,
    ready: Boolean,
    download: ModelDownload?,
    onInstallLanguage: () -> Unit,
    onUseLocalWhisper: () -> Unit,
    onOpenActiveEngine: () -> Unit,
) {
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
                    !ready -> Hint("Finish the setup above first.")
                    download != null -> {
                        Hint(DownloadNotice.short(download), AmberPalette.Text)
                        val fraction = (download as? ModelDownload.Running)
                            ?.let { if (it.total > 0) it.received.toFloat() / it.total else 0f }
                        ProgressLine(fraction, Modifier.padding(horizontal = 48.dp))
                    }
                    else -> Hint("Tap and speak. The text lands in the test field.")
                }

                DictationState.Starting -> Hint("Opening microphone …", AmberPalette.Text)

                is DictationState.Listening -> {
                    Hint("Listening. Tap to stop.", AmberPalette.Text)
                    current.partial?.takeIf { it.isNotBlank() }?.let { partial ->
                        Text(
                            partial,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AmberPalette.Text,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                is DictationState.Transcribing -> Hint("Writing …", AmberPalette.Text)

                is DictationState.Done -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TrustBadge(current.transcript.trustLevel)
                        Hint("${current.transcript.latencyMs} ms · ${current.strategy.label()}")
                    }
                }

                is DictationState.Commanded -> Hint("Command: ${current.did}", AmberPalette.Text)

                is DictationState.Failed -> {
                    Text(
                        current.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AmberPalette.Error,
                        textAlign = TextAlign.Center,
                    )
                    when (current.remedy) {
                        SttFailure.Remedy.INSTALL_LANGUAGE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryButton("Get language pack", onInstallLanguage)
                            GhostButton("Use local Whisper", onUseLocalWhisper)
                        }

                        SttFailure.Remedy.DOWNLOAD_MODEL -> PrimaryButton("Download model", onOpenActiveEngine)

                        null -> Unit
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
        title = "Setup",
        subtitle = "Two steps, then the bubble works in every app.",
        modifier = modifier,
    ) {
        StatusRow(
            label = "Microphone",
            description = "Amber only records while you dictate.",
            done = setup.microphoneGranted,
            actionLabel = "Allow",
            onAction = actions.requestMicrophone,
        )
        StatusRow(
            label = "Accessibility service",
            description = if (setup.accessibilityTried) {
                "Not on yet. Switch on ${stringResource(R.string.accessibility_label)} and allow it."
            } else {
                "Shows the bubble and types the text into the active field."
            },
            done = setup.accessibilityEnabled,
            actionLabel = "Open",
            onAction = { disclosing = true },
        )
    }
}

private fun app.amethyst.core.insert.InsertionStrategy.label(): String = when (this) {
    app.amethyst.core.insert.InsertionStrategy.COMMIT_TEXT -> "typed directly"
    app.amethyst.core.insert.InsertionStrategy.SET_TEXT -> "written into the field"
    app.amethyst.core.insert.InsertionStrategy.PASTE -> "pasted"
    app.amethyst.core.insert.InsertionStrategy.CLIPBOARD_ONLY -> "on the clipboard"
}
