package app.nocturne.core

import app.nocturne.core.dictation.DictationLog
import app.nocturne.core.insert.InsertionStrategy
import app.nocturne.core.privacy.TrustLevel
import app.nocturne.core.text.Transcript
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DictationLogTest {

    private var now = 1_000L
    private fun log(capacity: Int = 3) = DictationLog(capacity) { now }

    private fun transcript(text: String = "Hallo", latency: Long = 120) = Transcript(
        text = text,
        engineId = "tinfoil-whisper",
        trustLevel = TrustLevel.CLOUD,
        latencyMs = latency,
    )

    @Test
    fun `starts empty`() {
        val log = log()
        assertTrue(log.records.value.isEmpty())
        assertNull(log.last)
    }

    @Test
    fun `keeps the newest record first`() {
        val log = log()
        log.recordSuccess(transcript("eins"), InsertionStrategy.SET_TEXT, true, "com.whatsapp")
        log.recordSuccess(transcript("zwei"), InsertionStrategy.PASTE, true, "com.android.chrome")

        assertEquals("zwei", log.last?.text)
        assertEquals(listOf("zwei", "eins"), log.records.value.map { it.text })
    }

    @Test
    fun `drops the oldest beyond capacity`() {
        val log = log(capacity = 3)
        repeat(5) { i ->
            log.recordSuccess(transcript("nr$i"), InsertionStrategy.SET_TEXT, true, "com.app")
        }

        assertEquals(3, log.records.value.size)
        assertEquals(listOf("nr4", "nr3", "nr2"), log.records.value.map { it.text })
    }

    @Test
    fun `records a failure without a transcript`() {
        val log = log()
        log.recordFailure("Mikrofon belegt", engineId = "android-on-device")

        val record = log.last!!
        assertTrue(record.failed)
        assertEquals("", record.text)
        assertNull(record.latencyMs)
        assertNull(record.strategy)
        assertContains(record.summary(), "Mikrofon belegt")
    }

    @Test
    fun `stamps records with the clock`() {
        val log = log()
        now = 42_000L
        log.recordFailure("egal")
        assertEquals(42_000L, log.last?.atMillis)
    }

    @Test
    fun `summary names engine latency strategy and target app`() {
        val log = log()
        log.recordSuccess(transcript(latency = 345), InsertionStrategy.SET_TEXT, true, "com.whatsapp")

        val summary = log.last!!.summary()
        assertContains(summary, "tinfoil-whisper")
        assertContains(summary, "345 ms")
        assertContains(summary, "SET_TEXT")
        assertContains(summary, "com.whatsapp")
    }

    @Test
    fun `summary flags text that was recognised but never inserted`() {
        val log = log()
        log.recordSuccess(transcript(), InsertionStrategy.CLIPBOARD_ONLY, inserted = false, null)

        val record = log.last!!
        assertTrue(!record.failed, "a clipboard fallback is not an engine failure")
        assertContains(record.summary(), "nicht eingefügt")
    }

    @Test
    fun `clear empties the log`() {
        val log = log()
        log.recordFailure("weg damit")
        log.clear()
        assertTrue(log.records.value.isEmpty())
    }
}
