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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.Texts
import app.amethyst.core.bubble.QuickMenuLayout
import app.amethyst.core.engine.ConfigIssue
import app.amethyst.core.engine.EngineAdvice
import app.amethyst.core.engine.EngineConfigValidator
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.Servers
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.BubbleSettings
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.RecognitionNote
import app.amethyst.core.settings.RecognitionRows
import app.amethyst.core.settings.SettingsAttention
import app.amethyst.core.settings.AppRows
import app.amethyst.core.settings.SettingsGroupKind
import app.amethyst.core.settings.SettingsItem
import app.amethyst.core.settings.SettingsLayout
import app.amethyst.core.settings.SettingsSummaries
import app.amethyst.core.settings.ThemeMode
import app.amethyst.core.text.CleanupTrouble
import app.amethyst.core.ui.SettingsPage
import app.amethyst.core.update.ReleaseChannel
import app.amethyst.core.update.UpdatePolicy
import app.amethyst.data.PhoneLocales
import app.amethyst.ui.AmberIcons
import app.amethyst.ui.ChevronRight
import app.amethyst.ui.AppIcon
import app.amethyst.ui.AppSource
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.LocalUndo
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
import app.amethyst.ui.presetLine
import app.amethyst.ui.recognitionRowName
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes
import app.amethyst.ui.theme.placeExplanation
import app.amethyst.ui.words
import kotlin.math.roundToInt

/**
 * Amber's settings in groups: the most used, the per-app styles, and Amber itself - what keeps
 * it running, how it looks, and one row to what is about it rather than a setting
 * ([AboutScreen]). Every row is always there ([SettingsLayout]); each is an icon, its name and
 * its state in a few words, and the page behind it explains.
 */
@Composable
fun SettingsScreen(
    settings: AmethystSettings,
    setup: SetupState,
    version: String,
    /** Which build this is - only the GitHub build checks for updates itself; null until known. */
    releaseChannel: ReleaseChannel?,
    onThemeChange: (ThemeMode) -> Unit,
    onOpen: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
    /** A cleanup that does not run until something is put right ([app.amethyst.core.text.CleanupTrouble]). */
    cleanupTrouble: CleanupTrouble.Open? = null,
) {
    val engine = settings.activeEngine
    val trust = engine?.trustLevelForDisplay()
    val reliability = SettingsAttention.reliabilityOpen(setup.notificationsGranted, setup.batteryUnrestricted)
    val reliabilityOpen = reliability.size
    // Only while a cleanup still uses the one that failed.
    val trouble = cleanupTrouble?.takeIf { CleanupTrouble.stillApplies(it, settings) }
    // The Reliability row is at the bottom, out of sight: what is open there is said on top
    // instead, and the row itself then says nothing more (SettingsSummaries.reliability).
    val attention = SettingsAttention.of(reliability, releaseChannel, settings.updates, version, trouble)
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
        SettingsItem.OWN_WORDS -> NavRow(stringResource(R.string.own_words), words(SettingsSummaries.ownWords(settings)), icon = AmberIcons.Keyboard) {
            onOpen(SettingsPage.OWN_WORDS)
        }
        // Where it shows and the apps it stays out of: a line and a half in German.
        SettingsItem.BUBBLE -> NavRow(
            stringResource(R.string.settings_bubble), words(SettingsSummaries.bubble(settings.bubble)), icon = AmberIcons.Bubble, summaryMaxLines = 2,
        ) { onOpen(SettingsPage.BUBBLE) }
        SettingsItem.PRIVACY -> NavRow(
            stringResource(R.string.settings_privacy), words(SettingsSummaries.privacy(settings)), icon = AmberIcons.Lock,
        ) { onOpen(SettingsPage.PRIVACY) }
        SettingsItem.VOICE_COMMANDS -> NavRow(
            stringResource(R.string.settings_voice_commands), words(SettingsSummaries.commands(settings)), icon = AmberIcons.VoiceCommand,
            summaryColor = if (commandsWithoutBubble) AmberPalette.Primary else AmberPalette.Muted,
        ) { onOpen(SettingsPage.COMMANDS) }
        SettingsItem.APP_STYLES -> NavRow(stringResource(R.string.settings_apps), words(SettingsSummaries.apps(settings.profiles, settings.bubble.hiddenIn)), icon = AmberIcons.Apps) {
            onOpen(SettingsPage.APP_STYLES)
        }
        SettingsItem.RECORDING -> NavRow(stringResource(R.string.settings_recording), words(SettingsSummaries.recording(settings.capture)), icon = AmberIcons.GraphicEq) { onOpen(SettingsPage.RECORDING) }
        SettingsItem.INSERTING -> NavRow(stringResource(R.string.settings_text), words(SettingsSummaries.text(settings.capture, settings.insertion)), icon = AmberIcons.TextFields) {
            onOpen(SettingsPage.INSERTING)
        }
        SettingsItem.RELIABILITY -> NavRow(
            stringResource(R.string.settings_reliability), SettingsSummaries.reliability(reliabilityOpen, attention)?.let { words(it) }, icon = AmberIcons.Battery,
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
    }

    SubPage(modifier) {
        // What needs a look, before everything else and in both views; nothing open, no row.
        attention?.let { AttentionRow(it, onOpen) }
        SettingsLayout.groups().forEach { group ->
            SettingsGroup(
                // No heading over a single row: "Per app" over "Apps" said the same twice.
                label = if (!group.labeled) null else when (group.kind) {
                    SettingsGroupKind.MAIN -> null
                    SettingsGroupKind.PER_APP -> stringResource(R.string.settings_group_per_app)
                    SettingsGroupKind.AMBER -> "Embersay" // English: the app's name
                },
            ) {
                group.items.forEachIndexed { i, item ->
                    if (i > 0) RowDivider()
                    ItemRow(item)
                }
            }
        }
    }

    if (choosingTheme) ThemeDialog(settings.ui.theme, onThemeChange) { choosingTheme = false }
}

/**
 * What is kept of a dictation: the history of recent dictations, the statistics (counts and
 * timings, never the words), then the security mode that keeps nothing - one page, as turning
 * the security mode on changes the history.
 */
@Composable
fun PrivacyScreen(settings: AmethystSettings, onOpen: (SettingsPage) -> Unit, modifier: Modifier = Modifier) {
    SubPage(modifier) {
        SettingsGroup(label = null) {
            NavRow(
                stringResource(R.string.settings_history), words(SettingsSummaries.history(settings)), icon = AmberIcons.History,
            ) { onOpen(SettingsPage.HISTORY) }
            RowDivider()
            NavRow(stringResource(R.string.settings_statistics), words(SettingsSummaries.onOff(settings.stats.keep)), icon = AmberIcons.BarChart) { onOpen(SettingsPage.STATS) }
            RowDivider()
            NavRow(
                stringResource(R.string.settings_security_mode), words(SettingsSummaries.onOff(settings.privacy.securityMode)), icon = AmberIcons.Lock,
            ) { onOpen(SettingsPage.SECURITY_MODE) }
        }
    }
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
    /** To the page of who steps in when the server fails ([FallbackScreen]). */
    onOpenFallback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPresets by remember { mutableStateOf(false) }
    val undo = LocalUndo.current
    val context = LocalContext.current

    /**
     * A tap on a row makes it the active recognition at once, with Undo: the one before comes
     * back, with the first run's plan as it was (a model on its way wanted again). No Undo where
     * the small stand-in model goes with the tap: nothing would be left to come back to. The row
     * already in use changes nothing - unless it is the stand-in, whose tap lets the better
     * model's download go: that gets its Undo too.
     */
    fun choose(engine: EngineConfig) {
        val before = settings
        if (!app.amethyst.core.onboarding.FirstRun.changes(before, engine.id)) return
        onUpdateSettings { app.amethyst.core.onboarding.FirstRun.chosen(it, engine.id) }
        if (!app.amethyst.core.onboarding.FirstRun.canUnchoose(before, engine.id)) return
        val name = Texts.recognitionRowName(context, engine)
        val message = if (engine.id == before.activeEngineId) {
            context.getString(R.string.recognition_kept, name)
        } else {
            context.getString(R.string.recognition_switched, name)
        }
        undo.offer(message) {
            onUpdateSettings { app.amethyst.core.onboarding.FirstRun.unchosen(it, before) }
        }
    }

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
                            onSelect = if (cleanupOnly) ({ onOpenEngine(engine.id) }) else ({ choose(engine) }),
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
        // What happens when the server fails belongs to who recognizes: one row at the bottom,
        // its choices on a page of their own - not a second list of the same names here. Only
        // for a server: a recognition on the phone has none to fail.
        val active = settings.activeEngine
        if (active != null && active.trustLevelForDisplay() != TrustLevel.ON_DEVICE) {
            SettingsGroup(label = null) {
                NavRow(
                    stringResource(R.string.settings_fallback),
                    words(SettingsSummaries.fallback(settings, active.trustLevelForDisplay())),
                    icon = AmberIcons.CloudOff,
                    onClick = onOpenFallback,
                )
            }
        }
    }

    fun addPreset(preset: app.amethyst.core.engine.EnginePreset) {
        val language = EngineConfig.defaultLanguageTag(preset.kind, PhoneLocales.tags())
        // A server is added as often as wanted, each with an id of its own.
        val added = if (preset == EnginePresets.SERVER) {
            Servers.newServer(Servers.newId(taken = settings.engines.map { it.id }.toSet()), language)
        } else {
            EngineConfig.fromPreset(preset, languageTag = language)
        }
        onUpdateSettings { it.withEngine(added) }
        onOpenEngine(added.id)
    }

    val ramMb = rememberTotalRamMb()
    // A speech recognition too big for the memory, until the question is answered.
    var askingPreset by remember { mutableStateOf<app.amethyst.core.engine.EnginePreset?>(null) }
    askingPreset?.let { preset ->
        TooBigDialog(
            name = catalog(preset.displayName),
            onUse = { askingPreset = null; addPreset(preset) },
            onDismiss = { askingPreset = null },
        )
    }

    if (showPresets) {
        PresetDialog(
            existingIds = settings.engines.map { it.id }.toSet(),
            tooBig = { EngineAdvice.tooBigForMemory(it, ramMb) },
            onDismiss = { showPresets = false },
            onPick = { preset ->
                showPresets = false
                if (EngineAdvice.tooBigForMemory(preset, ramMb)) askingPreset = preset else addPreset(preset)
            },
        )
    }
}

/**
 * When a recording ends and how long it may get, sound and vibration around it (a tone, a buzz,
 * music paused), and recognizing while you speak. What becomes of the words is [TextScreen]'s.
 */
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
        // Together, as Android has "Sound & vibration": the tone, the buzz, the music paused.
        SectionCard(title = stringResource(R.string.recording_during)) {
            ToggleRow(
                label = stringResource(R.string.bubble_vibrate),
                description = stringResource(R.string.bubble_vibrate_note),
                checked = settings.bubble.haptics,
                onChange = { value -> onUpdateSettings { it.copy(bubble = it.bubble.copy(haptics = value)) } },
            )
            ToggleRow(
                label = stringResource(R.string.recording_sounds),
                description = stringResource(R.string.recording_sounds_note),
                checked = settings.feedback.sounds,
                onChange = { value -> onUpdateSettings { it.copy(feedback = it.feedback.copy(sounds = value)) } },
            )
            ToggleRow(
                label = stringResource(R.string.recording_pause_media),
                description = stringResource(R.string.recording_pause_media_note),
                checked = settings.feedback.pauseMedia,
                onChange = { value -> onUpdateSettings { it.copy(feedback = it.feedback.copy(pauseMedia = value)) } },
            )
        }
        SectionCard(
            title = stringResource(R.string.recording_while_speaking),
        ) {
            ChunkingSettings(settings, onUpdateSettings)
        }
    }
}

/**
 * The bubble, by what people come here for: where it shows (a field, everywhere, the apps it
 * stays out of - listed right here), how it looks, and what its quick menu offers. Sound and
 * vibration are the Recording page's: they go with any dictation.
 */
@Composable
fun BubbleScreen(
    settings: AmethystSettings,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
    onOpenQuickMenu: () -> Unit,
    /** The installed apps, for the hidden ones' names and icons and for hiding it in one more. */
    apps: AppSource,
    usage: Map<String, Int>,
    modifier: Modifier = Modifier,
) {
    val bubble = settings.bubble
    var picking by remember { mutableStateOf(false) }
    fun shown(packageName: String, shown: Boolean) =
        onUpdateSettings { it.copy(bubble = AppRows.withBubble(it.bubble, packageName, shown)) }

    SubPage(modifier) {
        MainSwitch(
            label = stringResource(R.string.bubble_use),
            summary = stringResource(R.string.bubble_use_summary),
            checked = bubble.enabled,
            onChange = { value -> onUpdateSettings { it.copy(bubble = it.bubble.copy(enabled = value)) } },
        )

        SectionCard(title = stringResource(R.string.bubble_where)) {
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
        }

        // The apps it stays out of, here where people look for them; each app's page says it too.
        SettingsGroup(stringResource(R.string.bubble_hidden_group), note = stringResource(R.string.bubble_hidden_note)) {
            // None hidden yet: only the way to hide it somewhere, no row that looks tappable and is not.
            val hidden = bubble.hiddenIn.distinct()
            hidden.forEach { packageName ->
                NavRow(
                    apps.label(packageName),
                    summary = null,
                    leading = { AppIcon(packageName, apps) },
                    trailing = { GhostButton(stringResource(R.string.bubble_hidden_show), { shown(packageName, true) }) },
                    onClick = null,
                )
                RowDivider()
            }
            NavRow(stringResource(R.string.styles_add_app), summary = null, icon = AmberIcons.Add, trailing = null) { picking = true }
        }

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

        SettingsGroup(label = null) {
            NavRow(
                stringResource(R.string.settings_quick_menu),
                quickMenuSummary(QuickMenuLayout.idle(bubble.quickMenuIdle)),
                icon = AmberIcons.Apps,
                onClick = onOpenQuickMenu,
            )
        }
    }

    if (picking) {
        AppPickerDialog(
            title = stringResource(R.string.apps_which_app),
            existing = bubble.hiddenIn.toSet(),
            apps = apps,
            usage = usage,
            onDismiss = { picking = false },
            onPick = { app ->
                picking = false
                shown(app.packageName, false)
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
        ) {
            ReliabilityRows(setup, actions)
        }
    }
}

/**
 * A scrolling page of groups, the frame every settings page shares. A page of long text
 * ([readable]: the privacy policy, the licenses, Diagnostics) keeps its lines to a width that
 * reads, centred, on a wide window - a phone in landscape ran them across 2400 px.
 */
@Composable
internal fun SubPage(modifier: Modifier = Modifier, readable: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val page = modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = PageGutter)
        .padding(bottom = 32.dp)
    if (!readable) {
        Column(page, content = content)
        return
    }
    // The whole width still scrolls; only the text keeps to its column.
    Box(page, contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = READABLE_WIDTH).fillMaxWidth(), content = content)
    }
}

/** About 75 characters of body text a line, where reading stays easy. */
private val READABLE_WIDTH = 640.dp

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
    val name = recognitionRowName(config)
    val openLabel = stringResource(R.string.engine_open_named, name)
    RadioRow(
        label = name,
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
        // The row chooses; the chevron opens its page - quiet, as on the Cleanup page, not an
        // amber "Open" on every row.
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onOpen)
                .semantics { contentDescription = openLabel },
            contentAlignment = Alignment.Center,
        ) {
            ChevronRight()
        }
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
            Hint(words(SettingsSummaries.bubbleSize(shown)))
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
    tooBig: (app.amethyst.core.engine.EnginePreset) -> Boolean,
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
                                    text = recognitionRowName(EngineConfig.fromPreset(preset)),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = AmberPalette.Text,
                                )
                                // Android's follows the phone, as in the list of added ones.
                                TrustBadge(preset.trustLevelForDisplay())
                            }
                            Hint(presetLine(preset))
                            if (tooBig(preset)) Hint(stringResource(R.string.model_too_big), AmberPalette.Error)
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
