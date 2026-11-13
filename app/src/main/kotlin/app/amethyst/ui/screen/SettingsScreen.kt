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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.core.engine.ConfigIssue
import app.amethyst.core.engine.EngineConfigValidator
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.Servers
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.bubble.QuickMenuLayout
import app.amethyst.core.settings.BubbleSettings
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.SettingsGroupKind
import app.amethyst.core.settings.SettingsItem
import app.amethyst.core.settings.SettingsLayout
import app.amethyst.core.settings.SettingsSummaries
import app.amethyst.core.update.ReleaseChannel
import app.amethyst.core.update.UpdatePolicy
import app.amethyst.data.PhoneLocales
import app.amethyst.ui.AmberIcons
import app.amethyst.ui.AmberSwitch
import app.amethyst.ui.AppIcon
import app.amethyst.ui.AppSource
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
import app.amethyst.ui.engineName
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes
import app.amethyst.ui.theme.placeExplanation
import app.amethyst.ui.words
import kotlin.math.roundToInt

/** Where a settings row leads. */
enum class SettingsPage { RECOGNITION, CLEANUP, FALLBACK, RECORDING, INSERTING, APP_STYLES, BUBBLE, QUICK_MENU, COMMANDS, STATS, RELIABILITY, DIAGNOSTICS, UPDATES, LICENSES }

/**
 * Amber's settings in groups: the most used, the per-app styles, the advanced ones and Amber
 * itself. Which rows show is [SettingsLayout]'s table: a new install sees the simple ones, the
 * "Advanced settings" switch shows the rest. Each row is an icon, its name and its state in a
 * few words; the page behind it explains.
 */
@Composable
fun SettingsScreen(
    settings: AmethystSettings,
    setup: SetupState,
    diagnosticsCount: Int,
    version: String,
    /** Which build this is - only the GitHub build checks for updates itself; null until known. */
    releaseChannel: ReleaseChannel?,
    onAdvancedChange: (Boolean) -> Unit,
    onOpen: (SettingsPage) -> Unit,
    onReplayIntroduction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val engine = settings.activeEngine
    val trust = engine?.trustLevelForDisplay()
    val reliabilityOpen = listOf(setup.micServiceRunning, setup.notificationsGranted, setup.batteryUnrestricted).count { !it }
    val advanced = settings.ui.advanced
    val commandsWithoutBubble = settings.commandsEnabled && !settings.bubble.enabled
    val updateAvailable = releaseChannel?.let { UpdatePolicy.available(it, settings.updates, version) } != null

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
        SettingsItem.APP_STYLES -> NavRow(stringResource(R.string.settings_app_styles), words(SettingsSummaries.appStyles(settings.profiles.size)), icon = AmberIcons.Palette) {
            onOpen(SettingsPage.APP_STYLES)
        }
        SettingsItem.FALLBACK -> NavRow(stringResource(R.string.settings_fallback), words(SettingsSummaries.fallback(settings, trust)), icon = AmberIcons.CloudOff) {
            onOpen(SettingsPage.FALLBACK)
        }
        SettingsItem.RECORDING -> NavRow(stringResource(R.string.settings_recording), words(SettingsSummaries.recording(settings.capture)), icon = AmberIcons.GraphicEq) { onOpen(SettingsPage.RECORDING) }
        SettingsItem.INSERTING -> NavRow(stringResource(R.string.settings_inserting), words(SettingsSummaries.inserting(settings.insertion)), icon = AmberIcons.Keyboard) { onOpen(SettingsPage.INSERTING) }
        SettingsItem.RELIABILITY -> NavRow(
            stringResource(R.string.settings_reliability), words(SettingsSummaries.reliability(reliabilityOpen)), icon = AmberIcons.Battery,
            summaryColor = if (reliabilityOpen > 0) AmberPalette.Primary else AmberPalette.Muted,
        ) { onOpen(SettingsPage.RELIABILITY) }
        SettingsItem.STATISTICS -> NavRow(stringResource(R.string.settings_statistics), words(SettingsSummaries.onOff(settings.stats.keep)), icon = AmberIcons.BarChart) { onOpen(SettingsPage.STATS) }
        SettingsItem.DIAGNOSTICS -> NavRow(stringResource(R.string.settings_diagnostics), words(SettingsSummaries.diagnostics(diagnosticsCount)), icon = AmberIcons.Bug) { onOpen(SettingsPage.DIAGNOSTICS) }
        SettingsItem.UPDATES -> NavRow(
            stringResource(R.string.settings_updates), releaseChannel?.let { words(UpdatePolicy.summary(it, settings.updates, version)) }, icon = AmberIcons.Update,
            summaryColor = if (updateAvailable) AmberPalette.Primary else AmberPalette.Muted,
        ) { onOpen(SettingsPage.UPDATES) }
        SettingsItem.LICENSES -> NavRow(stringResource(R.string.settings_licenses), stringResource(R.string.settings_licenses_summary), icon = AmberIcons.Info) { onOpen(SettingsPage.LICENSES) }
        SettingsItem.INTRODUCTION -> NavRow(
            stringResource(R.string.settings_introduction), stringResource(R.string.settings_introduction_summary), icon = AmberIcons.Replay, onClick = onReplayIntroduction,
        )
        // Parts of the Cleanup page, an app's page and the Bubble page, never rows here.
        SettingsItem.CLEANUP_INSTRUCTION, SettingsItem.APP_RECOGNITION, SettingsItem.QUICK_MENU -> Unit
    }

    SubPage(modifier) {
        var switchShown = false
        SettingsLayout.groups(advanced).forEach { group ->
            // The switch sits right above what it shows, or where that would be.
            if (!switchShown && group.kind >= SettingsGroupKind.ADVANCED) {
                AdvancedSwitch(advanced, reliabilityOpen, onAdvancedChange)
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
        if (!switchShown) AdvancedSwitch(advanced, reliabilityOpen, onAdvancedChange)

        Text(
            stringResource(R.string.settings_version, version),
            style = MaterialTheme.typography.labelSmall,
            color = AmberPalette.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        )
    }
}

/**
 * The one switch between the simple and the full settings. Off, it says how much more there
 * is - or, first, what Reliability would flag, which is then out of sight.
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
                            measuredWaitMs = medianWaitMs[engine.id],
                            onSelect = if (cleanupOnly) ({ onOpenEngine(engine.id) }) else ({ onUpdateSettings { it.copy(activeEngineId = engine.id) } }),
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

/** When a recording ends, how raw the text stays, and recognizing while you speak. */
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
            title = stringResource(R.string.recording_text),
            learnMore = stringResource(R.string.recording_text_more),
        ) {
            ToggleRow(
                label = stringResource(R.string.recording_fillers),
                description = stringResource(R.string.recording_fillers_note),
                checked = settings.capture.stripFillers,
                onChange = { value ->
                    onUpdateSettings { it.copy(capture = it.capture.copy(stripFillers = value)) }
                },
            )
            ToggleRow(
                label = stringResource(R.string.recording_commas),
                description = stringResource(R.string.recording_commas_note),
                checked = settings.capture.germanCommas,
                onChange = { value ->
                    onUpdateSettings { it.copy(capture = it.capture.copy(germanCommas = value)) }
                },
            )
        }
        SectionCard(
            title = stringResource(R.string.recording_while_speaking),
            learnMore = stringResource(R.string.recording_while_speaking_more),
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
            subtitle = stringResource(R.string.inserting_subtitle),
            learnMore = stringResource(R.string.inserting_more),
        ) {
            ToggleRow(
                label = stringResource(R.string.inserting_spaces),
                description = stringResource(R.string.inserting_spaces_note),
                checked = settings.insertion.autoSpace,
                onChange = { value ->
                    onUpdateSettings { it.copy(insertion = it.insertion.copy(autoSpace = value)) }
                },
            )
            ToggleRow(
                label = stringResource(R.string.inserting_capitals),
                description = stringResource(R.string.inserting_capitals_note),
                checked = settings.insertion.autoCapitalize,
                onChange = { value ->
                    onUpdateSettings { it.copy(insertion = it.insertion.copy(autoCapitalize = value)) }
                },
            )
            ToggleRow(
                label = stringResource(R.string.inserting_punctuation),
                description = stringResource(R.string.inserting_punctuation_note),
                checked = settings.insertion.gluePunctuation,
                onChange = { value ->
                    onUpdateSettings {
                        it.copy(insertion = it.insertion.copy(gluePunctuation = value))
                    }
                },
            )
            ToggleRow(
                label = stringResource(R.string.inserting_clipboard),
                description = stringResource(R.string.inserting_clipboard_note),
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
    onOpenQuickMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf(false) }
    val bubble = settings.bubble
    val hideLabel = stringResource(R.string.hide)
    val resources = LocalContext.current.resources
    val hideInApps = { n: Int -> resources.getQuantityString(R.plurals.bubble_hide_in_n, n, n) }
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

        SettingsGroup(stringResource(R.string.bubble_hidden_group), note = stringResource(R.string.bubble_hidden_note)) {
            bubble.hiddenIn.forEach { pkg ->
                NavRow(
                    apps.label(pkg), summary = null,
                    leading = { AppIcon(pkg, apps) },
                    trailing = {
                        GhostButton(stringResource(R.string.bubble_show_again), {
                            onUpdateSettings { it.copy(bubble = it.bubble.copy(hiddenIn = it.bubble.hiddenIn - pkg)) }
                        })
                    },
                    onClick = null,
                )
                RowDivider()
            }
            NavRow(stringResource(R.string.add_apps), summary = if (bubble.hiddenIn.isEmpty()) stringResource(R.string.bubble_none_hidden) else null, icon = AmberIcons.Add, trailing = null) {
                picking = true
            }
        }
    }

    if (picking) {
        AppPickerDialog(
            title = stringResource(R.string.bubble_hide_in_title),
            existing = bubble.hiddenIn.toSet(),
            apps = apps,
            usage = usage,
            multiple = true,
            confirmLabel = { n -> if (n <= 1) hideLabel else hideInApps(n) },
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
            stringResource(R.string.bubble_preview_text),
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
    measuredWaitMs: Long?,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    cleanupOnly: Boolean = false,
) {
    RadioRow(
        label = engineName(config),
        // Name the first missing thing rather than a generic "not configured":
        // the user should not have to open the detail screen to find out. A server for the
        // cleanup only misses its speech model on purpose.
        note = (if (cleanupOnly) stringResource(R.string.recognition_cleanup_only) else null)
            ?: problems.firstOrNull()?.let { words(it) }
            ?: listOfNotNull(
                config.modelId ?: stringResource(R.string.engine_system_model),
                // Android's recognizer ends at a pause by itself, whatever the recording settings say.
                if (config.kind == EngineKind.ANDROID_ON_DEVICE) stringResource(R.string.engine_stops_at_pause) else null,
                measuredWaitMs?.let { stringResource(R.string.engine_typically, formatSeconds(it)) },
            ).joinToString(" · "),
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
