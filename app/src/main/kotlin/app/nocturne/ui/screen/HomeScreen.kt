package app.nocturne.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nocturne.core.dictation.DictationRecord
import app.nocturne.core.settings.NocturneSettings
import app.nocturne.dictation.DictationState
import app.nocturne.ui.Hint
import app.nocturne.ui.SectionCard
import app.nocturne.ui.SetupActions
import app.nocturne.ui.SetupState
import app.nocturne.ui.StatusRow
import app.nocturne.ui.TrustBadge
import app.nocturne.ui.theme.NocturnePalette
import app.nocturne.ui.theme.badgeExplanation

@Composable
fun HomeScreen(
    setup: SetupState,
    actions: SetupActions,
    settings: NocturneSettings,
    dictation: DictationState,
    records: List<DictationRecord>,
    onStartTest: () -> Unit,
    onStopTest: () -> Unit,
    onClearDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!setup.ready) {
            SectionCard(
                title = "Einrichtung",
                subtitle = "Zwei Schritte, dann funktioniert die Blase in jeder App.",
            ) {
                StatusRow(
                    label = "Mikrofon",
                    description = "Nocturne nimmt nur auf, während du die Blase gedrückt hast.",
                    done = setup.microphoneGranted,
                    actionLabel = "Erlauben",
                    onAction = actions.requestMicrophone,
                )
                StatusRow(
                    label = "Bedienungshilfe",
                    description = "Zeigt die Blase an und schreibt den Text ins aktive Feld.",
                    done = setup.accessibilityEnabled,
                    actionLabel = "Öffnen",
                    onAction = actions.openAccessibility,
                )
            }
        }

        TestDictationCard(
            dictation = dictation,
            canDictate = setup.ready,
            onStartTest = onStartTest,
            onStopTest = onStopTest,
        )

        SectionCard(
            title = "Zuverlässigkeit",
            subtitle = "Optional, verhindert aber, dass Android das Mikrofon abdreht.",
        ) {
            StatusRow(
                label = "Mikrofon-Dienst läuft",
                description = "Hält das Mikrofon verfügbar, während du in anderen Apps bist. " +
                    "Ohne ihn weicht Nocturne auf ein unsichtbares Fenster aus.",
                done = setup.micServiceRunning,
                actionLabel = "Starten",
                onAction = actions.startMicService,
            )
            StatusRow(
                label = "Benachrichtigungen",
                description = "Nur für die stille \"Nocturne ist bereit\"-Meldung.",
                done = setup.notificationsGranted,
                actionLabel = "Erlauben",
                onAction = actions.requestNotifications,
            )
            StatusRow(
                label = "Akku unbeschränkt",
                description = "Sonst beenden manche Hersteller-ROMs den Dienst im Hintergrund.",
                done = setup.batteryUnrestricted,
                actionLabel = "Öffnen",
                onAction = actions.openBattery,
            )
        }

        SectionCard(title = "Aktive Engine") {
            val active = settings.activeEngine
            if (active == null) {
                Hint("Keine Engine ausgewählt.", NocturnePalette.Error)
                Button(onClick = onOpenSettings) { Text("Engine wählen") }
            } else {
                Text(
                    text = active.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = NocturnePalette.Text,
                )
                Hint(
                    buildString {
                        append(active.modelId ?: "Systemmodell")
                        append(" · ")
                        append(active.languageTag ?: "Sprache automatisch")
                    },
                )
                Button(onClick = onOpenSettings) { Text("Engine wechseln") }
            }
        }

        SectionCard(title = "Status") {
            when (val state = dictation) {
                DictationState.Idle -> Hint("Bereit. Tippe die Blase an.")
                DictationState.Starting -> Hint("Mikrofon öffnet …")
                is DictationState.Listening -> {
                    Hint("Hört zu …", NocturnePalette.Accent)
                    state.partial?.takeIf { it.isNotBlank() }?.let { partial ->
                        Text(
                            text = partial,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NocturnePalette.Muted,
                        )
                    }
                }

                DictationState.Transcribing -> Hint("Schreibt …", NocturnePalette.Accent)

                is DictationState.Failed -> Hint(state.message, NocturnePalette.Error)

                is DictationState.Done -> Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TrustBadge(state.transcript.trustLevel)
                        Hint("${state.transcript.latencyMs} ms · ${state.strategy.name}")
                    }
                    Text(
                        text = state.transcript.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NocturnePalette.Text,
                    )
                    Hint(state.transcript.trustLevel.badgeExplanation())
                }
            }
        }

        DiagnosticsCard(records = records, onClear = onClearDiagnostics)
    }
}
