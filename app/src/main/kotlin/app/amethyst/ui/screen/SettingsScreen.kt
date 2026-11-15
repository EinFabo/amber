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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.core.engine.ConfigIssue
import app.amethyst.core.engine.EngineConfigValidator
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.Servers
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.SettingsAttention
import app.amethyst.core.text.CleanupTrouble
import app.amethyst.core.bubble.QuickMenuLayout
import app.amethyst.core.settings.BubbleSettings
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.RecognitionNote
import app.amethyst.core.settings.RecognitionRows
import app.amethyst.core.settings.SettingsGroupKind
import app.amethyst.core.settings.SettingsItem
import app.amethyst.core.settings.SettingsLayout
import app.amethyst.core.settings.SettingsSummaries
import app.amethyst.core.settings.ThemeMode
import app.amethyst.core.update.ReleaseChannel
import app.amethyst.core.update.UpdatePolicy
import app.amethyst.data.PhoneLocales
import app.amethyst.ui.AmberIcons
import app.amethyst.ui.AmberSwitch
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
import app.amethyst.ui.catalog
import app.amethyst.ui.recognitionRowName
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes
import app.amethyst.ui.theme.placeExplanation
import app.amethyst.ui.words
import kotlin.math.roundToInt

/**
 * Amber's settings in groups: the most used, the per-app styles, the advanced ones and Amber
 * itself - how it looks, and one row to what is about it rather than a setting ([AboutScreen]).
 * Which rows show is [SettingsLayout]'s table: a new install sees the simple ones, the
 * "Advanced settings" switch shows the rest. Each row is an icon, its name and its state in a
 * few words; the page behind it explains.
 */
@Composable
fun SettingsScreen(
    settings: AmethystSettings,
    setup: SetupState,
    version: String,
    /** Which build this is - only the GitHub build checks for updates itself; null until known. */
    releaseChannel: ReleaseChannel?,
    onAdvancedChange: (Boolean) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onOpen: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
    /** A cleanup that does not run until something is put right ([app.amethyst.core.text.CleanupTrouble]). */
    cleanupTrouble: CleanupTrouble.Open? = null,
) {
    val engine = settings.activeEngine
    val trust = engine?.trustLevelForDisplay()
    val reliability = SettingsAttention.reliabilityOpen(setup.micServiceRunning, setup.notificationsGranted, setup.batteryUnrestricted)
    val reliabilityOpen = reliability.size
    val attention = SettingsAttention.of(reliability, releaseChannel, settings.updates, version, cleanupTrouble)
    val advanced = settings.ui.advanced
    val commandsWithoutBubble = settings.commandsEnabled && !settings.bubble.enabled
    val updateAvailable = releaseChannel?.let { UpdatePolicy.available(it, settings.updates, version) } != null
    var choosingTheme by remember { mutableStateOf(false) }

    @Composable
    fun ItemRow(item: SettingsItem) = when (item) {
        SettingsItem.RECOGNITION -> NavRow(
            stringResource(R.string.settings_recognition), words(SettingsSummaries.recognition(engine, trust)), icon = AmberIcons.Mic,
            summaryColor = if (engine == null) AmberPalette.Error else AmberPalette.Muted,
        ) { onOpen(SettingsPage.RECOGNITION) }
        SettingsItem.CLEANUP -> NavRow(stringResource(R.string.settings_cleanup), words(SettingsSummaries.cleanup(settings)), icon = AmberIcons.AutoFix) { onOpen(SettingsPage.CLEANUP) }
        SettingsItem.BUBBLE -> NavRow(stringResource(R.string.settings_bubble), words(SettingsSummaries.onOff(settings.bubble.enabled)), icon = AmberIcons.Bubble) { onOpen(SettingsPage.BUBBLE) }
        SettingsItem.VOICE_COMMANDS -> NavRow(
            stringResource(R.string.settings_voice_commands), words(SettingsSummaries.commands(settings)), icon = AmberIcons.VoiceCommand,
            summaryColor = if (commandsWithoutBubble) AmberPalette.Primary else AmberPalette.Muted,
        ) { onOpen(SettingsPage.COMMANDS) }
        SettingsItem.APP_STYLES -> NavRow(stringResource(R.string.settings_apps), words(SettingsSummaries.apps(settings.profiles, settings.bubble.hiddenIn)), icon = AmberIcons.Apps) {
            onOpen(SettingsPage.APP_STYLES)
        }
        SettingsItem.FALLBACK -> NavRow(stringResource(R.string.settings_fallback), words(SettingsSummaries.fallback(settings, trust)), icon = AmberIcons.CloudOff) {
            onOpen(SettingsPage.FALLBACK)
        }
        SettingsItem.RECORDING -> NavRow(stringResource(R.string.settings_recording), words(SettingsSummaries.recording(settings.capture)), icon = AmberIcons.GraphicEq) { onOpen(SettingsPage.RECORDING) }
        SettingsItem.INSERTING -> NavRow(stringResource(R.string.settings_text), words(SettingsSummaries.text(settings.capture, settings.insertion)), icon = AmberIcons.TextFields) {
            onOpen(SettingsPage.INSERTING)
        }
        SettingsItem.RELIABILITY -> NavRow(
            stringResource(R.string.settings_reliability), words(SettingsSummaries.reliability(reliabilityOpen)), icon = AmberIcons.Battery,
            summaryColor = if (reliabilityOpen > 0) AmberPalette.Primary else AmberPalette.Muted,
        ) { onOpen(SettingsPage.RELIABILITY) }
        SettingsItem.APPEARANCE -> NavRow(
            stringResource(R.string.settings_appearance), words(SettingsSummaries.appearance(settings.ui)), icon = AmberIcons.Contrast, trailing = null,
        ) { choosingTheme = true }
        SettingsItem.ABOUT -> NavRow(
            stringResource(R.string.settings_about),
            // Nothing to say for the moment before the build is read.
            version.takeIf { it.isNotBlank() }?.let { words(SettingsSummaries.about(it, releaseChannel, settings.updates)) },
            icon = AmberIcons.Info,
            summaryColor = if (updateAvailable) AmberPalette.Primary else AmberPalette.Muted,
            // A debug build's version and a newer one beside it are more than a line.
            summaryMaxLines = 2,
        ) { onOpen(SettingsPage.ABOUT) }
        // Parts of the Cleanup page, an app's page and the Bubble page, never rows here.
        SettingsItem.CLEANUP_INSTRUCTION, SettingsItem.APP_RECOGNITION, SettingsItem.QUICK_MENU -> Unit
    }

    SubPage(modifier) {
        // What needs a look, before everything else and in both views; nothing open, no row.
        attention?.let { AttentionRow(it, onOpen) }
        var switchShown = false
        // The row on top says what is open, so the switch only says what it holds.
        val advancedSwitch: @Composable () -> Unit = { AdvancedSwitch(advanced, reliabilityOpen = 0, onAdvancedChange) }
        SettingsLayout.groups(advanced).forEach { group ->
            // The switch sits right above what it shows, or where that would be.
            if (!switchShown && group.kind >= SettingsGroupKind.ADVANCED) {
                advancedSwitch()
                switchShown = true
            }
            SettingsGroup(
                label = when (group.kind) {
                    SettingsGroupKind.MAIN -> null
                    SettingsGroupKind.PER_APP -> stringResource(R.string.settings_group_per_app)
                    SettingsGroupKind.ADVANCED -> stringResource(R.string.settings_group_advanced)
                    SettingsGroupKind.AMBER -> "Amber" // English: the app's name
                },
            ) {
                group.items.forEachIndexed { i, item ->
                    if (i > 0) RowDivider()
                    ItemRow(item)
                }
            }
        }
        if (!switchShown) advancedSwitch()
    }

    if (choosingTheme) ThemeDialog(settings.ui.theme, onThemeChange) { choosingTheme = false }
}

/** Light, dark, or as the phone is set. A choice shows at once, behind the dialog. */
@Composable
internal fun ThemeDialog(current: ThemeMode, onChange: (ThemeMode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AmberPalette.Surface,
        shape = AmberShapes.Card,
        title = { Text(stringResource(R.string.settings_appearance), color = AmberPalette.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ThemeMode.entries.forEach { mode ->
                    RadioRow(label = words(SettingsSummaries.theme(mode)), note = null, selected = mode == current, onSelect = { onChange(mode) })
                }
                Hint(stringResource(R.string.theme_bubble_note), modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.style_here_done), color = AmberPalette.Primary)
            }
        },
    )
}

/**
 * The one switch between the simple and the full settings. Off, it names the first rows it
 * holds - or, first, what Reliability would flag, which is then out of sight.
 */
@Composable
private fun AdvancedSwitch(advanced: Boolean, reliabilityOpen: Int, onChange: (Boolean) -> Unit) {
    val flagged = !advanced && reliabilityOpen > 0
    SettingsGroup(label = null) {
        NavRow(
            stringResource(R.string.settings_advanced),
            if (advanced) stringResource(R.string.settings_advanced_all) else words(SettingsSummaries.advanced(SettingsLayout.hiddenRows(), reliabilityOpen)),
            Modifier.toggleable(value = advanced, role = Role.Switch, onValueChange = onChange),
            icon = AmberIcons.Tune,
            summaryColor = if (flagged) AmberPalette.Primary else AmberPalette.Muted,
            // The titles it holds, which the switch beside them leaves little room for.
            summaryMaxLines = 2,
            trailing = { AmberSwitch(advanced) },
            onClick = null,
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

    // Once per settings change, not per engine row on every recomposition while scrolling.
    // (hasKeyFor() no longer decrypts - SecretStore keeps the stored aliases in memory.)
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
            TrustLevel.ON_DEVICE to stringResource(R.string.recognition_group_phone),
            TrustLevel.ENCLAVE_VERIFIED to stringResource(R.string.recognition_group_enclave),
            TrustLevel.CLOUD to stringResource(R.string.recognition_group_server),
        ).forEach { (level, title) ->
            val engines = groups[level].orEmpty()
            if (engines.isNotEmpty()) {
                SectionCard(title = title, subtitle = level.placeExplanation()) {
                    engines.forEach { engine ->
                        // A server for the cleanup only cannot be chosen here; it opens instead.
                        val cleanupOnly = Servers.cleanupOnly(settings, engine)
                        EngineRow(
                            config = engine,
                            active = engine.id == settings.activeEngineId,
                            problems = problemsByEngine[engine.id].orEmpty(),
                            note = RecognitionRows.note(engine, settings, medianWaitMs[engine.id]),
                            onSelect = if (cleanupOnly) ({ onOpenEngine(engine.id) }) else ({ onUpdateSettings { app.amethyst.core.onboarding.FirstRun.chosen(it, engine.id) } }),
                            onOpen = { onOpenEngine(engine.id) },
                            cleanupOnly = cleanupOnly,
                        )
                    }
                }
            }
        }
        SettingsGroup(label = null) {
            NavRow(stringResource(R.string.recognition_add), summary = null, icon = AmberIcons.Add, trailing = null) { showPresets = true }
        }
    }

    if (showPresets) {
        PresetDialog(
            existingIds = settings.engines.map { it.id }.toSet(),
            onDismiss = { showPresets = false },
            onPick = { preset ->
                showPresets = false
                val language = EngineConfig.defaultLanguageTag(preset.kind, PhoneLocales.tags())
                // A server is added as often as wanted, each with an id of its own.
                val added = if (preset == EnginePresets.SERVER) {
                    Servers.newServer(Servers.newId(taken = settings.engines.map { it.id }.toSet()), language)
                } else {
                    EngineConfig.fromPreset(preset, languageTag = language)
                }
                onUpdateSettings { it.withEngine(added) }
                onOpenEngine(added.id)
            },
        )
    }
}

/** When a recording ends, how long it may get, and recognizing while you speak. What becomes of the words is [TextScreen]'s. */
@Composable
fun RecordingScreen(
    settings: AmethystSettings,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    SubPage(modifier) {
        SectionCard(title = stringResource(R.string.recording_ending)) {
            ToggleRow(
                label = stringResource(R.string.recording_auto_stop),
                // Android's recognizer ends at a pause by itself; say so where it matters.
                description = stringResource(
                    if (settings.activeEngine?.kind == EngineKind.ANDROID_ON_DEVICE) R.string.recording_auto_stop_note_android else R.string.recording_auto_stop_note,
                ),
                checked = settings.capture.autoStopOnSilence,
                onChange = { value ->
                    onUpdateSettings { it.copy(capture = it.capture.copy(autoStopOnSilence = value)) }
                },
            )
            RecordingLimits(settings, onUpdateSettings)
        }
        SectionCard(
            title = stringResource(R.string.recording_while_speaking),
            learnMore = stringResource(R.string.recording_while_speaking_more),
        ) {
            ChunkingSettings(settings, onUpdateSettings)
        }
        SectionCard(title = stringResource(R.string.recording_microphone)) {
            ToggleRow(
                label = stringResource(R.string.recording_sounds),
                description = stringResource(R.string.recording_sounds_note),
                checked = settings.feedback.sounds,
                onChange = { value -> onUpdateSettings { it.copy(feedback = it.feedback.copy(sounds = value)) } },
            )
        }
    }
}

@Composable
fun BubbleScreen(
    settings: AmethystSettings,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
    onOpenQuickMenu: () -> Unit,
    /** To the Apps page, where the bubble is hidden app by app. */
    onOpenApps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bubble = settings.bubble
    SubPage(modifier) {
        MainSwitch(
            label = stringResource(R.string.bubble_use),
            summary = stringResource(R.string.bubble_use_summary),
            checked = bubble.enabled,
            onChange = { value -> onUpdateSettings { it.copy(bubble = it.bubble.copy(enabled = value)) } },
        )
        LearnMore(stringResource(R.string.bubble_use_more))

        SectionCard(title = stringResource(R.string.bubble_look)) {
            BubbleSizeRow(
                sizeDp = bubble.sizeDp,
                onChange = { value ->
                    onUpdateSettings { it.copy(bubble = it.bubble.copy(sizeDp = value)) }
                },
            )
            StepSlider(
                label = stringResource(R.string.bubble_opacity),
                valueText = stringResource(R.string.percent, (bubble.restingOpacity * 100).roundToInt()),
                value = (bubble.restingOpacity * 100).roundToInt(),
                range = 20..100,
                step = 10,
                onChange = { percent -> onUpdateSettings { it.copy(bubble = it.bubble.copy(restingOpacity = percent / 100f)) } },
            )
            BubbleOpacityPreview(bubble.restingOpacity)
        }

        SectionCard(title = stringResource(R.string.bubble_behavior)) {
            ToggleRow(
                label = stringResource(R.string.bubble_only_field),
                description = stringResource(R.string.bubble_only_field_note),
                checked = bubble.onlyWithTextField,
                onChange = { value ->
                    onUpdateSettings {
                        it.copy(bubble = it.bubble.copy(onlyWithTextField = value))
                    }
                },
            )
            if (settings.commandsEnabled && bubble.onlyWithTextField) {
                ToggleRow(
                    label = stringResource(R.string.bubble_everywhere_commands),
                    description = stringResource(R.string.bubble_everywhere_commands_note),
                    checked = bubble.everywhereForCommands,
                    onChange = { value ->
                        onUpdateSettings { it.copy(bubble = it.bubble.copy(everywhereForCommands = value)) }
                    },
                )
            }
            ToggleRow(
                label = stringResource(R.string.bubble_vibrate),
                description = stringResource(R.string.bubble_vibrate_note),
                checked = bubble.haptics,
                onChange = { value ->
                    onUpdateSettings { it.copy(bubble = it.bubble.copy(haptics = value)) }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.bubble_position), style = MaterialTheme.typography.bodyLarge, color = AmberPalette.Text)
                    Hint(stringResource(R.string.bubble_position_note))
                }
                GhostButton(stringResource(R.string.reset), {
                    onUpdateSettings {
                        it.copy(bubble = it.bubble.copy(side = BubbleSettings().side, edgeY = BubbleSettings.DEFAULT_EDGE_Y))
                    }
                })
            }
        }

        // Put together by hand: advanced, so who only dictates keeps the menu as it is - but a
        // menu of one's own stays in reach after the switch goes off.
        if (SettingsLayout.showsQuickMenu(bubble, settings.ui.advanced)) {
            SettingsGroup(label = null) {
                NavRow(
                    stringResource(R.string.settings_quick_menu),
                    quickMenuSummary(QuickMenuLayout.idle(bubble.quickMenuIdle)),
                    icon = AmberIcons.Apps,
                    onClick = onOpenQuickMenu,
                )
            }
        }

        // Where the bubble stays away is said app by app, on the Apps page.
        SettingsGroup(stringResource(R.string.bubble_hidden_group), note = stringResource(R.string.bubble_hidden_note)) {
            val hidden = bubble.hiddenIn.distinct().size
            NavRow(
                if (hidden == 0) stringResource(R.string.bubble_none_hidden) else pluralStringResource(R.plurals.summary_apps, hidden, hidden),
                stringResource(R.string.bubble_hidden_where),
                icon = AmberIcons.Apps,
                onClick = onOpenApps,
            )
        }
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
            stringResource(R.string.bubble_preview_text),
            style = MaterialTheme.typography.bodyMedium,
            color = AmberPalette.Text,
            modifier = Modifier.fillMaxWidth(),
        )
        Canvas(Modifier.padding(end = 8.dp).size(52.dp).alpha(animated)) {
            drawCircle(AmberPalette.Flame)
        }
    }
}

/** The three things that keep the microphone available while you are in other apps. */
@Composable
fun ReliabilityScreen(setup: SetupState, actions: SetupActions, modifier: Modifier = Modifier) {
    SubPage(modifier) {
        SectionCard(
            title = null,
            subtitle = stringResource(R.string.reliability_subtitle),
            learnMore = stringResource(R.string.reliability_more),
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
    problems: List<ConfigIssue>,
    note: RecognitionNote,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    cleanupOnly: Boolean = false,
) {
    RadioRow(
        label = recognitionRowName(config),
        // Name the first missing thing rather than a generic "not configured":
        // the user should not have to open the detail screen to find out. A server for the
        // cleanup only misses its speech model on purpose.
        note = (if (cleanupOnly) stringResource(R.string.recognition_cleanup_only) else null)
            ?: problems.firstOrNull()?.let { words(it) }
            ?: listOfNotNull(
                note.model?.let { catalog(it) },
                // Android's recognizer ends at a pause by itself, whatever the recording settings say.
                if (note.stopsAtPause) stringResource(R.string.engine_stops_at_pause) else null,
                note.typicalMs?.let { stringResource(R.string.engine_typically, formatSeconds(it)) },
            ).takeIf { it.isNotEmpty() }?.joinToString(" · "),
        selected = active,
        onSelect = onSelect,
        noteColor = if (problems.isEmpty() || cleanupOnly) AmberPalette.Muted else AmberPalette.Error,
    ) {
        GhostButton(stringResource(R.string.open), onOpen)
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
                text = stringResource(R.string.bubble_size),
                style = MaterialTheme.typography.bodyLarge,
                color = AmberPalette.Text,
            )
            Hint(stringResource(R.string.dp, shown))
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
        drawCircle(AmberPalette.Flame, radius = this.size.minDimension / 2 * 0.78f)
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
        title = { Text(stringResource(R.string.recognition_add), color = AmberPalette.Text) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                EnginePresets.offered
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
                                    text = catalog(preset.displayName),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = AmberPalette.Text,
                                )
                                TrustBadge(preset.trustLevel)
                            }
                            if (preset.note.isNotBlank()) Hint(catalog(preset.note))
                        }
                    }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = AmberPalette.Primary)
            }
        },
    )
}
