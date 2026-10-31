package app.amethyst.ui.screen

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.amethyst.core.engine.EngineConfigValidator
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.BubbleSettings
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.SettingsSummaries
import app.amethyst.ui.AmberIcons
import app.amethyst.ui.AppIcon
import app.amethyst.ui.AppSource
import app.amethyst.ui.ExpandChevron
import app.amethyst.ui.foldState
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.LearnMore
import app.amethyst.ui.MainSwitch
import app.amethyst.ui.NavRow
import app.amethyst.ui.PageGutter
import app.amethyst.ui.RadioRow
import app.amethyst.ui.RowDivider
import app.amethyst.ui.SectionCard
import app.amethyst.ui.SettingsGroup
import app.amethyst.ui.SetupActions
import app.amethyst.ui.SetupState
import app.amethyst.ui.ToggleRow
import app.amethyst.ui.TrustBadge
import app.amethyst.ui.Unfold
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes
import app.amethyst.ui.theme.placeExplanation
import kotlin.math.roundToInt

/** Where a settings row leads. */
enum class SettingsPage { RECOGNITION, CLEANUP, FALLBACK, RECORDING, INSERTING, APP_STYLES, BUBBLE, COMMANDS, STATS, RELIABILITY, DIAGNOSTICS }

/** How many rows the folded "Advanced" group holds. */
private const val ADVANCED_ROWS = 4

/**
 * Amber's settings in four groups: the four most used, the per-app styles, an "Advanced" group
 * folded to one row, and Amber itself. Each row is an icon, its name and its state in a few
 * words; the page behind it explains. [advancedOpen] lives with the caller, so coming back from
 * a page finds the group as it was left.
 */
@Composable
fun SettingsScreen(
    settings: AmethystSettings,
    setup: SetupState,
    diagnosticsCount: Int,
    version: String,
    advancedOpen: Boolean,
    onToggleAdvanced: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
    onReplayIntroduction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val engine = settings.activeEngine
    val trust = engine?.trustLevelForDisplay()
    val reliabilityOpen = listOf(setup.micServiceRunning, setup.notificationsGranted, setup.batteryUnrestricted).count { !it }
    // Folded, "Advanced" passes on what Reliability needs; open, the Reliability row says it itself.
    val advancedFlags = if (advancedOpen) 0 else reliabilityOpen
    val commandsWithoutBubble = settings.commands.enabled && !settings.bubble.enabled

    SubPage(modifier) {
        SettingsGroup(label = null) {
            NavRow(
                "Recognition", SettingsSummaries.recognition(engine, trust), icon = AmberIcons.Mic,
                summaryColor = if (engine == null) AmberPalette.Error else AmberPalette.Muted,
            ) { onOpen(SettingsPage.RECOGNITION) }
            RowDivider()
            NavRow("Cleanup", SettingsSummaries.cleanup(settings), icon = AmberIcons.AutoFix) { onOpen(SettingsPage.CLEANUP) }
            RowDivider()
            NavRow("Bubble", SettingsSummaries.onOff(settings.bubble.enabled), icon = AmberIcons.Bubble) { onOpen(SettingsPage.BUBBLE) }
            RowDivider()
            NavRow(
                "Voice commands", SettingsSummaries.commands(settings), icon = AmberIcons.VoiceCommand,
                summaryColor = if (commandsWithoutBubble) AmberPalette.Primary else AmberPalette.Muted,
            ) { onOpen(SettingsPage.COMMANDS) }
        }

        SettingsGroup("Per app") {
            NavRow("App styles", SettingsSummaries.appStyles(settings.profiles.size), icon = AmberIcons.Palette) { onOpen(SettingsPage.APP_STYLES) }
        }

        SettingsGroup(label = null) {
            NavRow(
                "Advanced", SettingsSummaries.advanced(ADVANCED_ROWS, advancedFlags), Modifier.foldState(advancedOpen), icon = AmberIcons.Tune,
                summaryColor = if (advancedFlags > 0) AmberPalette.Primary else AmberPalette.Muted,
                trailing = { ExpandChevron(advancedOpen) },
                onClick = onToggleAdvanced,
            )
            Unfold(advancedOpen) {
                Column {
                    RowDivider()
                    NavRow("When the server fails", SettingsSummaries.fallback(settings, trust), icon = AmberIcons.CloudOff) {
                        onOpen(SettingsPage.FALLBACK)
                    }
                    RowDivider()
                    NavRow("Recording", SettingsSummaries.recording(settings.capture), icon = AmberIcons.GraphicEq) { onOpen(SettingsPage.RECORDING) }
                    RowDivider()
                    NavRow("Inserting", SettingsSummaries.inserting(settings.insertion), icon = AmberIcons.Keyboard) { onOpen(SettingsPage.INSERTING) }
                    RowDivider()
                    NavRow(
                        "Reliability", SettingsSummaries.reliability(reliabilityOpen), icon = AmberIcons.Battery,
                        summaryColor = if (reliabilityOpen > 0) AmberPalette.Primary else AmberPalette.Muted,
                    ) { onOpen(SettingsPage.RELIABILITY) }
                }
            }
        }

        SettingsGroup("Amber") {
            NavRow("Statistics", SettingsSummaries.onOff(settings.stats.keep), icon = AmberIcons.BarChart) { onOpen(SettingsPage.STATS) }
            RowDivider()
            NavRow("Diagnostics", SettingsSummaries.diagnostics(diagnosticsCount), icon = AmberIcons.Bug) { onOpen(SettingsPage.DIAGNOSTICS) }
            RowDivider()
            NavRow("Show the introduction again", "The setup steps, with your choices", icon = AmberIcons.Replay, onClick = onReplayIntroduction)
        }

        Text(
            "Amber $version",
            style = MaterialTheme.typography.labelSmall,
            color = AmberPalette.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        )
    }
}

/**
 * Who turns the voice into text: the engines, grouped by where they send your voice, each with
 * the wait measured here. The row picks the active one; its button opens the details. Cleanup
 * and the fallback have their own pages.
 */
@Composable
fun RecognitionScreen(
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

    SubPage(modifier) {
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
        SettingsGroup(label = null) {
            NavRow("Add engine", summary = null, icon = AmberIcons.Add, trailing = null) { showPresets = true }
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
        SectionCard(
            title = "Text",
            learnMore = "Both work by rules, without a language model, so nothing is made up. The commas go before " +
                "\"dass\", \"weil\", \"wenn\" or \"um ... zu\", where recognition often leaves them out - " +
                "they never change a word.",
        ) {
            ToggleRow(
                label = "Remove filler words",
                description = "\"ähm\", \"äh\", \"um\" and the like",
                checked = settings.capture.stripFillers,
                onChange = { value ->
                    onUpdateSettings { it.copy(capture = it.capture.copy(stripFillers = value)) }
                },
            )
            ToggleRow(
                label = "Add missing commas",
                description = "German only",
                checked = settings.capture.germanCommas,
                onChange = { value ->
                    onUpdateSettings { it.copy(capture = it.capture.copy(germanCommas = value)) }
                },
            )
        }
        SectionCard(
            title = "Recognize while speaking",
            learnMore = "Longer dictations are cut at pauses and recognized while you keep talking, " +
                "so stopping only waits for the last part.",
        ) {
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
        SectionCard(
            title = null,
            subtitle = "How the text arrives at the cursor.",
            learnMore = "Off, text is only typed straight into the field and the clipboard stays untouched - a " +
                "dictation that cannot be typed is reported. On, it is pasted instead, or copied as a last " +
                "resort, and then shows up in the clipboard history.",
        ) {
            ToggleRow(
                label = "Add spaces",
                description = "Apart from the word before",
                checked = settings.insertion.autoSpace,
                onChange = { value ->
                    onUpdateSettings { it.copy(insertion = it.insertion.copy(autoSpace = value)) }
                },
            )
            ToggleRow(
                label = "Capitalize sentence starts",
                description = "At the start of a field and after . ! ?",
                checked = settings.insertion.autoCapitalize,
                onChange = { value ->
                    onUpdateSettings { it.copy(insertion = it.insertion.copy(autoCapitalize = value)) }
                },
            )
            ToggleRow(
                label = "Attach punctuation",
                description = "\"Hello , world\" becomes \"Hello, world\"",
                checked = settings.insertion.gluePunctuation,
                onChange = { value ->
                    onUpdateSettings {
                        it.copy(insertion = it.insertion.copy(gluePunctuation = value))
                    }
                },
            )
            ToggleRow(
                label = "Clipboard as a fallback",
                description = "When typing into the field fails",
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
    apps: AppSource,
    usage: Map<String, Int>,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf(false) }
    val bubble = settings.bubble
    SubPage(modifier) {
        MainSwitch(
            label = "Use the bubble",
            summary = "Tap to dictate, hold to talk.",
            checked = bubble.enabled,
            onChange = { value -> onUpdateSettings { it.copy(bubble = it.bubble.copy(enabled = value)) } },
        )
        LearnMore(
            "Off hides the bubble without turning off the accessibility service - dictating in the test " +
                "field keeps working. While you dictate, the bubble is always fully visible.",
        )

        SectionCard(title = "Look") {
            BubbleSizeRow(
                sizeDp = bubble.sizeDp,
                onChange = { value ->
                    onUpdateSettings { it.copy(bubble = it.bubble.copy(sizeDp = value)) }
                },
            )
            StepSlider(
                label = "Opacity at rest",
                valueText = "${(bubble.restingOpacity * 100).roundToInt()} %",
                value = (bubble.restingOpacity * 100).roundToInt(),
                range = 20..100,
                step = 10,
                onChange = { percent -> onUpdateSettings { it.copy(bubble = it.bubble.copy(restingOpacity = percent / 100f)) } },
            )
            BubbleOpacityPreview(bubble.restingOpacity)
        }

        SectionCard(title = "Behavior") {
            ToggleRow(
                label = "Only with a text field",
                description = if (settings.commands.enabled) "Voice commands show it in every app" else "Appears when you tap into a field",
                checked = bubble.onlyWithTextField,
                onChange = { value ->
                    onUpdateSettings {
                        it.copy(bubble = it.bubble.copy(onlyWithTextField = value))
                    }
                },
            )
            ToggleRow(
                label = "Vibrate",
                description = "On start, success and failure",
                checked = bubble.haptics,
                onChange = { value ->
                    onUpdateSettings { it.copy(bubble = it.bubble.copy(haptics = value)) }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Position", style = MaterialTheme.typography.bodyLarge, color = AmberPalette.Text)
                    Hint("Stays where you drag it")
                }
                GhostButton("Reset", {
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

        SettingsGroup("Hidden in", note = "Games, or apps with their own voice input.") {
            bubble.hiddenIn.forEach { pkg ->
                NavRow(
                    apps.label(pkg), summary = null,
                    leading = { AppIcon(pkg, apps) },
                    trailing = {
                        GhostButton("Show again", {
                            onUpdateSettings { it.copy(bubble = it.bubble.copy(hiddenIn = it.bubble.hiddenIn - pkg)) }
                        })
                    },
                    onClick = null,
                )
                RowDivider()
            }
            NavRow("Add apps", summary = if (bubble.hiddenIn.isEmpty()) "None hidden yet" else null, icon = AmberIcons.Add, trailing = null) {
                picking = true
            }
        }
    }

    if (picking) {
        AppPickerDialog(
            title = "Hide the bubble in",
            existing = bubble.hiddenIn.toSet(),
            apps = apps,
            usage = usage,
            multiple = true,
            confirmLabel = { n -> if (n <= 1) "Hide" else "Hide in $n apps" },
            onDismiss = { picking = false },
            onPick = { chosen ->
                picking = false
                onUpdateSettings { it.copy(bubble = it.bubble.copy(hiddenIn = (it.bubble.hiddenIn + chosen.map { app -> app.packageName }).distinct())) }
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
            .background(AmberPalette.Background, AmberShapes.Inner)
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
        SectionCard(
            title = null,
            subtitle = "Optional. Keeps Android from cutting off the microphone.",
            learnMore = "Android may cut off the microphone of an app in the background. The microphone service " +
                "keeps it ready while you are in other apps, its silent notification says so, and unrestricted " +
                "battery stops some manufacturers from killing the service.",
        ) {
            ReliabilityRows(setup, actions)
        }
    }
}

/** A scrolling page of groups, the frame every settings page shares. */
@Composable
internal fun SubPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PageGutter)
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
    RadioRow(
        label = config.displayName,
        // Name the first missing thing rather than a generic "not configured":
        // the user should not have to open the detail screen to find out.
        note = problems.firstOrNull()
            ?: listOfNotNull(config.modelId ?: "System model", measuredWaitMs?.let { "typically ${formatSeconds(it)} here" }).joinToString(" · "),
        selected = active,
        onSelect = onSelect,
        noteColor = if (problems.isEmpty()) AmberPalette.Muted else AmberPalette.Error,
    ) {
        GhostButton("Open", onOpen)
    }
}

/**
 * Size slider with a live preview. The setting is written when the finger lifts, not on
 * every step of the drag - each write goes through DataStore and resizes the overlay.
 */
@Composable
internal fun BubbleSizeRow(sizeDp: Int, onChange: (Int) -> Unit) {
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

internal const val MIN_BUBBLE_DP = 44
internal const val MAX_BUBBLE_DP = 96

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
