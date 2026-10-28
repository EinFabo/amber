package app.amethyst.ui.screen

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import app.amethyst.core.settings.BubbleSettings
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.ui.Hairline
import app.amethyst.ui.NavRow
import app.amethyst.ui.SetupActions
import app.amethyst.ui.SetupState
import app.amethyst.ui.theme.badgeLabel
import app.amethyst.ui.theme.placeExplanation
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.SectionCard
import app.amethyst.ui.TrustBadge
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes

/** Where a settings row leads. */
enum class SettingsPage { ENGINES, RECORDING, INSERTING, APP_STYLES, BUBBLE, STATS, RELIABILITY, DIAGNOSTICS }

/**
 * Hearth's settings: a short list, each row showing its current value, each leading to one
 * sub-screen. Nothing collapses, nothing is "advanced" - the rarer options sit lower on the
 * page they belong to.
 */
@Composable
fun SettingsScreen(
    settings: AmethystSettings,
    setup: SetupState,
    diagnosticsCount: Int,
    version: String,
    onOpen: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val capture = settings.capture
    val insertion = settings.insertion
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        Hairline()
        NavRow("Engine", settings.activeEngine?.let { "${it.displayName} · ${it.trustLevelForDisplay().badgeLabel()}" } ?: "None selected") { onOpen(SettingsPage.ENGINES) }
        NavRow(
            "Recording",
            if (capture.autoStopOnSilence) "Stops after %.1f s of silence".format(java.util.Locale.US, capture.silenceTimeoutMs / 1000.0)
            else "Runs until you tap",
        ) { onOpen(SettingsPage.RECORDING) }
        NavRow(
            "Inserting",
            listOfNotNull(
                "spaces".takeIf { insertion.autoSpace },
                "capitals".takeIf { insertion.autoCapitalize },
                "clipboard fallback".takeIf { insertion.allowClipboard },
            ).joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { "As recognized" },
        ) { onOpen(SettingsPage.INSERTING) }
        NavRow("App styles", "${settings.profiles.size} ${if (settings.profiles.size == 1) "app" else "apps"} with their own style") { onOpen(SettingsPage.APP_STYLES) }
        NavRow(
            "Bubble",
            if (!settings.bubble.enabled) "Off"
            else listOfNotNull(
                "On",
                "${settings.bubble.sizeDp} dp",
                "${(settings.bubble.restingOpacity * 100).roundToInt()} % at rest".takeIf { settings.bubble.restingOpacity < 1f },
                "hidden in ${settings.bubble.hiddenIn.size} ${if (settings.bubble.hiddenIn.size == 1) "app" else "apps"}".takeIf { settings.bubble.hiddenIn.isNotEmpty() },
            ).joinToString(" · "),
        ) { onOpen(SettingsPage.BUBBLE) }
        Hairline()
        NavRow("Statistics", if (settings.stats.keep) "Kept on this phone" else "Off") { onOpen(SettingsPage.STATS) }
        val open = listOf(setup.micServiceRunning, setup.notificationsGranted, setup.batteryUnrestricted).count { !it }
        NavRow(
            "Reliability",
            if (open == 0) "All set" else "$open ${if (open == 1) "thing" else "things"} open",
            valueColor = if (open == 0) AmberPalette.Muted else AmberPalette.Primary,
        ) { onOpen(SettingsPage.RELIABILITY) }
        NavRow("Diagnostics", if (diagnosticsCount == 0) "Nothing dictated yet" else "The last $diagnosticsCount dictations") { onOpen(SettingsPage.DIAGNOSTICS) }
        Hairline()
        Spacer(Modifier.height(16.dp))
        Hint("Amber $version", AmberPalette.Muted)
    }
}

/**
 * The engines, grouped by where they send your voice, each with the wait measured here. The
 * radio picks the active one; the row's button opens its details.
 */
@Composable
fun EnginesScreen(
    settings: AmethystSettings,
    hasKeyFor: (String?) -> Boolean,
    medianWaitMs: Map<String, Long>,
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
        val groups = settings.engines.groupBy { it.trustLevelForDisplay() }
        listOf(
            TrustLevel.ON_DEVICE to "On this phone",
            TrustLevel.ENCLAVE_VERIFIED to "Verified enclave",
            TrustLevel.CLOUD to "Server",
        ).forEach { (level, title) ->
            val engines = groups[level].orEmpty()
            if (engines.isNotEmpty()) {
                SectionCard(title = title, subtitle = level.placeExplanation()) {
                    engines.forEach { engine ->
                        EngineRow(
                            config = engine,
                            active = engine.id == settings.activeEngineId,
                            problems = problemsByEngine[engine.id].orEmpty(),
                            measuredWaitMs = medianWaitMs[engine.id],
                            onSelect = { onUpdateSettings { it.copy(activeEngineId = engine.id) } },
                            onOpen = { onOpenEngine(engine.id) },
                        )
                    }
                }
            }
        }
        Hairline()
        GhostButton("Add engine", { showPresets = true })

        SectionCard(title = "Without a connection") {
            ToggleRow(
                label = "Fall back to the phone",
                description = "If a server cannot be reached, a downloaded local model (Parakeet, " +
                    "then Whisper) recognizes the recording instead of it being lost.",
                checked = settings.capture.localFallback,
                onChange = { on -> onUpdateSettings { it.copy(capture = it.capture.copy(localFallback = on)) } },
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

/** When a recording ends, how raw the text stays, and recognizing while you speak. */
@Composable
fun RecordingScreen(
    settings: AmethystSettings,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    SubPage(modifier) {
        SectionCard(title = "Ending") {
            ToggleRow(
                label = "Stop automatically on silence",
                description = "Off: it runs until the next tap.",
                checked = settings.capture.autoStopOnSilence,
                onChange = { value ->
                    onUpdateSettings { it.copy(capture = it.capture.copy(autoStopOnSilence = value)) }
                },
            )
            RecordingLimits(settings, onUpdateSettings)
        }
        SectionCard(title = "Text") {
            ToggleRow(
                label = "Remove filler words",
                description = "Drops \"ähm\", \"äh\", \"um\" and the like. " +
                    "Purely rule-based, no LLM, so no risk of hallucination.",
                checked = settings.capture.stripFillers,
                onChange = { value ->
                    onUpdateSettings { it.copy(capture = it.capture.copy(stripFillers = value)) }
                },
            )
        }
        SectionCard(title = "Recognize while speaking") {
            ChunkingSettings(settings, onUpdateSettings)
        }
    }
}

/** How the text arrives at the cursor. */
@Composable
fun InsertingScreen(
    settings: AmethystSettings,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    SubPage(modifier) {
        SectionCard(title = "Inserting", subtitle = "How the text arrives at the cursor.") {
            ToggleRow(
                label = "Add spaces",
                description = "Separates from the previous word automatically.",
                checked = settings.insertion.autoSpace,
                onChange = { value ->
                    onUpdateSettings { it.copy(insertion = it.insertion.copy(autoSpace = value)) }
                },
            )
            ToggleRow(
                label = "Capitalize sentence starts",
                description = "Only at the start of the field and after . ! ?",
                checked = settings.insertion.autoCapitalize,
                onChange = { value ->
                    onUpdateSettings { it.copy(insertion = it.insertion.copy(autoCapitalize = value)) }
                },
            )
            ToggleRow(
                label = "Attach punctuation",
                description = "Turns \"Hello , world\" into \"Hello, world\".",
                checked = settings.insertion.gluePunctuation,
                onChange = { value ->
                    onUpdateSettings {
                        it.copy(insertion = it.insertion.copy(gluePunctuation = value))
                    }
                },
            )
            ToggleRow(
                label = "Clipboard as a fallback",
                description = "Off: text is only typed straight into the field, the clipboard " +
                    "stays untouched. On: if that fails, it is pasted or copied.",
                checked = settings.insertion.allowClipboard,
                onChange = { value ->
                    onUpdateSettings { it.copy(insertion = it.insertion.copy(allowClipboard = value)) }
                },
            )
        }
    }
}

@Composable
fun BubbleScreen(
    settings: AmethystSettings,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
    loadApps: suspend () -> List<InstalledApp>,
    appName: (String) -> String,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf(false) }
    val bubble = settings.bubble
    SubPage(modifier) {
        SectionCard(title = "Bubble", subtitle = "Tap to dictate, hold to talk, drag to move.") {
            ToggleRow(
                label = "Show bubble",
                description = "Off hides the bubble without turning off the accessibility " +
                    "service - dictating in the test field keeps working.",
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
                label = "Only with a text field",
                description = "The bubble appears as soon as you tap into an input field, " +
                    "and disappears again afterwards.",
                checked = settings.bubble.onlyWithTextField,
                onChange = { value ->
                    onUpdateSettings {
                        it.copy(bubble = it.bubble.copy(onlyWithTextField = value))
                    }
                },
            )
            ToggleRow(
                label = "Vibrate",
                description = "Short feedback on start, success and failure.",
                checked = settings.bubble.haptics,
                onChange = { value ->
                    onUpdateSettings { it.copy(bubble = it.bubble.copy(haptics = value)) }
                },
            )
        }

        SectionCard(title = "Look") {
            StepSlider(
                label = "Opacity at rest",
                valueText = "${(bubble.restingOpacity * 100).roundToInt()} %",
                value = (bubble.restingOpacity * 100).roundToInt(),
                range = 20..100,
                step = 10,
                onChange = { percent -> onUpdateSettings { it.copy(bubble = it.bubble.copy(restingOpacity = percent / 100f)) } },
            )
            Hint("While you dictate, the bubble is always fully visible.")
            BubbleOpacityPreview(bubble.restingOpacity)
        }

        SectionCard(title = "Hidden in", subtitle = "Apps the bubble stays out of - games, or apps with their own voice input.") {
            if (bubble.hiddenIn.isEmpty()) Hint("Shown in every app.")
            bubble.hiddenIn.forEach { pkg ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(appName(pkg), style = MaterialTheme.typography.bodyLarge, color = AmberPalette.Text, modifier = Modifier.weight(1f))
                    GhostButton("Show again", {
                        onUpdateSettings { it.copy(bubble = it.bubble.copy(hiddenIn = it.bubble.hiddenIn - pkg)) }
                    })
                }
            }
            GhostButton("Add app", { picking = true })
        }

        SectionCard(title = "Position") {
            Hint("Drag the bubble anywhere; it stays where you drop it.")
            GhostButton("Put it back in the corner", {
                onUpdateSettings {
                    it.copy(
                        bubble = it.bubble.copy(
                            horizontalBias = BubbleSettings.DEFAULT_HORIZONTAL_BIAS,
                            verticalBias = BubbleSettings.DEFAULT_VERTICAL_BIAS,
                        ),
                    )
                }
            })
        }
    }

    if (picking) {
        AppPickerDialog(
            existing = bubble.hiddenIn.toSet(),
            loadApps = loadApps,
            onDismiss = { picking = false },
            onPick = { app ->
                picking = false
                onUpdateSettings { it.copy(bubble = it.bubble.copy(hiddenIn = it.bubble.hiddenIn + app.packageName)) }
            },
        )
    }
}

/** The resting bubble over a strip of text, so the opacity can be judged before trying it. */
@Composable
private fun BubbleOpacityPreview(opacity: Float) {
    val animated by animateFloatAsState(opacity, tween(150), label = "opacity")
    Box(
        Modifier
            .fillMaxWidth()
            .height(84.dp)
            .background(AmberPalette.Surface, AmberShapes.Inner)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        // The disc sits on the text, as the bubble does over an app.
        Text(
            "Hi, I'll be there in ten minutes - bring the key, I left mine at home again.",
            style = MaterialTheme.typography.bodyMedium,
            color = AmberPalette.Text,
            modifier = Modifier.fillMaxWidth(),
        )
        Canvas(Modifier.padding(end = 8.dp).size(52.dp).alpha(animated)) {
            drawCircle(AmberPalette.Primary)
        }
    }
}

/** The three things that keep the microphone available while you are in other apps. */
@Composable
fun ReliabilityScreen(setup: SetupState, actions: SetupActions, modifier: Modifier = Modifier) {
    SubPage(modifier) {
        SectionCard(title = "Reliability", subtitle = "Optional. Keeps Android from cutting off the microphone.") {
            ReliabilityRows(setup, actions)
        }
    }
}

/** A scrolling page of sections, the frame every sub-screen shares. */
@Composable
internal fun SubPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        content = content,
    )
}

@Composable
private fun EngineRow(
    config: EngineConfig,
    active: Boolean,
    problems: List<String>,
    measuredWaitMs: Long?,
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
                text = problems.firstOrNull()
                    ?: listOfNotNull(config.modelId ?: "System model", measuredWaitMs?.let { "typically ${formatSeconds(it)} here" }).joinToString(" · "),
                color = if (problems.isEmpty()) AmberPalette.Muted else AmberPalette.Error,
            )
        }
        GhostButton("Open", onOpen)
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
                text = "Size",
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
internal fun ToggleRow(
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
        title = { Text("Add engine", color = AmberPalette.Text) },
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
                Text("Cancel", color = AmberPalette.Primary)
            }
        },
    )
}
