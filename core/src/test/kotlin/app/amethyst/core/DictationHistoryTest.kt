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

    private var now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val file = Files.createTempDirectory("history").resolve("entries").toFile()

    /** Stands in for the keystore key: [unlocked] false is a locked phone, which cannot use it. */
    private var unlocked = true
    private var key: SecretKey? = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val lock = AesGcmLock({ key?.takeIf { unlocked } }, { key = null })
    private val history = DictationHistory(file, lock) { now }

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
    fun `a day is 24 hours, a week 7 days`() {
        assertEquals(now - 24 * hour, HistoryRetention.keptFrom(now, HistoryKeep.DAY))
        assertEquals(now - 7 * 24 * hour, HistoryRetention.keptFrom(now, HistoryKeep.WEEK))
        assertEquals(Long.MAX_VALUE, HistoryRetention.keptFrom(now, HistoryKeep.OFF))
    }

    @Test
    fun `delete all takes the file and the key`() {
        history.add("geheim", null, HistoryKeep.DAY)
        history.deleteAll()
        assertFalse(file.exists())
        assertEquals(null, key)
        assertTrue(history.entries(HistoryKeep.DAY).isEmpty())
    }

    @Test
    fun `switched off, everything goes`() {
        history.add("geheim", null, HistoryKeep.DAY)
        history.prune(HistoryKeep.OFF)
        assertFalse(file.exists())
        assertEquals(null, key)
    }

    @Test
    fun `a line moved to another time cannot be opened`() {
        history.add("Hallo", null, HistoryKeep.DAY)
        val line = file.readText()
        file.writeText(line.replaceFirst("$now ", "${now + 1} "))
        assertTrue(history.entries(HistoryKeep.DAY).isEmpty())
    }

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
