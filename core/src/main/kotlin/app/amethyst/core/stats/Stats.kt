package app.amethyst.core.stats

import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.privacy.TrustLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max

/**
 * One finished dictation, as the statistics see it: counts and timings, never the text or
 * the audio. About 150 bytes as a line of JSON.
 */
@Serializable
data class DictationStat(
    /** Epoch milliseconds. */
    val at: Long,
    /** Local calendar day, as LocalDate.toEpochDay(). */
    val day: Long,
    val engine: String,
    val model: String? = null,
    val trust: TrustLevel,
    val words: Int,
    val audioMs: Long,
    /** From the stop to the text being there: what the user waited. */
    val waitMs: Long,
    val app: String? = null,
    val cleanupModel: String? = null,
    /** Estimated, in millionths of a US dollar; null when the engine costs nothing. */
    val costMicroUsd: Long? = null,
)

/**
 * The statistics, kept on this phone only: an append-only JSON Lines file in a directory that
 * is not backed up. Each dictation adds one line; reading parses them all, which stays fast -
 * a year of heavy use is a few thousand lines.
 */
class StatsLedger(private val file: File, private val zone: () -> ZoneId = ZoneId::systemDefault) {

    private val lock = Any()

    fun record(stat: DictationStat) = synchronized(lock) {
        file.parentFile?.mkdirs()
        file.appendText(json.encodeToString(DictationStat.serializer(), stat) + "\n")
    }

    /** Every stat that still parses; a damaged line is skipped, not fatal. */
    fun all(): List<DictationStat> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        file.readLines().mapNotNull { line ->
            line.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(DictationStat.serializer(), it) }.getOrNull() }
        }
    }

    fun clear() = synchronized(lock) { file.delete() }

    fun dayOf(atMillis: Long): Long = Instant.ofEpochMilli(atMillis).atZone(zone()).toLocalDate().toEpochDay()

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    }
}

/** What the statistics screen and the home line show, computed from the ledger. */
data class StatsSummary(
    val wordsToday: Int,
    val dictationsToday: Int,
    /** Words per day, oldest first, ending today. */
    val lastDays: List<Int>,
    val totalWords: Int,
    val totalDictations: Int,
    /** Typing time the words would have taken minus the time speaking and waiting took. */
    val minutesSavedToday: Double,
    val minutesSavedTotal: Double,
    /** Words per app package, most first. */
    val wordsByApp: List<Pair<String, Int>>,
    val dictationsByTrust: Map<TrustLevel, Int>,
    /** Median wait after stopping, per engine id. */
    val medianWaitMs: Map<String, Long>,
    /** Consecutive days with a dictation, ending today or yesterday. */
    val streakDays: Int,
    val longestAudioMs: Long,
    /** Words a minute while speaking, over all dictations. */
    val speakingWpm: Int?,
    val costUsd: Double,
    val firstDay: Long?,
) {
    val isEmpty: Boolean get() = totalDictations == 0

    companion object {
        const val DEFAULT_TYPING_WPM = 40

        fun of(stats: List<DictationStat>, today: Long, typingWpm: Int = DEFAULT_TYPING_WPM, days: Int = 7): StatsSummary {
            val todays = stats.filter { it.day == today }
            val byDay = stats.groupBy { it.day }
            val activeDays = byDay.keys
            var streak = 0
            var day = if (today in activeDays) today else today - 1
            while (day in activeDays) {
                streak++
                day--
            }
            val totalAudioMs = stats.sumOf { it.audioMs }
            val totalWords = stats.sumOf { it.words }
            return StatsSummary(
                wordsToday = todays.sumOf { it.words },
                dictationsToday = todays.size,
                lastDays = (today - days + 1..today).map { d -> byDay[d].orEmpty().sumOf { it.words } },
                totalWords = totalWords,
                totalDictations = stats.size,
                minutesSavedToday = saved(todays, typingWpm),
                minutesSavedTotal = saved(stats, typingWpm),
                wordsByApp = stats.filter { it.app != null }
                    .groupBy { it.app!! }
                    .map { (app, list) -> app to list.sumOf { it.words } }
                    .sortedByDescending { it.second },
                dictationsByTrust = stats.groupingBy { it.trust }.eachCount(),
                medianWaitMs = stats.groupBy { it.engine }.mapValues { (_, list) -> median(list.map { it.waitMs }) },
                streakDays = streak,
                longestAudioMs = stats.maxOfOrNull { it.audioMs } ?: 0,
                speakingWpm = if (totalAudioMs >= MIN_AUDIO_FOR_PACE_MS) (totalWords * 60_000L / totalAudioMs).toInt() else null,
                costUsd = stats.sumOf { it.costMicroUsd ?: 0 } / 1_000_000.0,
                firstDay = stats.minOfOrNull { it.day },
            )
        }

        private const val MIN_AUDIO_FOR_PACE_MS = 30_000L

        private fun saved(stats: List<DictationStat>, typingWpm: Int): Double = max(
            0.0,
            stats.sumOf { it.words } / typingWpm.coerceAtLeast(1).toDouble() -
                stats.sumOf { it.audioMs + it.waitMs } / 60_000.0,
        )

        private fun median(values: List<Long>): Long {
            if (values.isEmpty()) return 0
            val sorted = values.sorted()
            return sorted[sorted.size / 2]
        }
    }
}

/** A server's bill for one dictation, from the prices in [ServerProviders]. An estimate. */
object ServerCost {

    /** Voxtral reads audio as about 12.5 tokens a second; text is about 4 characters a token. */
    private const val AUDIO_MS_PER_TOKEN = 80
    private const val CHARS_PER_TOKEN = 4
    private const val PROMPT_TOKENS = 250

    /** Null for an own server or an unknown model: nothing to estimate, nothing billed by us. */
    fun microUsd(baseUrl: String?, speechModel: String?, audioMs: Long, chars: Int, cleanupModel: String?): Long? {
        val provider = ServerProviders.forUrl(baseUrl)
        val speech = provider.speechModel(speechModel)
        val cleanup = provider.cleanupModel(cleanupModel)
        if (speech == null && cleanup == null) return null
        val textTokens = chars / CHARS_PER_TOKEN + 1
        val speechUsd = speech?.let {
            it.usdPerRequest + audioMs / 60_000.0 * it.usdPerMinute +
                (audioMs / AUDIO_MS_PER_TOKEN) * it.usdPerMIn / 1e6 + textTokens * it.usdPerMOut / 1e6
        } ?: 0.0
        val cleanupUsd = cleanup?.let {
            (PROMPT_TOKENS + textTokens) * it.usdPerMIn / 1e6 + textTokens * it.usdPerMOut / 1e6
        } ?: 0.0
        return ((speechUsd + cleanupUsd) * 1e6).toLong()
    }
}

/** Words as the statistics count them: runs of letters or digits. */
fun countWords(text: String): Int = Regex("""[\p{L}\p{N}]+(?:['’-][\p{L}\p{N}]+)*""").findAll(text).count()
