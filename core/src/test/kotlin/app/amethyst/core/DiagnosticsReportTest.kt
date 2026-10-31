package app.amethyst.core

import app.amethyst.core.commands.CommandOutcome
import app.amethyst.core.dictation.DiagnosticsReport
import app.amethyst.core.dictation.DictationLog
import app.amethyst.core.dictation.DictationRecord
import app.amethyst.core.insert.InsertionStrategy
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.text.Transcript
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsReportTest {

    /** 2026-10-31 14:02:31 UTC. */
    private val at = 1_793_455_351_000L
    private var now = at
    private val log = DictationLog { now }

    private fun records(): List<DictationRecord> {
        now = at - 65_000
        log.recordSuccess(
            Transcript("Bin gleich da", engineId = "parakeet", trustLevel = TrustLevel.ON_DEVICE, latencyMs = 640),
            InsertionStrategy.SET_TEXT, inserted = true, targetPackage = "com.whatsapp",
            timing = "route BUBBLE/RECORD_NOW · recording 1.2 s",
        )
        now = at
        log.recordCommand(
            "Öffne Kalender", CommandOutcome.Failed("No app called “kalender”."), engineId = "parakeet",
            timing = "route BUBBLE/RECORD_NOW · recording 0.9 s",
            details = listOf("normalized: \"offne kalender\"", "understood: OpenApp \"kalender\"", "app search: NotFound among 84 apps"),
        )
        return log.records.value
    }

    private fun report(records: List<DictationRecord>, keyboard: List<String> = emptyList()) =
        DiagnosticsReport.of(records, ZoneOffset.UTC, title = "Amber 0.21.0", copiedAt = at + 60_000, keyboard = keyboard)

    @Test
    fun `each record with its time, summary, words, steps and timing, newest first`() {
        assertEquals(
            """
            Amber 0.21.0 - diagnostics copied 2026-10-31 14:03:31 - 2 records, newest first

            14:02:31  Command · parakeet · No app called “kalender”.
              heard: Öffne Kalender
              normalized: "offne kalender"
              understood: OpenApp "kalender"
              app search: NotFound among 84 apps
              timing: route BUBBLE/RECORD_NOW · recording 0.9 s

            14:01:26  parakeet · 640 ms · SET_TEXT · com.whatsapp
              text: Bin gleich da
              timing: route BUBBLE/RECORD_NOW · recording 1.2 s
            """.trimIndent() + "\n",
            report(records()),
        )
    }

    @Test
    fun `a command that failed before anything was heard has no words line`() {
        now = at
        log.recordFailure("Nothing heard - say the command again.", engineId = "parakeet", command = true, details = listOf("recording: no speech"))
        val text = report(log.records.value)
        assertTrue("14:02:31  Command · parakeet · Nothing heard - say the command again.\n  recording: no speech\n" in text, text)
        assertFalse("heard:" in text, text)
    }

    @Test
    fun `nothing to copy says so`() {
        assertEquals("Amber 0.21.0 - diagnostics copied 2026-10-31 14:03:31 - no records\n", report(emptyList()))
    }

    @Test
    fun `the keyboard lines come after the records`() {
        val text = report(emptyList(), keyboard = listOf("12:00:01 keyboard at 1466", "12:00:02 keyboard closed"))
        assertTrue(text.endsWith("\nKeyboard\n  12:00:01 keyboard at 1466\n  12:00:02 keyboard closed\n"), text)
    }

    @Test
    fun `the filter shows dictations, commands or both`() {
        val all = records()
        assertEquals(all, DiagnosticsReport.Filter.ALL.apply(all))
        assertEquals(listOf("Öffne Kalender"), DiagnosticsReport.Filter.COMMANDS.apply(all).map { it.text })
        assertEquals(listOf("Bin gleich da"), DiagnosticsReport.Filter.DICTATIONS.apply(all).map { it.text })
    }
}
