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
import app.amethyst.core.dictation.DictationRecord
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.dictation.DictationState
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

@Composable
fun HomeScreen(
    setup: SetupState,
    actions: SetupActions,
    settings: AmethystSettings,
    dictation: DictationState,
    records: List<DictationRecord>,
    keyboardLog: List<String>,
    onStartTest: () -> Unit,
    onStopTest: () -> Unit,
    onClearDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenActiveEngine: () -> Unit,
    onInstallLanguage: () -> Unit,
    onUseLocalWhisper: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val testField = remember { FocusRequester() }
    val running = dictation !is DictationState.Idle &&
        dictation !is DictationState.Failed &&
        dictation !is DictationState.Done

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        EngineChip(settings, onOpenSettings, Modifier)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DictateOrb(
                state = dictation,
                enabled = setup.ready,
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
                ready = setup.ready,
                onInstallLanguage = onInstallLanguage,
                onUseLocalWhisper = onUseLocalWhisper,
                onOpenActiveEngine = onOpenActiveEngine,
            )
        }

        TestFieldCard(testField, Modifier)

        AnimatedVisibility(
            visible = !setup.ready,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut(tween(250)) + shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)),
        ) {
            SetupCard(setup, actions, Modifier)
        }

        val reliable = setup.micServiceRunning && setup.notificationsGranted && setup.batteryUnrestricted
        SectionCard(
            title = "Zuverlässigkeit",
            subtitle = if (reliable) "Alles eingerichtet." else "Optional. Verhindert, dass Android das Mikrofon abdreht.",
            collapsible = true,
            initiallyExpanded = !reliable,
        ) {
            StatusRow(
                label = "Mikrofon-Dienst",
                description = "Hält das Mikrofon bereit, während du in anderen Apps bist.",
                done = setup.micServiceRunning,
                actionLabel = "Starten",
                onAction = actions.startMicService,
            )
            StatusRow(
                label = "Benachrichtigungen",
                description = "Nur für die stille „Amber ist bereit“-Meldung.",
                done = setup.notificationsGranted,
                actionLabel = "Erlauben",
                onAction = actions.requestNotifications,
            )
            StatusRow(
                label = "Akku unbeschränkt",
                description = "Sonst beenden manche Hersteller den Dienst im Hintergrund.",
                done = setup.batteryUnrestricted,
                actionLabel = "Öffnen",
                onAction = actions.openBattery,
            )
        }

        DiagnosticsCard(
            records = records,
            keyboardLog = keyboardLog,
            onClear = onClearDiagnostics,
        )
    }
}

/** Which engine dictates, as one plain row that leads to the settings. */
@Composable
private fun EngineChip(settings: AmethystSettings, onOpenSettings: () -> Unit, modifier: Modifier) {
    val active = settings.activeEngine
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenSettings)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = active?.displayName ?: "Keine Engine ausgewählt",
                style = MaterialTheme.typography.bodyLarge,
                color = if (active == null) AmberPalette.Error else AmberPalette.Text,
            )
            if (active != null) TrustBadge(active.trustLevelForDisplay())
        }
        Text("Ändern", style = MaterialTheme.typography.labelLarge, color = AmberPalette.Primary)
    }
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
                    if (ready) "Antippen und sprechen. Der Text landet im Testfeld."
                    else "Erst die Einrichtung unten abschließen.",
                )

                DictationState.Starting -> Hint("Mikrofon öffnet …", AmberPalette.Text)

                is DictationState.Listening -> {
                    Hint("Hört zu. Antippen zum Beenden.", AmberPalette.Text)
                    current.partial?.takeIf { it.isNotBlank() }?.let { partial ->
                        Text(
                            partial,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AmberPalette.Text,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                DictationState.Transcribing -> Hint("Schreibt …", AmberPalette.Text)

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
                            PrimaryButton("Sprachpaket laden", onInstallLanguage)
                            GhostButton("Whisper lokal nutzen", onUseLocalWhisper)
                        }

                        SttFailure.Remedy.DOWNLOAD_MODEL -> PrimaryButton("Modell laden", onOpenActiveEngine)

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
        title = "Einrichtung",
        subtitle = "Zwei Schritte, dann funktioniert die Blase in jeder App.",
        modifier = modifier,
    ) {
        StatusRow(
            label = "Mikrofon",
            description = "Amber nimmt nur auf, während du diktierst.",
            done = setup.microphoneGranted,
            actionLabel = "Erlauben",
            onAction = actions.requestMicrophone,
        )
        StatusRow(
            label = "Bedienungshilfe",
            description = "Zeigt die Blase an und tippt den Text ins aktive Feld.",
            done = setup.accessibilityEnabled,
            actionLabel = "Öffnen",
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
            "Ausgegraut oder „Eingeschränkte Einstellung“?",
            style = MaterialTheme.typography.bodyLarge,
            color = AmberPalette.Text,
        )
        AnimatedVisibility(visible = open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint(
                    "Android sperrt die Bedienungshilfe für Apps, die nicht aus einem Store " +
                        "kommen. So hebst du die Sperre einmalig auf:",
                    AmberPalette.Text,
                )
                Hint("1. Unten auf „App-Info öffnen“ tippen.")
                Hint("2. Oben rechts ⋮ → „Eingeschränkte Einstellungen zulassen“, dann bestätigen.")
                Hint("3. Zurück und die Bedienungshilfe einschalten.")
                Hint("Der Menüpunkt erscheint erst, nachdem du einmal versucht hast, sie einzuschalten.")
                Spacer(Modifier.height(2.dp))
                GhostButton("App-Info öffnen", onOpenAppInfo)
            }
        }
        if (!open) Hint("Antippen für die Lösung.")
    }
}

private fun app.amethyst.core.insert.InsertionStrategy.label(): String = when (this) {
    app.amethyst.core.insert.InsertionStrategy.COMMIT_TEXT -> "direkt getippt"
    app.amethyst.core.insert.InsertionStrategy.SET_TEXT -> "ins Feld geschrieben"
    app.amethyst.core.insert.InsertionStrategy.PASTE -> "eingefügt"
    app.amethyst.core.insert.InsertionStrategy.CLIPBOARD_ONLY -> "in der Zwischenablage"
}
