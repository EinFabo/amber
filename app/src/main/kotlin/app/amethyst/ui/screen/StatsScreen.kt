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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
            label = "Use statistics",
            summary = "Counts only, never what you said.",
            checked = settings.stats.keep,
            onChange = { on -> onUpdateSettings { it.copy(stats = it.stats.copy(keep = on)) } },
        )
        LearnMore(
            "Counts and timings are kept on this phone and not backed up. Off, nothing new is counted; " +
                "what is there stays until you clear it.",
        )

        if (stats == null || stats.isEmpty) {
            SectionCard(title = null) {
                Text("Nothing counted yet", style = MaterialTheme.typography.titleMedium, color = AmberPalette.Text)
                Hint(
                    if (settings.stats.keep) "Dictate once and your words, time saved and the apps you dictate into show up here."
                    else "Statistics are off. Turn them on above to start counting.",
                )
            }
        } else {
            SectionCard(title = "This week") {
                Row(Modifier.fillMaxWidth()) {
                    Figure(formatCount(stats.wordsToday), "words today", Modifier.weight(1f))
                    Figure(formatCount(stats.lastDays.sum()), "this week", Modifier.weight(1f))
                    Figure(formatMinutes(stats.minutesSavedTotal), "saved so far", Modifier.weight(1f))
                }
                Column {
                    DayBars(stats.lastDays)
                    WeekdayLabels(today, stats.lastDays.size)
                }
            }

            SectionCard(title = "Where your voice went") {
                val total = stats.totalDictations.toFloat()
                listOf(TrustLevel.ON_DEVICE, TrustLevel.ENCLAVE_VERIFIED, TrustLevel.CLOUD).forEach { level ->
                    val count = stats.dictationsByTrust[level] ?: 0
                    if (count > 0) {
                        ShareBar(level.place(), "$count", count / total, level.badgeColor())
                    }
                }
            }

            if (stats.wordsByApp.isNotEmpty()) {
                SectionCard(title = "Words per app") {
                    val top = stats.wordsByApp.first().second.coerceAtLeast(1).toFloat()
                    stats.wordsByApp.take(MAX_APPS).forEach { (pkg, words) ->
                        ShareBar(appName(pkg), formatCount(words), words / top)
                    }
                }
            }

            SectionCard(title = "Waiting after you stop", subtitle = "Typical, measured on this phone.") {
                stats.medianWaitMs.entries.sortedBy { it.value }.forEach { (engine, ms) ->
                    ValueRow(engineName(engine), formatSeconds(ms))
                }
            }

            SectionCard(title = "Records") {
                ValueRow("Days in a row", "${stats.streakDays}")
                ValueRow("Longest dictation", formatSeconds(stats.longestAudioMs))
                stats.speakingWpm?.let { ValueRow("Your speaking pace", "$it words a minute") }
                ValueRow("Words in all", formatCount(stats.totalWords))
                ValueRow("Dictations", formatCount(stats.totalDictations))
                stats.firstDay?.let { ValueRow("Counting since", LocalDate.ofEpochDay(it).toString()) }
                if (stats.costUsd > 0) ValueRow("Server costs, estimated", formatUsd(stats.costUsd))
            }
        }

        SectionCard(title = "Time saved", subtitle = "Typing at your speed, against the time you spoke and waited.") {
            StepSlider(
                label = "Your typing speed",
                valueText = "${settings.stats.typingWpm} words a minute",
                value = settings.stats.typingWpm,
                range = 20..80,
                step = 5,
                onChange = { wpm -> onUpdateSettings { it.copy(stats = it.stats.copy(typingWpm = wpm)) } },
            )
        }

        if (stats != null && !stats.isEmpty) DangerAction("Clear statistics") { confirmClear = true }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = AmberPalette.Surface,
            shape = AmberShapes.Card,
            title = { Text("Clear statistics?", color = AmberPalette.Text) },
            text = { Hint("All counts and timings are deleted from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text("Clear", color = AmberPalette.Error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = AmberPalette.Muted) }
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

private fun TrustLevel.place(): String = when (this) {
    TrustLevel.ON_DEVICE -> "On this phone"
    TrustLevel.ENCLAVE_VERIFIED -> "Verified enclave"
    TrustLevel.CLOUD -> "Server"
}

private const val MAX_APPS = 5

internal fun formatCount(n: Int): String = "%,d".format(Locale.US, n)

internal fun formatSeconds(ms: Long): String = when {
    // A no-break space keeps the number and its unit on one line.
    ms < 1_000 -> "$ms\u00A0ms"
    ms < 60_000 -> "%.1f\u00A0s".format(Locale.US, ms / 1000.0)
    else -> "${ms / 60_000} min ${ms / 1000 % 60} s"
}

internal fun formatMinutes(minutes: Double): String = when {
    minutes < 1 -> "${(minutes * 60).toInt()} s"
    minutes < 90 -> "${minutes.toInt()} min"
    else -> "%.1f h".format(Locale.US, minutes / 60)
}

internal fun formatMinutesSaved(minutes: Double): String =
    if (minutes < 1) "under a minute saved" else "about ${formatMinutes(minutes)} saved"

internal fun Int.daysInARow(): String = if (this == 1) "1 day in a row" else "$this days in a row"

private fun formatUsd(usd: Double): String = if (usd < 0.01) "under \$0.01" else "\$%.2f".format(Locale.US, usd)
