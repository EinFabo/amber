package app.amethyst.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.unit.dp
import app.amethyst.core.apps.AppChoice
import app.amethyst.core.text.ChatCleanup
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppProfile
import app.amethyst.core.settings.AppProfiles
import app.amethyst.core.settings.WritingStyle
import app.amethyst.ui.AppIcon
import app.amethyst.ui.AppSource
import app.amethyst.ui.ChoiceChip
import app.amethyst.ui.InstalledApp
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.SectionCard
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes
import kotlin.math.roundToInt

/** Silence before stopping and the longest dictation; part of the Recording page. */
@Composable
fun RecordingLimits(
    settings: AmethystSettings,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
) {
    val capture = settings.capture
    StepSlider(
        label = "Silence before stopping",
        valueText = "${capture.silenceTimeoutMs} ms",
        value = capture.silenceTimeoutMs,
        range = 500..2_500,
        step = 100,
        enabled = capture.autoStopOnSilence,
        onChange = { ms -> onUpdateSettings { it.copy(capture = it.capture.copy(silenceTimeoutMs = ms)) } },
    )
    StepSlider(
        label = "Longest dictation",
        valueText = "${capture.maxDurationMs / 1000} s",
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
        label = "Recognize in parts",
        description = "Longer dictations are cut at pauses and recognized while you keep " +
            "talking, so stopping only waits for the last part. Local engines only.",
        checked = capture.chunking,
        onChange = { on -> onUpdateSettings { it.copy(capture = it.capture.copy(chunking = on)) } },
    )
    StepSlider(
        label = "Shortest part",
        valueText = capture.chunkMinMs?.let { "${it / 1000} s" } ?: "engine default",
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
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<AppProfile?>(null) }
    var picking by remember { mutableStateOf(false) }
    val hasCleanup = settings.cleanup != null

    SubPage(modifier) {
        SectionCard(
            title = "Your apps",
            subtitle = "How the text is phrased depends on the app it goes into.",
        ) {
            Hint(
                if (hasCleanup) "Applied by the cleanup model chosen under Settings → Cleanup. Apps without a style get the text tidied as spoken."
                else "Styles need a cleanup model: pick one under Settings → Cleanup (a model on Tinfoil, Groq, OpenAI or your own server). Until then the text goes in as recognized.",
                if (hasCleanup) AmberPalette.Muted else AmberPalette.Primary,
            )
            settings.profiles.forEach { profile ->
                ProfileRow(profile, apps) { editing = profile }
            }
            GhostButton("Add app", { picking = true })
        }
    }

    editing?.let { profile ->
        ProfileDialog(
            profile = profile,
            onDismiss = { editing = null },
            onSave = { updated ->
                onUpdateSettings { it.copy(profiles = AppProfiles.upsert(it.profiles, updated)) }
                editing = null
            },
            onDelete = {
                onUpdateSettings { it.copy(profiles = it.profiles.filterNot { p -> p.packageName == profile.packageName }) }
                editing = null
            },
        )
    }

    if (picking) {
        AppPickerDialog(
            title = "Style for which app?",
            existing = settings.profiles.map { it.packageName }.toSet(),
            apps = apps,
            usage = usage,
            onDismiss = { picking = false },
            onPick = { chosen ->
                picking = false
                chosen.firstOrNull()?.let { app -> editing = AppProfile(app.packageName, app.label) }
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

internal fun WritingStyle.label(): String = when (this) {
    WritingStyle.AS_SPOKEN -> "As spoken"
    WritingStyle.CASUAL -> "Casual"
    WritingStyle.FORMAL -> "Formal"
    WritingStyle.CONCISE -> "Concise"
}

private fun WritingStyle.note(): String = when (this) {
    WritingStyle.AS_SPOKEN -> "only tidied"
    WritingStyle.CASUAL -> "chat-like"
    WritingStyle.FORMAL -> "for email"
    WritingStyle.CONCISE -> "short notes"
}

@Composable
private fun ProfileRow(profile: AppProfile, apps: AppSource, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(profile.packageName, apps)
        Column(Modifier.weight(1f)) {
            Text(profile.label, style = MaterialTheme.typography.bodyLarge, color = AmberPalette.Text)
            val extra = listOfNotNull(
                profile.instruction?.takeIf { it.isNotBlank() }?.let { "own instruction" },
                when (profile.cleanup) {
                    false -> "cleanup off"
                    true -> "cleanup always"
                    null -> null
                },
            )
            Hint((listOf(profile.style.label()) + extra).joinToString(" · "))
        }
        Text("Edit", style = MaterialTheme.typography.labelLarge, color = AmberPalette.Primary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileDialog(
    profile: AppProfile,
    onDismiss: () -> Unit,
    onSave: (AppProfile) -> Unit,
    onDelete: () -> Unit,
) {
    var style by remember(profile) { mutableStateOf(profile.style) }
    var instruction by remember(profile) { mutableStateOf(profile.instruction.orEmpty()) }
    var cleanup by remember(profile) { mutableStateOf(profile.cleanup) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AmberPalette.Surface,
        shape = AmberShapes.Card,
        title = { Text(profile.label, color = AmberPalette.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Hint("Style")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WritingStyle.entries.forEach { option ->
                        ChoiceChip(option.label(), option.note(), on = option == style) { style = option }
                    }
                }
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it.take(ChatCleanup.INSTRUCTION_CHARS) },
                    label = { Text("Own instruction (optional)") },
                    placeholder = { Text("e.g. use \"du\", sign with my first name") },
                    supportingText = { Text("${instruction.length} / ${ChatCleanup.INSTRUCTION_CHARS}") },
                    minLines = 2,
                    maxLines = 8,
                    shape = AmberShapes.Inner,
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint("Cleanup")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("Like the engine", null, on = cleanup == null) { cleanup = null }
                    ChoiceChip("Always", null, on = cleanup == true) { cleanup = true }
                    ChoiceChip("Never", "as recognized", on = cleanup == false) { cleanup = false }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(profile.copy(style = style, instruction = instruction.trim().ifEmpty { null }, cleanup = cleanup))
            }) { Text("Save", color = AmberPalette.Primary) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Remove", color = AmberPalette.Error) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = AmberPalette.Muted) }
            }
        },
    )
}

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
    confirmLabel: (Int) -> String = { "Add" },
) {
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
                    placeholder = { Text("Search apps") },
                    singleLine = true,
                    shape = AmberShapes.Inner,
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                val loaded = all
                if (loaded == null) {
                    Hint("Loading apps …")
                    return@Column
                }
                val grouped = remember(loaded, usage) {
                    AppChoice.group(loaded, { it.packageName }, { it.label }, usage)
                }
                val searching = query.isNotBlank()
                val frequent = grouped.frequent.filter { AppChoice.matches(query, it.label, it.packageName) }
                val rest = grouped.rest.filter { AppChoice.matches(query, it.label, it.packageName) }
                if (frequent.isEmpty() && rest.isEmpty()) {
                    Hint(if (searching) "No app matches \u201C${query.trim()}\u201D." else "Every app is added already.")
                    return@Column
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    if (frequent.isNotEmpty()) {
                        if (!searching) item(key = "h-frequent") { GroupLabel("Where you dictate") }
                        items(frequent, key = { "f-" + it.packageName }) { app ->
                            AppPickRow(app, apps, usage[app.packageName], multiple, app in picked) { choose(app) }
                        }
                        if (!searching && rest.isNotEmpty()) item(key = "h-all") { GroupLabel("All apps") }
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
                    Text(confirmLabel(picked.size), color = if (picked.isNotEmpty()) AmberPalette.Primary else AmberPalette.Muted)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = AmberPalette.Muted) }
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
            dictations?.let { Hint(if (it == 1) "1 dictation" else "$it dictations") }
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
