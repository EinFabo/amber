package app.amethyst.core

import app.amethyst.core.commands.CommandOutcome
import app.amethyst.core.dictation.Anonymize
import app.amethyst.core.dictation.DiagnosticsReport
import app.amethyst.core.dictation.DictationLog
import app.amethyst.core.dictation.TextShape
import app.amethyst.core.insert.InsertionStrategy
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.text.Transcript
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnonymousDiagnosticsTest {

    private val secret = "Treffpunkt Hauptbahnhof Gleis neun"
    private var now = 1_793_455_351_000L
    private val log = DictationLog { now++ }

    /** A record in every shape there is, the secret in every field that can carry text. */
    private fun fill() {
        // Inserted: the words, the insertion's note and the timing.
        log.recordSuccess(
            Transcript(secret, engineId = "groq", trustLevel = TrustLevel.CLOUD, latencyMs = 900),
            InsertionStrategy.SET_TEXT, inserted = true, targetPackage = "com.whatsapp",
            note = "focus: EditText in com.whatsapp → SET_TEXT, $secret",
            timing = "route BUBBLE/RECORD_NOW · recording 1.2 s · cleanup 300 ms (llama, de) · $secret",
        )
        // Recognized, not inserted: the words kept, an error with a server's answer and an address with its key.
        log.recordFailure(
            "Unexpected error: https://pc.example:8443/key-abc123/v1/audio answered HTTP 400: {\"error\": \"could not read '$secret'\"}",
            engineId = "amber-pc",
            timing = "route BUBBLE/RECORD_NOW · recording 2.0 s",
            text = "$secret\nund dann weiter",
        )
        // No words in the record, but in the error: what whisper made of silence.
        log.recordFailure("whisper returned no speech: \"$secret\"", engineId = "whisper")
        // A command: what was heard, its outcome quoting it, its steps.
        log.recordCommand(
            secret, CommandOutcome.Failed("Not a command: “$secret”. Try “open WhatsApp” or “go back”."),
            engineId = "parakeet",
            timing = "route BUBBLE/RECORD_NOW · recording 0.9 s",
            details = listOf(
                "normalized: \"${secret.lowercase()}\"",
                "words: ${secret.lowercase()}",
                "part 1: “${secret.lowercase()}” → OpenApp \"${secret.lowercase()}\"",
                "a count the rules cannot read: “$secret” - counts go from one to twenty",
                "not understood: no rule matches",
                "outcome: failed",
            ),
        )
        // A command that failed before its words were kept: a server's answer cut off in a quote.
        log.recordFailure(
            "Recognition failed.", engineId = "groq", command = true,
            details = listOf("recognition failed: HTTP 500: {\"detail\": \"${secret.take(20)}"),
        )
    }

    private fun copy() = DiagnosticsReport.of(
        log.records.value, ZoneOffset.UTC, title = "Amber 0.31.0", copiedAt = now,
        keyboard = listOf("+120 ms  keyboard settled at 1466"),
        warning = "Amber (debug) is also on - switch one off.",
    )

    @Test
    fun `the copy holds no word of what was said, wherever it was`() {
        fill()
        val text = copy()
        assertFalse(secret.lowercase() in text.lowercase(), text)
        for (word in secret.split(" ")) assertFalse(word.lowercase() in text.lowercase(), "\"$word\" in:\n$text")
        assertFalse("weiter" in text, text)
        assertFalse("key-abc123" in text, text)
    }

    @Test
    fun `apps, timings, reasons and hosts stay`() {
        fill()
        val text = copy()
        for (kept in listOf(
            "com.whatsapp", "recording 1.2 s", "cleanup 300 ms (llama, de)", "https://pc.example:8443/…",
            "HTTP 400: answer of", "HTTP 500: answer of", "not understood: no rule matches", "outcome: failed",
            "text: 34 characters, 4 words, 1 line", "text: 50 characters, 7 words, 2 lines", "heard: 34 characters, 4 words, 1 line",
            "words: 4", "keyboard settled at 1466", "Note: Amber (debug) is also on - switch one off.",
        )) {
            assertTrue(kept in text, "\"$kept\" missing in:\n$text")
        }
    }

    @Test
    fun `on the phone the record keeps its words`() {
        fill()
        // Insert again and Copy last read the record as it was.
        assertEquals("$secret\nund dann weiter", log.lastDictation?.text)
        val command = log.records.value.first { it.command && it.text.isNotBlank() }
        assertTrue(DiagnosticsReport.lines(command).any { secret.lowercase() in it }, "the steps as they were, for Show text")
        val shown = DiagnosticsReport.anonymous(command)
        assertEquals(TextShape(34, 4, 1, null), shown.shape)
        assertFalse(shown.lines.any { "treffpunkt" in it.lowercase() }, shown.lines.toString())
        assertFalse("Treffpunkt" in shown.summary, shown.summary)
    }

    @Test
    fun `a text's shape counts characters, words and lines, and names a clear language`() {
        assertEquals(
            TextShape(characters = 43, words = 8, lines = 4, language = "de"),
            TextShape.of("Hallo,\nwie geht es dir heute?\n\nBis morgen 👋\n"),
        )
        assertEquals("43 characters, 8 words, 4 lines, de", TextShape.of("Hallo,\nwie geht es dir heute?\n\nBis morgen 👋").describe())
        assertEquals("1 character, 1 word, 1 line", TextShape.of("a").describe())
        assertEquals(TextShape(14, 2, 1, null), TextShape.of("Öffne Kalender"))
    }

    @Test
    fun `a line keeps what is not said words`() {
        val texts = listOf("one point two seconds later")
        // Numbers alone are no phrase: a timing stays a timing.
        assertEquals("recording 1.2 s · recognition 640 ms", Anonymize.line("recording 1.2 s · recognition 640 ms", listOf("1 2 s")))
        assertEquals("rejected: negation dropped, in=40 out=38", Anonymize.line("rejected: negation dropped, in=40 out=38", texts))
        assertEquals("app search: … among 84 apps", Anonymize.line("app search: point two among 84 apps", texts))
        assertEquals("No app called “…”.", Anonymize.line("No app called “kalender”."))
        assertEquals("the run's time limit of 300000 ms ran out", Anonymize.line("the run's time limit of 300000 ms ran out"))
        assertEquals("  words: (none)", Anonymize.line("  words: (none)"))
    }
}
