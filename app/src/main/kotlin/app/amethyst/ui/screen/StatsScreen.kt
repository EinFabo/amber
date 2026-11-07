package app.amethyst.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.stats.StatsSummary
import app.amethyst.ui.DangerAction
import app.amethyst.ui.DayBars
import app.amethyst.ui.Figure
import app.amethyst.ui.Hint
import app.amethyst.ui.LearnMore
import app.amethyst.ui.MainSwitch
import app.amethyst.ui.SectionCard
import app.amethyst.ui.ShareBar
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes
import app.amethyst.ui.theme.badgeColor
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The numbers behind the home screen's "today" line. Calm on purpose: a few figures, one week
 * of bars, shares as thin lines. Everything is counted on this phone, from counts and timings
 * only - the screen says so, and how "time saved" is worked out.
 */
@Composable
fun StatsScreen(
    settings: AmethystSettings,
    stats: StatsSummary?,
    today: Long,
    engineName: (String) -> String,
    appName: (String) -> String,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }

    SubPage(modifier) {
        MainSwitch(
            label = stringResource(R.string.stats_use),
            summary = stringResource(R.string.stats_use_summary),
            checked = settings.stats.keep,
            onChange = { on -> onUpdateSettings { it.copy(stats = it.stats.copy(keep = on)) } },
        )
        LearnMore(stringResource(R.string.stats_use_more))

        if (stats == null || stats.isEmpty) {
            SectionCard(title = null) {
                Text(stringResource(R.string.stats_nothing), style = MaterialTheme.typography.titleMedium, color = AmberPalette.Text)
                Hint(
                    stringResource(if (settings.stats.keep) R.string.stats_nothing_on else R.string.stats_nothing_off),
                )
            }
        } else {
            SectionCard(title = stringResource(R.string.stats_week)) {
                Row(Modifier.fillMaxWidth()) {
                    Figure(formatCount(stats.wordsToday), stringResource(R.string.stats_words_today), Modifier.weight(1f))
                    Figure(formatCount(stats.lastDays.sum()), stringResource(R.string.stats_this_week), Modifier.weight(1f))
                    Figure(formatMinutes(stats.minutesSavedTotal), stringResource(R.string.stats_saved_so_far), Modifier.weight(1f))
                }
                Column {
                    DayBars(stats.lastDays)
                    WeekdayLabels(today, stats.lastDays.size)
                }
            }

            SectionCard(title = stringResource(R.string.stats_where)) {
                val total = stats.totalDictations.toFloat()
                listOf(TrustLevel.ON_DEVICE, TrustLevel.ENCLAVE_VERIFIED, TrustLevel.CLOUD).forEach { level ->
                    val count = stats.dictationsByTrust[level] ?: 0
                    if (count > 0) {
                        ShareBar(level.place(), "$count", count / total, level.badgeColor())
                    }
                }
            }

            if (stats.wordsByApp.isNotEmpty()) {
                SectionCard(title = stringResource(R.string.stats_per_app)) {
                    val top = stats.wordsByApp.first().second.coerceAtLeast(1).toFloat()
                    stats.wordsByApp.take(MAX_APPS).forEach { (pkg, words) ->
                        ShareBar(appName(pkg), formatCount(words), words / top)
                    }
                }
            }

            SectionCard(title = stringResource(R.string.stats_waiting), subtitle = stringResource(R.string.stats_waiting_subtitle)) {
                stats.medianWaitMs.entries.sortedBy { it.value }.forEach { (engine, ms) ->
                    ValueRow(engineName(engine), formatSeconds(ms))
                }
            }

            SectionCard(title = stringResource(R.string.stats_records)) {
                ValueRow(stringResource(R.string.stats_days_in_row), "${stats.streakDays}")
                ValueRow(stringResource(R.string.stats_longest), formatSeconds(stats.longestAudioMs))
                stats.speakingWpm?.let { ValueRow(stringResource(R.string.stats_pace), pluralStringResource(R.plurals.words_a_minute, it, it)) }
                ValueRow(stringResource(R.string.stats_words_all), formatCount(stats.totalWords))
                ValueRow(stringResource(R.string.stats_dictations), formatCount(stats.totalDictations))
                stats.firstDay?.let {
                    ValueRow(
                        stringResource(R.string.stats_since),
                        java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(Locale.getDefault()).format(LocalDate.ofEpochDay(it)),
                    )
                }
                if (stats.costUsd > 0) ValueRow(stringResource(R.string.stats_costs), formatUsd(stats.costUsd))
            }
        }

        SectionCard(title = stringResource(R.string.stats_time_saved), subtitle = stringResource(R.string.stats_time_saved_subtitle)) {
            StepSlider(
                label = stringResource(R.string.stats_typing_speed),
                valueText = pluralStringResource(R.plurals.words_a_minute, settings.stats.typingWpm, settings.stats.typingWpm),
                value = settings.stats.typingWpm,
                range = 20..80,
                step = 5,
                onChange = { wpm -> onUpdateSettings { it.copy(stats = it.stats.copy(typingWpm = wpm)) } },
            )
        }

        if (stats != null && !stats.isEmpty) DangerAction(stringResource(R.string.stats_clear)) { confirmClear = true }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = AmberPalette.Surface,
            shape = AmberShapes.Card,
            title = { Text(stringResource(R.string.stats_clear_title), color = AmberPalette.Text) },
            text = { Hint(stringResource(R.string.stats_clear_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text(stringResource(R.string.clear), color = AmberPalette.Error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel), color = AmberPalette.Muted) }
            },
        )
    }
}

@Composable
private fun WeekdayLabels(today: Long, days: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        (today - days + 1..today).forEach { day ->
            Text(
                LocalDate.ofEpochDay(day).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                style = MaterialTheme.typography.labelSmall,
                color = if (day == today) AmberPalette.Primary else AmberPalette.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = AmberPalette.Text, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = AmberPalette.Muted)
    }
}

@Composable
private fun TrustLevel.place(): String = stringResource(
    when (this) {
        TrustLevel.ON_DEVICE -> R.string.place_phone
        TrustLevel.ENCLAVE_VERIFIED -> R.string.place_verified_enclave
        TrustLevel.CLOUD -> R.string.place_server
    },
)

private const val MAX_APPS = 5

/** "1,234" or "1.234", as the phone writes numbers. */
internal fun formatCount(n: Int): String = "%,d".format(Locale.getDefault(), n)

/** "800 ms", "1.2 s" (or "1,2 s"), "2 min 5 s"; a no-break space keeps number and unit together. */
@Composable
internal fun formatSeconds(ms: Long): String = when {
    ms < 1_000 -> stringResource(R.string.duration_ms, ms.toInt())
    ms < 60_000 -> stringResource(R.string.duration_s, "%.1f".format(Locale.getDefault(), ms / 1000.0))
    else -> stringResource(R.string.duration_min_s, (ms / 60_000).toInt(), (ms / 1000 % 60).toInt())
}.replace(' ', '\u00A0')

@Composable
internal fun formatMinutes(minutes: Double): String = when {
    minutes < 1 -> stringResource(R.string.duration_s, (minutes * 60).toInt().toString())
    minutes < 90 -> stringResource(R.string.duration_min, minutes.toInt())
    else -> stringResource(R.string.duration_h, "%.1f".format(Locale.getDefault(), minutes / 60))
}

@Composable
internal fun formatMinutesSaved(minutes: Double): String =
    if (minutes < 1) stringResource(R.string.saved_under_minute) else stringResource(R.string.saved_about, formatMinutes(minutes))

@Composable
internal fun daysInARow(days: Int): String = pluralStringResource(R.plurals.days_in_a_row, days, days)

@Composable
private fun formatUsd(usd: Double): String =
    if (usd < 0.01) stringResource(R.string.usd_under_cent) else stringResource(R.string.usd, "%.2f".format(Locale.getDefault(), usd))
