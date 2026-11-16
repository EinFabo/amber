package app.amethyst.core

import app.amethyst.core.history.AesGcmLock
import app.amethyst.core.history.DictationHistory
import app.amethyst.core.history.HistoryEntry
import app.amethyst.core.history.HistoryKeep
import app.amethyst.core.history.HistoryRetention
import app.amethyst.core.privacy.SecurityMode
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.PrivacySettings
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DictationHistoryTest {

    private val hour = 3_600_000L
    private val day = HistoryRetention.DAY_MS

    /** Noon of a day, so an hour or two either way stays on it. */
    private var now = 20_800 * day + 12 * hour
    private val file = Files.createTempDirectory("history").resolve("entries").toFile()

    /** Stand in for the keystore keys, one per day: [unlocked] false is a locked phone, which cannot use them. */
    private var unlocked = true
    private val keys = mutableMapOf<Long, SecretKey>()
    private val lock = AesGcmLock(
        key = { d, create -> if (!unlocked) null else keys[d] ?: if (create) newKey().also { keys[d] = it } else null },
        days = { keys.keys.toSet() },
        destroyKey = { d -> keys.remove(d) },
    )
    private val history = DictationHistory(file, lock) { now }

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `a dictation is kept with its app and time, newest first`() {
        assertTrue(history.add("Bin gleich da", "com.whatsapp", HistoryKeep.DAY))
        now += 60_000
        assertTrue(history.add("Einkaufsliste: Milch, Brot", null, HistoryKeep.DAY))
        assertEquals(
            listOf(HistoryEntry(now, "Einkaufsliste: Milch, Brot", null), HistoryEntry(now - 60_000, "Bin gleich da", "com.whatsapp")),
            history.entries(HistoryKeep.DAY),
        )
        assertEquals(2, history.count(HistoryKeep.DAY))
    }

    @Test
    fun `on the disk the words and the app are sealed`() {
        history.add("Treffen wir uns um acht", "com.whatsapp", HistoryKeep.DAY)
        val stored = file.readText()
        assertFalse("acht" in stored)
        assertFalse("whatsapp" in stored)
        assertTrue(stored.startsWith("$now "))
    }

    @Test
    fun `nothing is written while the phone is locked, and nothing is read`() {
        history.add("vorher", null, HistoryKeep.DAY)
        unlocked = false
        assertFalse(history.add("gesperrt", null, HistoryKeep.DAY))
        assertTrue(history.entries(HistoryKeep.DAY).isEmpty())
        unlocked = true
        assertEquals(listOf("vorher"), history.entries(HistoryKeep.DAY).map { it.text })
    }

    @Test
    fun `off, or nothing said, keeps nothing`() {
        assertFalse(history.add("Hallo", null, HistoryKeep.OFF))
        assertFalse(history.add("  ", null, HistoryKeep.DAY))
        assertFalse(file.exists())
    }

    @Test
    fun `what is past its time goes when the history is read - without the key`() {
        history.add("alt", null, HistoryKeep.WEEK)
        now += 25 * hour
        history.add("neu", null, HistoryKeep.WEEK)
        assertEquals(listOf("neu", "alt"), history.entries(HistoryKeep.WEEK).map { it.text })
        // A shorter choice deletes the older one from the file, even with the phone locked.
        unlocked = false
        history.prune(HistoryKeep.DAY)
        unlocked = true
        assertEquals(listOf("neu"), history.entries(HistoryKeep.DAY).map { it.text })
        assertEquals(1, file.readLines().size)
        now += 24 * hour
        assertTrue(history.entries(HistoryKeep.DAY).isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun `adding and counting delete what is past its time too`() {
        history.add("alt", null, HistoryKeep.DAY)
        now += 25 * hour
        // A process that lives for weeks never opens the page: the next dictation prunes.
        history.add("neu", null, HistoryKeep.DAY)
        assertEquals(1, file.readLines().size)
        now += 25 * hour
        // So does Home's count.
        assertEquals(0, history.count(HistoryKeep.DAY))
        assertFalse(file.exists())
    }

    @Test
    fun `each day has its own key, destroyed once the whole day is past keeping`() {
        history.add("gestern", null, HistoryKeep.DAY)
        val yesterday = HistoryRetention.day(now)
        now += day
        history.add("heute", null, HistoryKeep.DAY)
        assertEquals(setOf(yesterday, yesterday + 1), keys.keys)
        // Yesterday noon's dictation is gone, yesterday's afternoon not yet past: its key stays.
        now += hour
        history.prune(HistoryKeep.DAY)
        assertEquals(listOf("heute"), history.entries(HistoryKeep.DAY).map { it.text })
        assertEquals(setOf(yesterday, yesterday + 1), keys.keys)
        // At midnight all of yesterday is more than 24 hours ago: its key goes, also while locked.
        now = (yesterday + 2) * day
        unlocked = false
        history.prune(HistoryKeep.DAY)
        assertEquals(setOf(yesterday + 1), keys.keys)
    }

    @Test
    fun `a day's words cannot be read once its key is destroyed, even if the line is still there`() {
        history.add("geheim", null, HistoryKeep.DAY)
        val copy = file.readText()
        now += 2 * day
        history.prune(HistoryKeep.DAY)
        assertTrue(keys.isEmpty())
        // What a deleted file may leave on the flash, put back.
        file.writeText(copy)
        now -= 2 * day
        assertTrue(history.entries(HistoryKeep.DAY).isEmpty())
    }

    @Test
    fun `a clock set back does not bring back what is past its time`() {
        history.add("alt", null, HistoryKeep.WEEK)
        now += 30 * hour
        history.add("neu", null, HistoryKeep.WEEK)
        // Down to a day, read with the clock set back to just after the old one: "now" is the
        // newest dictation kept, so the old one is past its time all the same.
        now -= 29 * hour
        assertEquals(listOf("neu"), history.entries(HistoryKeep.DAY).map { it.text })
        assertEquals(1, file.readLines().size)
        // Nor can a dictation be added further back than what is kept.
        assertFalse(history.add("zurückdatiert", null, HistoryKeep.DAY))
        assertEquals(1, history.count(HistoryKeep.DAY))
    }

    @Test
    fun `without a dictation kept, the newest day with a key still stands for now`() {
        assertEquals(5 * day, HistoryRetention.now(clock = day, newest = null, newestKeyDay = 5))
        assertEquals(5 * day + 7, HistoryRetention.now(clock = day, newest = 5 * day + 7, newestKeyDay = 5))
        assertEquals(9 * day, HistoryRetention.now(clock = 9 * day, newest = 5 * day, newestKeyDay = 5))
        assertEquals(day, HistoryRetention.now(clock = day, newest = null, newestKeyDay = null))
    }

    @Test
    fun `a day is past keeping only when its last moment is`() {
        val from = 10 * day + 1
        assertTrue(HistoryRetention.dayPast(9, from))
        assertFalse(HistoryRetention.dayPast(10, from))
        assertFalse(HistoryRetention.dayPast(9, 10 * day - 2))
        assertEquals(-1, HistoryRetention.day(-1))
    }

    @Test
    fun `a day is 24 hours, a week 7 days`() {
        assertEquals(now - 24 * hour, HistoryRetention.keptFrom(now, HistoryKeep.DAY))
        assertEquals(now - 7 * 24 * hour, HistoryRetention.keptFrom(now, HistoryKeep.WEEK))
        assertEquals(Long.MAX_VALUE, HistoryRetention.keptFrom(now, HistoryKeep.OFF))
    }

    @Test
    fun `delete all takes the file and every key`() {
        history.add("geheim", null, HistoryKeep.WEEK)
        now += day
        history.add("auch geheim", null, HistoryKeep.WEEK)
        history.deleteAll()
        assertFalse(file.exists())
        assertTrue(keys.isEmpty())
        assertTrue(history.entries(HistoryKeep.DAY).isEmpty())
    }

    @Test
    fun `switched off, everything goes`() {
        history.add("geheim", null, HistoryKeep.DAY)
        history.prune(HistoryKeep.OFF)
        assertFalse(file.exists())
        assertTrue(keys.isEmpty())
    }

    @Test
    fun `a line moved to another time cannot be opened`() {
        history.add("Hallo", null, HistoryKeep.DAY)
        val line = file.readText()
        file.writeText(line.replaceFirst("$now ", "${now + 1} "))
        assertTrue(history.entries(HistoryKeep.DAY).isEmpty())
    }

    /** Named as Android's: the lock knows it by its name. */
    private class KeyPermanentlyInvalidatedException : java.security.InvalidKeyException()

    @Test
    fun `a key unusable for good takes the history with it, and it starts over`() {
        history.add("vorher", null, HistoryKeep.DAY)
        val broken = lockThrowing(KeyPermanentlyInvalidatedException())
        val brokenHistory = DictationHistory(file, broken) { now }
        assertTrue(brokenHistory.entries(HistoryKeep.DAY).isEmpty())
        assertFalse(file.exists())
        assertTrue(keys.isEmpty())
    }

    @Test
    fun `an unusable key found while adding starts the history over with the new dictation`() {
        history.add("vorher", null, HistoryKeep.DAY)
        var throwOnce = true
        val flaky = AesGcmLock(
            key = { d, create ->
                if (throwOnce) {
                    throwOnce = false
                    throw KeyPermanentlyInvalidatedException()
                }
                keys[d] ?: if (create) newKey().also { keys[d] = it } else null
            },
            days = { keys.keys.toSet() },
            destroyKey = { d -> keys.remove(d) },
        )
        val flakyHistory = DictationHistory(file, flaky) { now }
        assertTrue(flakyHistory.add("neu", null, HistoryKeep.DAY))
        assertEquals(listOf("neu"), flakyHistory.entries(HistoryKeep.DAY).map { it.text })
    }

    @Test
    fun `when not one line opens while the keys work, the history starts over`() {
        history.add("vorher", null, HistoryKeep.DAY)
        // The key is gone - not by the history's doing - and the line can never be opened.
        keys.clear()
        assertEquals(1, history.count(HistoryKeep.DAY))
        assertTrue(history.entries(HistoryKeep.DAY).isEmpty())
        assertFalse(file.exists())
        assertEquals(0, history.count(HistoryKeep.DAY))
        assertTrue(keys.isEmpty())
    }

    @Test
    fun `a locked phone deletes nothing it cannot open`() {
        history.add("vorher", null, HistoryKeep.DAY)
        unlocked = false
        assertTrue(history.entries(HistoryKeep.DAY, unlocked = false).isEmpty())
        // Even told it is unlocked: today's key cannot be used either, so nothing is broken.
        assertTrue(history.entries(HistoryKeep.DAY).isEmpty())
        unlocked = true
        assertEquals(listOf("vorher"), history.entries(HistoryKeep.DAY).map { it.text })
    }

    @Test
    fun `a dictation decided on before delete all is not added after it`() {
        val ticket = history.ticket()
        history.deleteAll()
        assertFalse(history.add("zu spät", null, ticket) { HistoryKeep.DAY })
        assertFalse(file.exists())
        assertTrue(keys.isEmpty())
        assertTrue(history.add("danach", null, history.ticket()) { HistoryKeep.DAY })
    }

    @Test
    fun `how long to keep is asked as the line is written`() {
        // The security mode went on between the dictation and its add.
        assertFalse(history.add("Hallo", null, history.ticket()) { HistoryKeep.OFF })
        assertFalse(file.exists())
    }

    private fun lockThrowing(e: Exception) = AesGcmLock(
        key = { _, _ -> throw e },
        days = { keys.keys.toSet() },
        destroyKey = { d -> keys.remove(d) },
    )

    @Test
    fun `the security mode keeps no history, whatever the choice`() {
        val settings = AmethystSettings.defaults(listOf("de-DE"))
        assertEquals(HistoryKeep.DAY, SecurityMode.historyKeep(settings))
        assertEquals(HistoryKeep.OFF, SecurityMode.historyKeep(settings.copy(privacy = PrivacySettings(securityMode = true))))
        assertEquals(HistoryKeep.OFF, SecurityMode.historyKeep(settings.copy(privacy = PrivacySettings(history = HistoryKeep.OFF))))
    }

    @Test
    fun `absent from older settings, the history keeps a day`() {
        val decoded = app.amethyst.core.settings.SettingsCodec.decode("""{"schemaVersion":14}""", listOf("de-DE"))
        assertEquals(HistoryKeep.DAY, decoded.privacy.history)
    }
}
