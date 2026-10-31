package app.amethyst.core

import app.amethyst.core.commands.CommandOutcome
import app.amethyst.core.dictation.DictationLog
import app.amethyst.core.insert.InsertionStrategy
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.text.Transcript
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
        assertContains(record.summary(), "not inserted")
    }

    @Test
    fun `a command is kept with what was heard and what happened`() {
        val log = log()
        log.recordCommand("öffne WhatsApp", CommandOutcome.Done("Opened WhatsApp"), engineId = "parakeet")

        val record = log.last!!
        assertTrue(record.command)
        assertTrue(!record.failed)
        assertEquals("öffne WhatsApp", record.text)
        assertContains(record.summary(), "Command")
        assertContains(record.summary(), "Opened WhatsApp")
    }

    @Test
    fun `a command that could not be done counts as failed`() {
        val log = log()
        log.recordCommand("öffne Kalender", CommandOutcome.Failed("No app called “kalender”."), engineId = "parakeet")

        val record = log.last!!
        assertTrue(record.failed)
        assertContains(record.summary(), "No app called")
    }

    @Test
    fun `a recording for a command that failed says it was one`() {
        val log = log()
        log.recordFailure("Nothing heard", command = true)
        assertTrue(log.last!!.command)
        assertContains(log.last!!.summary(), "Command")
    }

    @Test
    fun `a command keeps its steps for Diagnostics`() {
        val log = log()
        val steps = listOf("normalized: \"scroll runter\"", "understood: Scroll DOWN", "stopped at step 1: end of list")
        log.recordCommand("Scroll runter", CommandOutcome.Failed("Nothing more to scroll"), engineId = "parakeet", details = steps)
        assertEquals(steps, log.last!!.details)
    }

    @Test
    fun `a command that failed before it was read keeps its details too`() {
        val log = log()
        log.recordFailure("Nothing heard", engineId = "parakeet", command = true, details = listOf("recording: no speech"))
        assertEquals(listOf("recording: no speech"), log.last!!.details)
    }

    @Test
    fun `a dictation has no details`() {
        val log = log()
        log.recordSuccess(transcript(), InsertionStrategy.SET_TEXT, true, "com.whatsapp")
        assertTrue(log.last!!.details.isEmpty())
    }

    @Test
    fun `commands and dictations share fifty places`() {
        val log = DictationLog { now }
        repeat(60) { i -> log.recordCommand("zurück $i", CommandOutcome.Done("Back"), engineId = "parakeet") }
        assertEquals(50, DictationLog.DEFAULT_CAPACITY)
        assertEquals(50, log.records.value.size)
        assertEquals("zurück 59", log.last!!.text)
    }

    @Test
    fun `clear empties the log`() {
        val log = log()
        log.recordFailure("weg damit")
        log.clear()
        assertTrue(log.records.value.isEmpty())
    }
}
