package app.amethyst.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amethyst.core.dictation.DiagnosticsReport
import app.amethyst.core.dictation.DictationRecord
import app.amethyst.ui.AmberIcons
import app.amethyst.ui.ChoiceChip
import app.amethyst.ui.DangerAction
import app.amethyst.ui.ExpandChevron
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.NavRow
import app.amethyst.ui.RowDivider
import app.amethyst.ui.SectionCard
import app.amethyst.ui.SettingsGroup
import app.amethyst.ui.TrustBadge
import app.amethyst.ui.Unfold
import app.amethyst.ui.foldState
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The first thing to try after installing: dictate into a field inside Amber itself.
 *
 * This exists so a failure is attributable. The bubble path involves the microphone, the
 * engine, the normalizer, the accessibility service, the insertion chain and a foreign app's
 * text field - six things that can break. Here the app is in the foreground, where microphone
 * access is never refused, and the target is a plain Compose field. If this works and
 * dictating into WhatsApp does not, the problem is the insertion path, not the engine.
 */
@Composable
fun TestFieldCard(focusRequester: FocusRequester, modifier: Modifier = Modifier) {
    var testText by rememberSaveable { mutableStateOf("") }

    // The field is the group's surface itself, not a box inside it.
    SettingsGroup(
        label = "Test field",
        modifier = modifier,
        note = "A test dictation lands here, as in any other app.",
        trailing = if (testText.isNotEmpty()) {
            { GhostButton("Clear", { testText = "" }) }
        } else {
            null
        },
    ) {
        OutlinedTextField(
            value = testText,
            onValueChange = { testText = it },
            placeholder = { Text("Tap the flame and speak …", color = AmberPalette.Muted) },
            minLines = 3,
            shape = AmberShapes.Card,
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = fieldColors(container = Color.Transparent),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
    }
}

/**
 * Filled fields without a frame; only the focused one gets a thin amber edge. Raised a step
 * above the group surface they mostly sit on, unless [container] says otherwise.
 */
@Composable
fun fieldColors(container: Color = AmberPalette.Elevated) = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = container,
    unfocusedContainerColor = container,
    focusedBorderColor = AmberPalette.Primary,
    unfocusedBorderColor = Color.Transparent,
    cursorColor = AmberPalette.Primary,
    focusedTextColor = AmberPalette.Text,
    unfocusedTextColor = AmberPalette.Text,
    focusedLabelColor = AmberPalette.Accent,
    unfocusedLabelColor = AmberPalette.Muted,
)

/**
 * What actually happened on the last dictations and voice commands, so a bug report can be
 * precise. A command opens to its steps - what the rules made of the words, what was tried, why
 * it stopped - and "Copy" puts the entries shown on the clipboard as plain text, marked
 * sensitive, to paste into a chat. Nothing here leaves the phone by itself.
 */
@Composable
fun DiagnosticsScreen(
    records: List<DictationRecord>,
    keyboardLog: List<String>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    /** The app and its version, at the top of a copy. */
    appTitle: String = "Amber",
    initialFilter: DiagnosticsReport.Filter = DiagnosticsReport.Filter.ALL,
    /** The entries (by their time) shown open from the start - for the screenshots. */
    initiallyOpen: Set<Long> = emptySet(),
) {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf(initialFilter) }
    var open by remember { mutableStateOf(initiallyOpen) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_MS)
            copied = false
        }
    }
    val shown = filter.apply(records)
    val keyboardShown = filter != DiagnosticsReport.Filter.COMMANDS && keyboardLog.isNotEmpty()

    SubPage(modifier) {
        if (records.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DiagnosticsReport.Filter.entries.forEach { option ->
                    ChoiceChip(option.label, note = null, on = option == filter) { filter = option }
                }
            }
        }

        if (shown.isEmpty()) {
            SectionCard(title = null) {
                Text(emptyTitle(filter, records.isEmpty()), style = MaterialTheme.typography.titleMedium, color = AmberPalette.Text)
                Hint(
                    when (filter) {
                        DiagnosticsReport.Filter.ALL -> "After the first dictation or voice command, what was heard and what came of it show up here."
                        DiagnosticsReport.Filter.DICTATIONS -> "After the first dictation, engine, duration and insertion method show up here."
                        DiagnosticsReport.Filter.COMMANDS -> "After the first voice command, what was heard and every step it took show up here."
                    },
                )
            }
        } else {
            SettingsGroup(
                label = "The last ${shown.size} ${noun(filter, shown.size)}",
                note = "Tap one for its steps. Kept on this phone until Amber restarts.",
                trailing = {
                    Spacer(Modifier.weight(1f))
                    GhostButton(
                        text = if (copied) "Copied" else "Copy",
                        onClick = {
                            val text = DiagnosticsReport.of(
                                shown, ZoneId.systemDefault(), appTitle, System.currentTimeMillis(),
                                keyboard = if (keyboardShown) keyboardLog else emptyList(),
                            )
                            copied = copySensitive(context, text)
                        },
                    )
                },
            ) {
                shown.forEachIndexed { i, record ->
                    // Keyed by the record, so an entry's fold state stays with it as new ones arrive on top.
                    key(record.atMillis) {
                        if (i > 0) RowDivider()
                        val at = record.atMillis
                        RecordRow(record, open = at in open) { open = if (at in open) open - at else open + at }
                    }
                }
            }
        }

        if (keyboardShown) {
            // What the bubble saw of the keyboard, newest first: the evidence when it misbehaves.
            SectionCard(title = "Keyboard") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    keyboardLog.forEach { Hint(it) }
                }
            }
        }

        if (records.isNotEmpty() || keyboardLog.isNotEmpty()) DangerAction("Clear diagnostics", onClear)
    }
}

/**
 * One entry: what was heard or dictated, the one-line summary, the time - and, opened, a
 * command's steps and the timing in small monospace text.
 */
@Composable
private fun RecordRow(record: DictationRecord, open: Boolean, onToggle: () -> Unit) {
    val foldable = record.details.isNotEmpty() || record.timing != null
    Column(Modifier.fillMaxWidth()) {
        NavRow(
            title = when {
                record.text.isNotBlank() -> if (record.command) "\u201C${record.text}\u201D" else record.text
                record.command -> "No words"
                else -> "No text"
            },
            summary = record.summary(),
            summaryColor = if (record.failed) AmberPalette.Error else AmberPalette.Muted,
            // Open, the whole outcome; folded, two lines of it.
            summaryMaxLines = if (open) Int.MAX_VALUE else 2,
            icon = if (record.command) AmberIcons.VoiceCommand else AmberIcons.Mic,
            below = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Hint(TIME.format(Instant.ofEpochMilli(record.atMillis).atZone(ZoneId.systemDefault())))
                    record.trustLevel?.let { TrustBadge(it) }
                }
            },
            trailing = if (foldable) {
                { ExpandChevron(open) }
            } else {
                null
            },
            modifier = if (foldable) Modifier.foldState(open) else Modifier,
            onClick = if (foldable) onToggle else null,
        )
        Unfold(open && foldable) {
            val lines = record.details + listOfNotNull(record.timing?.let { "timing: $it" })
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = DETAILS_START, end = 16.dp, bottom = 14.dp)
                    .clip(AmberShapes.Inner)
                    .background(AmberPalette.Elevated)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                lines.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
                        color = if (record.failed && line.isWhyNot()) AmberPalette.Error else AmberPalette.Text,
                    )
                }
            }
        }
    }
}

/** The line that says why a failed command was not done: the rules' verdict, or where a run stopped. */
private fun String.isWhyNot(): Boolean = startsWith("not understood") || startsWith("stopped")

private fun emptyTitle(filter: DiagnosticsReport.Filter, nothingAtAll: Boolean): String = when {
    nothingAtAll -> "Nothing dictated yet"
    filter == DiagnosticsReport.Filter.COMMANDS -> "No voice commands yet"
    else -> "No dictations yet"
}

private fun noun(filter: DiagnosticsReport.Filter, n: Int): String = when (filter) {
    DiagnosticsReport.Filter.ALL -> if (n == 1) "entry" else "entries"
    DiagnosticsReport.Filter.DICTATIONS -> if (n == 1) "dictation" else "dictations"
    DiagnosticsReport.Filter.COMMANDS -> if (n == 1) "command" else "commands"
}

/**
 * [text] on the clipboard, marked sensitive as a dictation is ([app.amethyst.accessibility.TextInserter]):
 * kept out of clipboard previews, history and keyboard sync. False when Android refused.
 */
private fun copySensitive(context: Context, text: String): Boolean {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    return runCatching {
        val clip = ClipData.newPlainText("Amber diagnostics", text)
        clip.description.extras = PersistableBundle().apply { putBoolean(SENSITIVE_EXTRA, true) }
        clipboard.setPrimaryClip(clip)
    }.isSuccess
}

/** ClipDescription.EXTRA_IS_SENSITIVE (API 33); honoured by name on earlier versions too. */
private const val SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"
private const val COPIED_MS = 2_000L

/** Where a row's text starts behind its icon tile: the steps line up with it. */
private val DETAILS_START = 68.dp
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
