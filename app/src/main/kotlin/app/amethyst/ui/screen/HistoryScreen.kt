package app.amethyst.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.ui.ShowsDictatedText
import app.amethyst.Texts
import app.amethyst.core.history.HistoryEntry
import app.amethyst.core.history.HistoryKeep
import app.amethyst.core.privacy.SecurityMode
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.ui.AmberIcons
import app.amethyst.ui.DangerAction
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.NavRow
import app.amethyst.ui.RadioRow
import app.amethyst.ui.RowDivider
import app.amethyst.ui.SectionCard
import app.amethyst.ui.SettingsGroup
import app.amethyst.ui.theme.AmberPalette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The recent dictations ([app.amethyst.core.history.DictationHistory]): how long they are kept,
 * each with its app and time, Copy and Share, and "Delete all". A recording that failed and is
 * still kept can be tried again from here - with no field in front, its words land in the list.
 * In the security mode there is no history, and the page says so.
 */
@Composable
fun HistoryScreen(
    settings: AmethystSettings,
    entries: List<HistoryEntry>,
    appName: (String) -> String,
    onKeepChange: (HistoryKeep) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onDeleteAll: () -> Unit,
    /** How many dictations are kept, counted without opening any: also those that cannot be shown. */
    count: Int = entries.size,
    /** A failed dictation's recording is still kept for "Try again" ([app.amethyst.core.dictation.KeptDictation]). */
    canTryAgain: Boolean = false,
    onTryAgain: () -> Unit = {},
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
) {
    val context = LocalContext.current
    // Dictated words on screen: out of screenshots and the Recents picture while they are.
    ShowsDictatedText(entries.isNotEmpty())
    SubPage {
        if (SecurityMode.on(settings)) {
            SectionCard(title = null) {
                Text(stringResource(R.string.history_off_secure), style = MaterialTheme.typography.titleMedium, color = AmberPalette.Text)
                Hint(stringResource(R.string.history_secure_note))
            }
            return@SubPage
        }
        val keep = settings.privacy.history
        SectionCard(title = stringResource(R.string.history_keep), subtitle = stringResource(R.string.history_keep_note)) {
            HistoryKeep.entries.forEach { choice ->
                RadioRow(label = Texts.historyKeep(context, choice), note = null, selected = choice == keep, onSelect = { onKeepChange(choice) })
            }
        }
        if (canTryAgain) {
            SettingsGroup(label = null) {
                NavRow(
                    title = stringResource(R.string.history_failed),
                    summary = stringResource(R.string.history_failed_note),
                    icon = AmberIcons.Replay,
                    summaryMaxLines = Int.MAX_VALUE,
                    below = { GhostButton(stringResource(R.string.quick_try_again), onTryAgain) },
                    trailing = null,
                    onClick = null,
                )
            }
        }
        if (keep == HistoryKeep.OFF) return@SubPage
        if (entries.isEmpty()) {
            SectionCard(title = null) {
                Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.titleMedium, color = AmberPalette.Text)
                Hint(stringResource(R.string.history_empty_note, Texts.historyKeep(context, keep)))
            }
        } else {
            SettingsGroup(label = stringResource(R.string.history_recent)) {
                entries.forEachIndexed { i, entry ->
                    key(entry.atMillis) {
                        if (i > 0) RowDivider()
                        EntryRow(entry, entry.app?.let(appName), timeOf(entry.atMillis, zone, today), { onCopy(entry.text) }, { onShare(entry.text) })
                    }
                }
            }
        }
        // Also for what is kept but cannot be shown: it can always be deleted.
        if (entries.isNotEmpty() || count > 0) DangerAction(stringResource(R.string.history_delete_all), onDeleteAll)
    }
}

/** One dictation: where and when as its title, its words in full, Copy and Share under them. */
@Composable
private fun EntryRow(entry: HistoryEntry, app: String?, at: String, onCopy: () -> Unit, onShare: () -> Unit) {
    NavRow(
        title = listOfNotNull(app, at).joinToString(" · "),
        summary = entry.text,
        icon = AmberIcons.Subject,
        summaryColor = AmberPalette.Text,
        summaryMaxLines = MAX_LINES,
        below = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                GhostButton(stringResource(R.string.copy), onCopy)
                GhostButton(stringResource(R.string.share), onShare)
            }
        },
        trailing = null,
        onClick = null,
    )
}

/** Today's time alone; an earlier day's date and time, as the phone writes them. */
private fun timeOf(atMillis: Long, zone: ZoneId, today: LocalDate): String {
    val at = Instant.ofEpochMilli(atMillis).atZone(zone)
    val format = if (at.toLocalDate() == today) DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    return format.format(at)
}

/** A long dictation shows this many lines; Copy and Share take it whole. */
private const val MAX_LINES = 8
