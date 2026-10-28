package app.amethyst.ui.screen

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import app.amethyst.core.engine.EngineConfigValidator
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.SectionCard
import app.amethyst.ui.TrustBadge
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes

@Composable
fun SettingsScreen(
    settings: AmethystSettings,
    hasKeyFor: (String?) -> Boolean,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
    onOpenEngine: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPresets by remember { mutableStateOf(false) }

    // Each hasKeyFor() is an AES-SIV decrypt out of EncryptedSharedPreferences. Computing it
    // per engine row on every recomposition made scrolling the settings screen do crypto.
    val problemsByEngine = remember(settings) {
        settings.engines.associate { engine ->
            engine.id to EngineConfigValidator.validate(
                engine,
                hasApiKey = hasKeyFor(engine.apiKeyAlias),
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        SectionCard(
            title = "Engines",
            subtitle = "Die aktive Engine entscheidet, wohin deine Stimme geht.",
        ) {
            settings.engines.forEach { engine ->
                EngineRow(
                    config = engine,
                    active = engine.id == settings.activeEngineId,
                    problems = problemsByEngine[engine.id].orEmpty(),
                    onSelect = { onUpdateSettings { it.copy(activeEngineId = engine.id) } },
                    onOpen = { onOpenEngine(engine.id) },
                )
            }
            GhostButton("Engine hinzufügen", { showPresets = true })
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
                label = "Zwischenablage als Notlösung",
                description = "Aus: Text wird nur direkt ins Feld getippt, die Zwischenablage " +
                    "bleibt unberührt. An: Klappt das nicht, wird eingefügt oder kopiert.",
                checked = settings.insertion.allowClipboard,
                onChange = { value ->
                    onUpdateSettings { it.copy(insertion = it.insertion.copy(allowClipboard = value)) }
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
            BubbleSizeRow(
                sizeDp = settings.bubble.sizeDp,
                onChange = { value ->
                    onUpdateSettings { it.copy(bubble = it.bubble.copy(sizeDp = value)) }
                },
            )
            ToggleRow(
                label = "Nur bei Textfeld zeigen",
                description = "Die Blase erscheint, sobald du in ein Eingabefeld tippst, " +
                    "und verschwindet danach wieder.",
                checked = settings.bubble.onlyWithTextField,
                onChange = { value ->
                    onUpdateSettings {
                        it.copy(bubble = it.bubble.copy(onlyWithTextField = value))
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
                    it.withEngine(EngineConfig.fromPreset(preset, languageTag = EngineConfig.defaultLanguageTag(preset.kind)))
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
        RadioButton(
            selected = active,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = AmberPalette.Primary,
                unselectedColor = AmberPalette.Outline,
            ),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = config.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = AmberPalette.Text,
            )
            Hint(
                // Name the first missing thing rather than a generic "not configured":
                // the user should not have to open the detail screen to find out.
                text = problems.firstOrNull() ?: config.modelId ?: "Systemmodell",
                color = if (problems.isEmpty()) AmberPalette.Muted else AmberPalette.Error,
            )
        }
        GhostButton("Öffnen", onOpen)
    }
}

/**
 * Size slider with a live preview. The setting is written when the finger lifts, not on
 * every step of the drag - each write goes through DataStore and resizes the overlay.
 */
@Composable
private fun BubbleSizeRow(sizeDp: Int, onChange: (Int) -> Unit) {
    var dragging by remember(sizeDp) { mutableStateOf(sizeDp.toFloat()) }
    val shown = dragging.roundToInt()

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Größe",
                style = MaterialTheme.typography.bodyLarge,
                color = AmberPalette.Text,
            )
            Hint("$shown dp")
            Slider(
                value = dragging,
                onValueChange = { dragging = it },
                onValueChangeFinished = { onChange(dragging.roundToInt()) },
                valueRange = MIN_BUBBLE_DP.toFloat()..MAX_BUBBLE_DP.toFloat(),
                steps = (MAX_BUBBLE_DP - MIN_BUBBLE_DP) / 4 - 1,
                colors = SliderDefaults.colors(
                    thumbColor = AmberPalette.Primary,
                    activeTrackColor = AmberPalette.Primary,
                    inactiveTrackColor = AmberPalette.Outline,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            )
        }
        Box(Modifier.size(MAX_BUBBLE_DP.dp), contentAlignment = Alignment.Center) {
            BubblePreview(shown.dp)
        }
    }
}

/** The bubble at the chosen size, drawn like the real overlay: a flat amber disc. */
@Composable
private fun BubblePreview(size: androidx.compose.ui.unit.Dp) {
    val animated by animateDpAsState(size, tween(150), label = "bubbleSize")
    Canvas(Modifier.size(animated)) {
        drawCircle(AmberPalette.Primary, radius = this.size.minDimension / 2 * 0.78f)
    }
}

private const val MIN_BUBBLE_DP = 44
private const val MAX_BUBBLE_DP = 96

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
                style = MaterialTheme.typography.bodyLarge,
                color = AmberPalette.Text,
            )
            Hint(description)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AmberPalette.Background,
                checkedTrackColor = AmberPalette.Primary,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = AmberPalette.Muted,
                uncheckedTrackColor = AmberPalette.Elevated,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun PresetDialog(
    existingIds: Set<String>,
    onDismiss: () -> Unit,
    onPick: (app.amethyst.core.engine.EnginePreset) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AmberPalette.Surface,
        shape = AmberShapes.Card,
        title = { Text("Engine hinzufügen", color = AmberPalette.Text) },
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
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = AmberPalette.Text,
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
                Text("Abbrechen", color = AmberPalette.Primary)
            }
        },
    )
}
