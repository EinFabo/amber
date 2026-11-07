package app.amethyst.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.apps.InstalledApp
import app.amethyst.core.apps.AppChoice
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppCleanupMode
import app.amethyst.core.settings.AppProfile
import app.amethyst.core.settings.AppProfiles
import app.amethyst.core.settings.SettingsSummaries
import app.amethyst.core.settings.WritingStyle
import app.amethyst.ui.AmberIcons
import app.amethyst.ui.AppIcon
import app.amethyst.ui.AppSource
import app.amethyst.ui.Hint
import app.amethyst.ui.NavRow
import app.amethyst.ui.RowDivider
import app.amethyst.ui.SettingsGroup
import app.amethyst.ui.ToggleRow
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes
import app.amethyst.ui.words
import kotlin.math.roundToInt

/** Silence before stopping and the longest dictation; part of the Recording page. */
@Composable
fun RecordingLimits(
    settings: AmethystSettings,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
) {
    val capture = settings.capture
    StepSlider(
        label = stringResource(R.string.limits_silence),
        valueText = stringResource(R.string.duration_ms, capture.silenceTimeoutMs),
        value = capture.silenceTimeoutMs,
        range = 500..2_500,
        step = 100,
        enabled = capture.autoStopOnSilence,
        onChange = { ms -> onUpdateSettings { it.copy(capture = it.capture.copy(silenceTimeoutMs = ms)) } },
    )
    StepSlider(
        label = stringResource(R.string.limits_longest),
        valueText = stringResource(R.string.seconds, capture.maxDurationMs / 1000),
        value = capture.maxDurationMs / 1000,
        range = 30..300,
        step = 30,
        onChange = { s -> onUpdateSettings { it.copy(capture = it.capture.copy(maxDurationMs = s * 1000)) } },
    )
}

/** Recognizing longer dictations in parts while the user still speaks. */
@Composable
fun ChunkingSettings(
    settings: AmethystSettings,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
) {
    val capture = settings.capture
    ToggleRow(
        label = stringResource(R.string.chunking),
        description = stringResource(R.string.chunking_note),
        checked = capture.chunking,
        onChange = { on -> onUpdateSettings { it.copy(capture = it.capture.copy(chunking = on)) } },
    )
    StepSlider(
        label = stringResource(R.string.chunking_shortest),
        valueText = capture.chunkMinMs?.let { stringResource(R.string.seconds, it / 1000) } ?: stringResource(R.string.chunking_default),
        value = (capture.chunkMinMs ?: 0) / 1000,
        range = 0..30,
        step = 2,
        enabled = capture.chunking,
        onChange = { s ->
            onUpdateSettings { it.copy(capture = it.capture.copy(chunkMinMs = if (s == 0) null else s * 1000)) }
        },
    )
}

/** A writing style per app: casual in messengers, formal in mail, short in notes. */
@Composable
fun AppStylesScreen(
    settings: AmethystSettings,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
    apps: AppSource,
    usage: Map<String, Int>,
    onOpenApp: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf(false) }
    val ownModels = settings.profiles.any { it.appCleanup.mode == AppCleanupMode.MODEL }

    SubPage(modifier) {
        val styled = settings.cleanup != null || ownModels
        SettingsGroup(
            stringResource(R.string.styles_your_apps),
            note = when {
                settings.cleanup != null -> stringResource(R.string.styles_note_cleanup)
                ownModels -> stringResource(R.string.styles_note_own)
                else -> stringResource(R.string.styles_note_none)
            },
            noteColor = if (styled) AmberPalette.Muted else AmberPalette.Primary,
            learnMore = when {
                settings.cleanup != null -> stringResource(R.string.styles_more_cleanup)
                ownModels -> stringResource(R.string.styles_more_own)
                else -> stringResource(R.string.styles_more_none)
            },
        ) {
            settings.profiles.forEach { profile ->
                NavRow(
                    profile.label,
                    profile.summary(settings),
                    leading = { AppIcon(profile.packageName, apps) },
                ) { onOpenApp(profile.packageName) }
                RowDivider()
            }
            NavRow(stringResource(R.string.styles_add_app), summary = null, icon = AmberIcons.Add, trailing = null) { picking = true }
        }
    }

    if (picking) {
        AppPickerDialog(
            title = stringResource(R.string.styles_which_app),
            existing = settings.profiles.map { it.packageName }.toSet(),
            apps = apps,
            usage = usage,
            onDismiss = { picking = false },
            onPick = { chosen ->
                picking = false
                chosen.firstOrNull()?.let { app ->
                    onUpdateSettings { it.copy(profiles = AppProfiles.upsert(it.profiles, AppProfile(app.packageName, app.label))) }
                    onOpenApp(app.packageName)
                }
            },
        )
    }
}

/** A slider in steps that writes once the finger lifts, like the bubble size. */
@Composable
internal fun StepSlider(
    label: String,
    valueText: String,
    value: Int,
    range: IntRange,
    step: Int,
    enabled: Boolean = true,
    onChange: (Int) -> Unit,
) {
    var dragging by remember(value) { mutableStateOf(value.toFloat()) }
    val shown = ((dragging / step).roundToInt() * step).coerceIn(range)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) AmberPalette.Text else AmberPalette.Muted, modifier = Modifier.weight(1f))
            Hint(if (shown == value) valueText else "$shown")
        }
        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            onValueChangeFinished = { onChange(shown) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / step - 1,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = AmberPalette.Primary,
                activeTrackColor = AmberPalette.Primary,
                inactiveTrackColor = AmberPalette.Outline,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

@Composable
internal fun WritingStyle.label(): String = stringResource(
    when (this) {
        WritingStyle.AS_SPOKEN -> R.string.writing_as_spoken
        WritingStyle.CASUAL -> R.string.writing_casual
        WritingStyle.FORMAL -> R.string.writing_formal
        WritingStyle.CONCISE -> R.string.writing_concise
    },
)

@Composable
internal fun WritingStyle.note(): String = stringResource(
    when (this) {
        WritingStyle.AS_SPOKEN -> R.string.writing_as_spoken_note
        WritingStyle.CASUAL -> R.string.writing_casual_note
        WritingStyle.FORMAL -> R.string.writing_formal_note
        WritingStyle.CONCISE -> R.string.writing_concise_note
    },
)

/** An app's style and what it has of its own: "Concise · own instruction · Gemma 4 31B". */
@Composable
private fun AppProfile.summary(settings: AmethystSettings): String =
    listOfNotNull(
        style.label(),
        instruction?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.styles_own_instruction) },
        when (appCleanup.mode) {
            AppCleanupMode.OFF -> stringResource(R.string.styles_cleanup_off)
            AppCleanupMode.MODEL -> appCleanup.choice?.let { words(SettingsSummaries.cleanup(settings, it)) }
            AppCleanupMode.DEFAULT -> null
        },
    ).joinToString(" · ")

/**
 * Picks apps from those with a launcher icon. The ones the user dictates in come first; the
 * search matches names and packages. With [multiple], rows tick and one button takes them all.
 */
@Composable
internal fun AppPickerDialog(
    title: String,
    existing: Set<String>,
    apps: AppSource,
    usage: Map<String, Int>,
    onDismiss: () -> Unit,
    onPick: (List<InstalledApp>) -> Unit,
    multiple: Boolean = false,
    confirmLabel: ((Int) -> String)? = null,
) {
    val addLabel = stringResource(R.string.add)
    var all by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(emptyList<InstalledApp>()) }
    LaunchedEffect(apps) { all = apps.all().filterNot { it.packageName in existing } }

    fun choose(app: InstalledApp) {
        if (!multiple) {
            onPick(listOf(app))
        } else {
            picked = if (app in picked) picked - app else picked + app
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AmberPalette.Surface,
        shape = AmberShapes.Card,
        title = { Text(title, color = AmberPalette.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.apps_search)) },
                    singleLine = true,
                    shape = AmberShapes.Inner,
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                val loaded = all
                if (loaded == null) {
                    Hint(stringResource(R.string.apps_loading))
                    return@Column
                }
                val grouped = remember(loaded, usage) {
                    AppChoice.group(loaded, { it.packageName }, { it.label }, usage)
                }
                val searching = query.isNotBlank()
                val frequent = grouped.frequent.filter { AppChoice.matches(query, it.label, it.packageName) }
                val rest = grouped.rest.filter { AppChoice.matches(query, it.label, it.packageName) }
                if (frequent.isEmpty() && rest.isEmpty()) {
                    Hint(if (searching) stringResource(R.string.apps_no_match, query.trim()) else stringResource(R.string.apps_all_added))
                    return@Column
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    if (frequent.isNotEmpty()) {
                        if (!searching) item(key = "h-frequent") { GroupLabel(stringResource(R.string.apps_where_you_dictate)) }
                        items(frequent, key = { "f-" + it.packageName }) { app ->
                            AppPickRow(app, apps, usage[app.packageName], multiple, app in picked) { choose(app) }
                        }
                        if (!searching && rest.isNotEmpty()) item(key = "h-all") { GroupLabel(stringResource(R.string.apps_all)) }
                    }
                    items(rest, key = { it.packageName }) { app ->
                        AppPickRow(app, apps, null, multiple, app in picked) { choose(app) }
                    }
                }
            }
        },
        confirmButton = {
            if (multiple) {
                TextButton(onClick = { onPick(picked) }, enabled = picked.isNotEmpty()) {
                    Text(confirmLabel?.invoke(picked.size) ?: addLabel, color = if (picked.isNotEmpty()) AmberPalette.Primary else AmberPalette.Muted)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = AmberPalette.Muted) }
        },
    )
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = AmberPalette.Muted,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun AppPickRow(
    app: InstalledApp,
    apps: AppSource,
    dictations: Int?,
    multiple: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(app.packageName, apps)
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, color = AmberPalette.Text)
            dictations?.let { Hint(pluralStringResource(R.plurals.apps_dictations, it, it)) }
        }
        if (multiple) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onClick() },
                colors = CheckboxDefaults.colors(
                    checkedColor = AmberPalette.Primary,
                    uncheckedColor = AmberPalette.Muted,
                    checkmarkColor = AmberPalette.Background,
                ),
            )
        }
    }
}
