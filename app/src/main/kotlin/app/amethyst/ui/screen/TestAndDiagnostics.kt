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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amethyst.R
import app.amethyst.Texts
import app.amethyst.core.dictation.DiagnosticsReport
import app.amethyst.core.dictation.DictationRecord
import app.amethyst.core.onboarding.AmberVariant
import app.amethyst.core.onboarding.OtherAmbers
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

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
        label = stringResource(R.string.test_field),
        modifier = modifier,
        note = stringResource(R.string.test_field_note),
        trailing = if (testText.isNotEmpty()) {
            { GhostButton(stringResource(R.string.clear), { testText = "" }) }
        } else {
            null
        },
    ) {
        OutlinedTextField(
            value = testText,
            onValueChange = { testText = it },
            placeholder = { Text(stringResource(R.string.test_field_placeholder), color = AmberPalette.Muted) },
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
 * sensitive, to paste into a chat. Nothing here leaves the phone by itself. Neither the copy nor
 * a card shows what was said: a card gives its words' shape until "Show text" on it.
 */
@Composable
fun DiagnosticsScreen(
    records: List<DictationRecord>,
    keyboardLog: List<String>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    /** The app and its version, at the top of a copy. */
    appTitle: String = "Amber", // English: the app's name
    initialFilter: DiagnosticsReport.Filter = DiagnosticsReport.Filter.ALL,
    /** The entries (by their time) shown open from the start - for the screenshots. */
    initiallyOpen: Set<Long> = emptySet(),
    /** Other Ambers switched on beside this one: shown on top and noted in a copy. */
    otherAmbers: List<AmberVariant> = emptyList(),
    onOpenAccessibility: () -> Unit = {},
) {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf(initialFilter) }
    var open by remember { mutableStateOf(initiallyOpen) }
    // The entries (by their time) whose words are shown: none until asked, and never kept.
    var revealed by remember { mutableStateOf(emptySet<Long>()) }
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
        if (otherAmbers.isNotEmpty()) OtherAmbersCard(otherAmbers, onOpenAccessibility)

        // Without voice commands every entry is a dictation: no filter to offer.
        val filters = DiagnosticsReport.filters()
        if (records.isNotEmpty() && filters.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                filters.forEach { option ->
                    ChoiceChip(filterLabel(option), note = null, on = option == filter) { filter = option }
                }
            }
        }

        if (shown.isEmpty()) {
            SectionCard(title = null) {
                Text(emptyTitle(filter, records.isEmpty()), style = MaterialTheme.typography.titleMedium, color = AmberPalette.Text)
                Hint(
                    stringResource(
                        when (filter) {
                            DiagnosticsReport.Filter.ALL -> if (filters.isEmpty()) R.string.diag_empty_all else R.string.diag_empty_all_commands
                            DiagnosticsReport.Filter.DICTATIONS -> R.string.diag_empty_dictations
                            DiagnosticsReport.Filter.COMMANDS -> R.string.diag_empty_commands
                        },
                    ),
                )
            }
        } else {
            SettingsGroup(
                label = lastEntries(filter, shown.size),
                note = stringResource(R.string.diag_note),
                trailing = {
                    Spacer(Modifier.weight(1f))
                    GhostButton(
                        text = stringResource(if (copied) R.string.copied else R.string.copy),
                        onClick = {
                            val text = DiagnosticsReport.of(
                                shown, ZoneId.systemDefault(), appTitle, System.currentTimeMillis(),
                                keyboard = if (keyboardShown) keyboardLog else emptyList(),
                                warning = Texts.otherAmbers(Texts.english(context), otherAmbers),
                            )
                            copied = copySensitive(context, text)
                        },
                    )
                },
            ) {
                // Every record's words, kept out of every card's lines until "Show text".
                val texts = remember(records) { DiagnosticsReport.texts(records) }
                shown.forEachIndexed { i, record ->
                    // Keyed by the record, so an entry's fold state stays with it as new ones arrive on top.
                    key(record.atMillis) {
                        if (i > 0) RowDivider()
                        val at = record.atMillis
                        RecordRow(
                            record,
                            texts,
                            open = at in open,
                            revealed = at in revealed,
                            onReveal = { revealed = if (at in revealed) revealed - at else revealed + at },
                        ) { open = if (at in open) open - at else open + at }
                    }
                }
            }
        }

        if (keyboardShown) {
            // What the bubble saw of the keyboard, newest first: the evidence when it misbehaves.
            SectionCard(title = stringResource(R.string.diag_keyboard)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    keyboardLog.forEach { Hint(it) }
                }
            }
        }

        if (records.isNotEmpty() || keyboardLog.isNotEmpty()) DangerAction(stringResource(R.string.diag_clear), onClear)
    }
}

/**
 * One entry: the shape of what was heard or dictated, the one-line summary, the time - and,
 * opened, a command's steps and the timing in small monospace text. No word of a dictation, a
 * command or a field in any of it ([DiagnosticsReport.anonymous], against all records' [texts]),
 * until [revealed] by "Show text".
 */
@Composable
private fun RecordRow(
    record: DictationRecord,
    texts: List<String>,
    open: Boolean,
    revealed: Boolean,
    onReveal: () -> Unit,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val foldable = record.details.isNotEmpty() || record.timing != null
    val anonymous = remember(record, texts) { DiagnosticsReport.anonymous(record, texts) }
    val allLines = remember(record) { DiagnosticsReport.lines(record) }
    // Only where something is hidden: the words, or a line that quoted them.
    val hidesWords = anonymous.shape != null || anonymous.summary != record.summary() || anonymous.lines != allLines
    val show = revealed && hidesWords
    val shape = anonymous.shape
    Column(Modifier.fillMaxWidth()) {
        NavRow(
            title = when {
                show && record.text.isNotBlank() -> if (record.command) "\u201C${record.text}\u201D" else record.text
                shape != null -> Texts.shape(context, shape)
                record.command -> stringResource(R.string.diag_no_words)
                else -> stringResource(R.string.diag_no_text)
            },
            summary = if (show) record.summary() else anonymous.summary,
            summaryColor = if (record.failed) AmberPalette.Error else AmberPalette.Muted,
            // Open, the whole outcome; folded, two lines of it.
            summaryMaxLines = if (open) Int.MAX_VALUE else 2,
            icon = if (record.command) AmberIcons.VoiceCommand else AmberIcons.Mic,
            below = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Hint(TIME.format(Instant.ofEpochMilli(record.atMillis).atZone(ZoneId.systemDefault())))
                    record.trustLevel?.let { TrustBadge(it) }
                    if (hidesWords) {
                        Spacer(Modifier.weight(1f))
                        GhostButton(stringResource(if (show) R.string.diag_hide_text else R.string.diag_show_text), onReveal)
                    }
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
            // Each of several commands' lines under its step, as the copied text has them.
            val lines = if (show) allLines else anonymous.lines
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

/**
 * The line that says why a failed command was not done: the rules' verdict, where a run stopped -
 * also inside a step - or which of several commands was not done.
 */
// English: the diagnostics lines it reads are English.
private fun String.isWhyNot(): Boolean = trimStart().let {
    it.startsWith("not understood") || it.startsWith("stopped") || (it.startsWith("outcome: ") && " at step " in it) // English
}

@Composable
private fun emptyTitle(filter: DiagnosticsReport.Filter, nothingAtAll: Boolean): String = stringResource(
    when {
        nothingAtAll -> R.string.diag_nothing_yet
        filter == DiagnosticsReport.Filter.COMMANDS -> R.string.diag_no_commands
        else -> R.string.diag_no_dictations
    },
)

@Composable
private fun lastEntries(filter: DiagnosticsReport.Filter, n: Int): String = pluralStringResource(
    when (filter) {
        DiagnosticsReport.Filter.ALL -> R.plurals.diag_last_entries
        DiagnosticsReport.Filter.DICTATIONS -> R.plurals.diag_last_dictations
        DiagnosticsReport.Filter.COMMANDS -> R.plurals.diag_last_commands
    },
    n,
    n,
)

@Composable
private fun filterLabel(filter: DiagnosticsReport.Filter): String = stringResource(
    when (filter) {
        DiagnosticsReport.Filter.ALL -> R.string.diag_filter_all
        DiagnosticsReport.Filter.DICTATIONS -> R.string.diag_filter_dictations
        DiagnosticsReport.Filter.COMMANDS -> R.string.diag_filter_commands
    },
)

/**
 * [text] on the clipboard, marked sensitive as a dictation is ([app.amethyst.accessibility.TextInserter]):
 * kept out of clipboard previews, history and keyboard sync. False when Android refused.
 */
private fun copySensitive(context: Context, text: String): Boolean {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    return runCatching {
        val clip = ClipData.newPlainText("Amber diagnostics", text) // English: a label for the system
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
