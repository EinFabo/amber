package app.amethyst.core

import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.stats.DictationStat
import app.amethyst.core.stats.StatsLedger
import app.amethyst.core.stats.StatsSummary
import app.amethyst.core.stats.ServerCost
import app.amethyst.core.stats.countWords
import java.nio.file.Files
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatsTest {

    private fun stat(day: Long, words: Int, engine: String = "local", trust: TrustLevel = TrustLevel.ON_DEVICE, app: String? = "com.whatsapp", audioMs: Long = 10_000, waitMs: Long = 500) =
        DictationStat(at = day * 86_400_000, day = day, engine = engine, trust = trust, words = words, audioMs = audioMs, waitMs = waitMs, app = app)

    @Test
    fun `the ledger keeps counts across a reopen and skips a damaged line`() {
        val file = Files.createTempDirectory("stats").resolve("stats.jsonl").toFile()
        StatsLedger(file).record(stat(100, 12))
        file.appendText("{broken\n")
        StatsLedger(file).record(stat(101, 8))
        val all = StatsLedger(file).all()
        assertEquals(listOf(12, 8), all.map { it.words })
        assertTrue("text" !in file.readText(), "no dictated text is ever stored")
    }

    @Test
    fun `days follow the phone's time zone`() {
        val ledger = StatsLedger(Files.createTempFile("s", ".jsonl").toFile()) { ZoneOffset.ofHours(2) }
        // 23:30 UTC is already the next day at UTC+2.
        assertEquals(1, ledger.dayOf(23L * 3_600_000 + 30 * 60_000))
        // 01:30 local: the next day starts in 22.5 hours, not the next UTC midnight.
        assertEquals(22L * 3_600_000 + 30 * 60_000, ledger.millisUntilNextDay(23L * 3_600_000 + 30 * 60_000))
    }

    @Test
    fun `summary counts today, the week, the streak and the apps`() {
        val stats = listOf(stat(7, 40), stat(8, 30), stat(10, 20), stat(10, 10, app = "com.google.android.gm"))
        val summary = StatsSummary.of(stats, today = 10)
        assertEquals(30, summary.wordsToday)
        assertEquals(2, summary.dictationsToday)
        assertEquals(listOf(0, 0, 0, 40, 30, 0, 30), summary.lastDays)
        assertEquals(1, summary.streakDays, "day 9 was skipped")
        assertEquals("com.whatsapp" to 90, summary.wordsByApp.first())
    }

    @Test
    fun `a streak may end yesterday`() {
        assertEquals(2, StatsSummary.of(listOf(stat(8, 1), stat(9, 1)), today = 10).streakDays)
    }

    @Test
    fun `time saved compares typing with speaking and waiting`() {
        // 80 words typed at 40 wpm: 2 minutes. Spoken in 30 s plus 0 s wait: 1.5 minutes saved.
        val summary = StatsSummary.of(listOf(stat(1, 80, audioMs = 30_000, waitMs = 0)), today = 1)
        assertEquals(1.5, summary.minutesSavedToday, 0.001)
        // Never negative, however slow.
        assertEquals(0.0, StatsSummary.of(listOf(stat(1, 1, audioMs = 60_000)), today = 1).minutesSavedTotal)
    }

    @Test
    fun `median wait per engine`() {
        val stats = listOf(stat(1, 1, waitMs = 100), stat(1, 1, waitMs = 900), stat(1, 1, waitMs = 300), stat(1, 1, engine = "tinfoil", waitMs = 5_000))
        val summary = StatsSummary.of(stats, today = 1)
        assertEquals(300, summary.medianWaitMs["local"])
        assertEquals(5_000, summary.medianWaitMs["tinfoil"])
    }

    @Test
    fun `server cost follows the provider's price`() {
        // Whisper is billed per request, whatever its length.
        val tinfoil = "https://inference.tinfoil.sh/v1"
        assertEquals(10_000L, ServerCost.microUsd(tinfoil, "whisper-large-v3-turbo", 15_000, 100, null, null))
        val voxtral = assertNotNull(ServerCost.microUsd(tinfoil, "voxtral-small-24b", 15_000, 200, null, null))
        assertTrue(voxtral in 30..100, "about \$0.00005 for 15 s, got $voxtral")
        assertNull(ServerCost.microUsd(tinfoil, null, 15_000, 200, null, null))
        assertNull(ServerCost.microUsd(tinfoil, "my-own-model", 15_000, 200, null, null))
        // OpenAI bills per minute: a minute of gpt-4o-mini-transcribe is $0.003.
        assertEquals(3_000L, ServerCost.microUsd("https://api.openai.com/v1", "gpt-4o-mini-transcribe", 60_000, 0, null, null))
        // A server of your own costs nothing we could know - even with a known model name.
        assertNull(ServerCost.microUsd("http://192.168.1.20:8000/v1", "whisper-large-v3-turbo", 60_000, 0, null, null))
    }

    @Test
    fun `the cleanup is priced by the server that cleaned up`() {
        val groq = "https://api.groq.com/openai/v1"
        val tinfoil = "https://inference.tinfoil.sh/v1"
        // Local recognition, Tinfoil cleanup: the cleanup still costs.
        val cleanupOnly = assertNotNull(ServerCost.microUsd(null, null, 60_000, 400, tinfoil, "deepseek-v4-1-flash"))
        assertTrue(cleanupOnly in 300..450, "about \$0.0004 for 400 characters, got $cleanupOnly")
        // Groq recognizes, Tinfoil cleans up: each at its own price.
        val speechOnly = assertNotNull(ServerCost.microUsd(groq, "whisper-large-v3-turbo", 60_000, 400, null, null))
        val both = assertNotNull(ServerCost.microUsd(groq, "whisper-large-v3-turbo", 60_000, 400, tinfoil, "deepseek-v4-1-flash"))
        assertTrue(both - (speechOnly + cleanupOnly) in -1..1, "$both vs $speechOnly + $cleanupOnly")
    }

    @Test
    fun `words are counted the way people count them`() {
        assertEquals(7, countWords("Ja, bis gleich - ich bin's um 8."))
        assertEquals(0, countWords("  … "))
    }
}
