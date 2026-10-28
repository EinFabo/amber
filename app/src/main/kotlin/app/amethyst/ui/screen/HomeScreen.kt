package app.amethyst.ui.screen

import android.os.Build
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.dictation.DictationState
import app.amethyst.ui.Hairline
import app.amethyst.ui.ChevronRight
import app.amethyst.core.stats.StatsSummary
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amethyst.ui.DictateOrb
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.PrimaryButton
import app.amethyst.ui.SectionCard
import app.amethyst.ui.SetupActions
import app.amethyst.ui.SetupState
import app.amethyst.ui.StatusRow
import app.amethyst.ui.TrustBadge
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes

/**
 * Hearth: one quiet home. The engine on top, the flame alone in the middle, one line of today's
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
    onStartTest: () -> Unit,
    onStopTest: () -> Unit,
    onOpenEngines: () -> Unit,
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
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        // First run: nothing else matters until these two are done.
        AnimatedVisibility(
            visible = !setup.ready,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut(tween(250)) + shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)),
        ) {
            SetupCard(setup, actions, Modifier)
        }

        EngineLine(settings, onOpenEngines)

        // Collected here, not at the root: the amplitude changes 50 times a second while
        // listening, and only the flame and its status line need to follow it.
        OrbArea(dictationState, setup.ready, testField, onStartTest, onStopTest, onInstallLanguage, onUseLocalWhisper, onOpenActiveEngine)

        if (settings.stats.keep) TodayLine(stats, onOpenStats)

        TestFieldCard(testField, Modifier)

        val reliable = setup.micServiceRunning && setup.notificationsGranted && setup.batteryUnrestricted
        if (setup.ready && !reliable) ReliabilitySection(setup, actions)
    }
}

@Composable
private fun OrbArea(
    dictationState: StateFlow<DictationState>,
    ready: Boolean,
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
        dictation !is DictationState.Done
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
            onInstallLanguage = onInstallLanguage,
            onUseLocalWhisper = onUseLocalWhisper,
            onOpenActiveEngine = onOpenActiveEngine,
        )
    }
}

/** Which engine dictates and where the voice goes; leads to the engine choice. */
@Composable
private fun EngineLine(settings: AmethystSettings, onOpenEngines: () -> Unit) {
    val active = settings.activeEngine
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenEngines)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = active?.displayName ?: "No engine selected",
                style = MaterialTheme.typography.bodyLarge,
                color = if (active == null) AmberPalette.Error else AmberPalette.Text,
            )
            if (active != null) TrustBadge(active.trustLevelForDisplay())
        }
        Text("Change", style = MaterialTheme.typography.labelLarge, color = AmberPalette.Primary)
    }
}

/** Today in one line - the door to the statistics. */
@Composable
private fun TodayLine(stats: StatsSummary?, onOpenStats: () -> Unit) {
    Hairline()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenStats)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (stats == null || stats.dictationsToday == 0) {
                Text(
                    if (stats == null || stats.isEmpty) "No dictations yet" else "Nothing dictated today",
                    style = MaterialTheme.typography.titleMedium,
                    color = AmberPalette.Text,
                )
                Hint(
                    if (stats == null || stats.isEmpty) "Your numbers appear here, counted on this phone."
                    else "${formatCount(stats.totalWords)} words so far · ${stats.streakDays.daysInARow()}",
                )
            } else {
                Text(
                    "${formatCount(stats.wordsToday)} words today",
                    style = MaterialTheme.typography.titleMedium,
                    color = AmberPalette.Text,
                )
                Hint("${stats.dictationsToday} ${if (stats.dictationsToday == 1) "dictation" else "dictations"} · ${formatMinutesSaved(stats.minutesSavedToday)}")
            }
        }
        ChevronRight()
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
        description = "Keeps the microphone ready while you are in other apps.",
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
        description = "Otherwise some manufacturers kill the service in the background.",
        done = setup.batteryUnrestricted,
        actionLabel = "Open",
        onAction = actions.openBattery,
    )
}

@Composable
private fun StatusLine(
    state: DictationState,
    ready: Boolean,
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
                DictationState.Idle -> Hint(
                    if (ready) "Tap and speak. The text lands in the test field."
                    else "Finish the setup below first.",
                )

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
            description = "Shows the bubble and types the text into the active field.",
            done = setup.accessibilityEnabled,
            actionLabel = "Open",
            onAction = actions.openAccessibility,
        )
        if (!setup.accessibilityEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RestrictedSettingsHint(onOpenAppInfo = actions.openAppInfo)
        }
    }
}

/**
 * Since Android 13, accessibility is locked for apps installed from a file. There is no API
 * to ask whether the lock applies to us, so the way out is shown whenever the service is off.
 */
@Composable
private fun RestrictedSettingsHint(onOpenAppInfo: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(AmberPalette.Surface, AmberShapes.Inner)
            .clickable { open = !open }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Greyed out or \"Restricted setting\"?",
            style = MaterialTheme.typography.bodyLarge,
            color = AmberPalette.Text,
        )
        AnimatedVisibility(visible = open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint(
                    "Android locks accessibility for apps that do not come from a store. " +
                        "This lifts the lock once:",
                    AmberPalette.Text,
                )
                Hint("1. Tap \"Open app info\" below.")
                Hint("2. Top right ⋮ → \"Allow restricted settings\", then confirm.")
                Hint("3. Go back and turn on the accessibility service.")
                Hint("The menu item only appears after you have tried to turn it on once.")
                Spacer(Modifier.height(2.dp))
                GhostButton("Open app info", onOpenAppInfo)
            }
        }
        if (!open) Hint("Tap for the fix.")
    }
}

private fun app.amethyst.core.insert.InsertionStrategy.label(): String = when (this) {
    app.amethyst.core.insert.InsertionStrategy.COMMIT_TEXT -> "typed directly"
    app.amethyst.core.insert.InsertionStrategy.SET_TEXT -> "written into the field"
    app.amethyst.core.insert.InsertionStrategy.PASTE -> "pasted"
    app.amethyst.core.insert.InsertionStrategy.CLIPBOARD_ONLY -> "on the clipboard"
}
