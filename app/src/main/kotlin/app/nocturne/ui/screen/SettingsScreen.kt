package app.nocturne.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nocturne.core.engine.EngineConfigValidator
import app.nocturne.core.engine.EnginePresets
import app.nocturne.core.settings.EngineConfig
import app.nocturne.core.settings.NocturneSettings
import app.nocturne.ui.Hint
import app.nocturne.ui.SectionCard
import app.nocturne.ui.TrustBadge
import app.nocturne.ui.theme.NocturnePalette

@Composable
fun SettingsScreen(
    settings: NocturneSettings,
    hasKeyFor: (String?) -> Boolean,
    onUpdateSettings: ((NocturneSettings) -> NocturneSettings) -> Unit,
    onOpenEngine: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPresets by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(
            title = "Engines",
            subtitle = "Die aktive Engine entscheidet, wohin deine Stimme geht.",
        ) {
            settings.engines.forEach { engine ->
                EngineRow(
                    config = engine,
                    active = engine.id == settings.activeEngineId,
                    problems = EngineConfigValidator.validate(
                        engine,
                        hasApiKey = hasKeyFor(engine.apiKeyAlias),
                    ),
                    onSelect = { onUpdateSettings { it.copy(activeEngineId = engine.id) } },
                    onOpen = { onOpenEngine(engine.id) },
                )
            }
            TextButton(onClick = { showPresets = true }) {
                Text("Engine hinzufügen", color = NocturnePalette.Primary)
            }
        }

        SectionCard(
            title = "Aufnahme",
            subtitle = "Wann eine Aufnahme endet und wie roh der Text bleibt.",
        ) {
            ToggleRow(
                label = "Automatisch bei Stille beenden",
                description = "Nach ${settings.capture.silenceTimeoutMs} ms Stille wird " +
                    "transkribiert. Aus: die Aufnahme läuft bis zum nächsten Tippen.",
                checked = settings.capture.autoStopOnSilence,
                onChange = { value ->
                    onUpdateSettings { it.copy(capture = it.capture.copy(autoStopOnSilence = value)) }
                },
            )
            ToggleRow(
                label = "Füllwörter entfernen",
                description = "Streicht \"ähm\", \"äh\" und Verwandte. " +
                    "Rein regelbasiert, ohne LLM, also ohne Halluzinationsrisiko.",
                checked = settings.capture.stripFillers,
                onChange = { value ->
                    onUpdateSettings { it.copy(capture = it.capture.copy(stripFillers = value)) }
                },
            )
        }

        SectionCard(
            title = "Einfügen",
            subtitle = "Wie der Text an der Cursorstelle ankommt.",
        ) {
            ToggleRow(
                label = "Leerzeichen setzen",
                description = "Trennt automatisch vom vorherigen Wort.",
                checked = settings.insertion.autoSpace,
                onChange = { value ->
                    onUpdateSettings { it.copy(insertion = it.insertion.copy(autoSpace = value)) }
                },
            )
            ToggleRow(
                label = "Satzanfang großschreiben",
                description = "Nur am Feldanfang und nach . ! ?",
                checked = settings.insertion.autoCapitalize,
                onChange = { value ->
                    onUpdateSettings { it.copy(insertion = it.insertion.copy(autoCapitalize = value)) }
                },
            )
            ToggleRow(
                label = "Satzzeichen ankleben",
                description = "Macht aus \"Hallo , Welt\" ein \"Hallo, Welt\".",
                checked = settings.insertion.gluePunctuation,
                onChange = { value ->
                    onUpdateSettings {
                        it.copy(insertion = it.insertion.copy(gluePunctuation = value))
                    }
                },
            )
        }

        SectionCard(title = "Blase") {
            ToggleRow(
                label = "Blase anzeigen",
                description = "Aus versteckt die Blase, ohne die Bedienungshilfe " +
                    "abzuschalten - Diktieren im Testfeld funktioniert weiter.",
                checked = settings.bubble.enabled,
                onChange = { value ->
                    onUpdateSettings { it.copy(bubble = it.bubble.copy(enabled = value)) }
                },
            )
            ToggleRow(
                label = "Abdunkeln ohne Textfeld",
                description = "Die Blase wird blass, solange kein Eingabefeld aktiv ist.",
                checked = settings.bubble.dimWhenNoTextField,
                onChange = { value ->
                    onUpdateSettings {
                        it.copy(bubble = it.bubble.copy(dimWhenNoTextField = value))
                    }
                },
            )
            ToggleRow(
                label = "Vibrieren",
                description = "Kurzes Feedback beim Start, Erfolg und Fehler.",
                checked = settings.bubble.haptics,
                onChange = { value ->
                    onUpdateSettings { it.copy(bubble = it.bubble.copy(haptics = value)) }
                },
            )
        }
    }

    if (showPresets) {
        PresetDialog(
            existingIds = settings.engines.map { it.id }.toSet(),
            onDismiss = { showPresets = false },
            onPick = { preset ->
                showPresets = false
                onUpdateSettings {
                    it.withEngine(EngineConfig.fromPreset(preset, languageTag = "de-DE"))
                }
                onOpenEngine(preset.id)
            },
        )
    }
}

@Composable
private fun EngineRow(
    config: EngineConfig,
    active: Boolean,
    problems: List<String>,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = active, onClick = onSelect)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = config.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = NocturnePalette.Text,
            )
            Hint(
                // Name the first missing thing rather than a generic "not configured":
                // the user should not have to open the detail screen to find out.
                text = problems.firstOrNull() ?: config.modelId ?: "Systemmodell",
                color = if (problems.isEmpty()) NocturnePalette.Muted else NocturnePalette.Error,
            )
        }
        TextButton(onClick = onOpen) {
            Text("Bearbeiten", color = NocturnePalette.Primary)
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = NocturnePalette.Text,
            )
            Hint(description)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PresetDialog(
    existingIds: Set<String>,
    onDismiss: () -> Unit,
    onPick: (app.nocturne.core.engine.EnginePreset) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NocturnePalette.Elevated,
        title = { Text("Engine hinzufügen", color = NocturnePalette.Text) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                EnginePresets.all
                    .filterNot { it.id in existingIds }
                    .forEach { preset ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(preset) },
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = preset.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = NocturnePalette.Text,
                                )
                                TrustBadge(preset.trustLevel)
                            }
                            if (preset.note.isNotBlank()) Hint(preset.note)
                        }
                    }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen", color = NocturnePalette.Primary)
            }
        },
    )
}
