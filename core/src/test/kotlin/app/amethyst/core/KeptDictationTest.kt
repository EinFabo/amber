package app.amethyst.core

import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.dictation.KeptDictation
import app.amethyst.core.engine.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KeptDictationTest {

    private var now = 1_000_000L
    private val kept = KeptDictation({ now })

    /** [seconds] of 16 kHz mono. */
    private fun audio(seconds: Double) = PcmAudio(ShortArray((seconds * 16_000).toInt()) { 100 })

    @Test
    fun `a recording whose recognition failed is kept for ten minutes`() {
        val audio = audio(3.0)
        assertTrue(kept.keepRecording(audio, Problem.NoConnection("Groq")))
        assertSame(audio, kept.recording())
        now += KeptDictation.KEEP_MS - 1
        assertSame(audio, kept.recording())
        assertEquals(now + 1, kept.expiresAt())
        now += 1
        assertNull(kept.recording())
        assertNull(kept.expiresAt())
    }

    @Test
    fun `expire gives the memory back without anyone asking`() {
        kept.keepRecording(audio(1.0), Problem.KeyRejected)
        now += KeptDictation.KEEP_MS
        kept.expire()
        assertNull(kept.expiresAt())
    }

    @Test
    fun `every failure but nothing heard is worth trying again`() {
        for (why in listOf(
            Problem.NoConnection("Groq"), Problem.NoAnswerInTime("Groq"), Problem.KeyRejected, Problem.NoCredit("Groq"),
            Problem.ServerError(500, "Groq"), Problem.ServiceDown(503), Problem.RateLimited, Problem.OutOfMemory, null,
        )) {
            assertTrue(KeptDictation.worthTryingAgain(why), why.toString())
        }
        assertFalse(KeptDictation.worthTryingAgain(Problem.NothingRecognized))
        assertFalse(KeptDictation.worthTryingAgain(Problem.NoSpeechHeard))
        assertFalse(kept.keepRecording(audio(2.0), Problem.NothingRecognized))
        assertNull(kept.recording())
    }

    @Test
    fun `only the last one is kept`() {
        val first = audio(1.0)
        val second = audio(2.0)
        kept.keepRecording(first, Problem.KeyRejected)
        now += 1_000
        kept.keepRecording(second, null)
        assertSame(second, kept.recording())
        assertEquals(now + KeptDictation.KEEP_MS, kept.expiresAt())
    }

    @Test
    fun `a recording longer than five and a half minutes is not kept, nor an empty one`() {
        assertFalse(kept.keepRecording(audio(KeptDictation.MAX_RECORDING_MS / 1000.0 + 1), Problem.KeyRejected))
        assertFalse(kept.keepRecording(PcmAudio(ShortArray(0)), Problem.KeyRejected))
        assertTrue(kept.keepRecording(audio(300.0), Problem.KeyRejected))
    }

    @Test
    fun `a dictation whose words came drops it`() {
        kept.keepRecording(audio(1.0), Problem.KeyRejected)
        kept.textRecognized()
        assertNull(kept.recording())
        assertFalse(kept.hasRecording)
    }

    @Test
    fun `clear forgets it`() {
        kept.keepRecording(audio(1.0), Problem.KeyRejected)
        kept.clear()
        assertNull(kept.recording())
    }

    @Test
    fun `keeping off forgets it at once and keeps nothing until it is on again`() {
        kept.keepRecording(audio(1.0), Problem.KeyRejected)
        kept.keeping = false
        assertNull(kept.recording())
        assertFalse(kept.keepRecording(audio(1.0), Problem.KeyRejected))
        assertNull(kept.recording())
        kept.keeping = true
        assertTrue(kept.keepRecording(audio(1.0), Problem.KeyRejected))
    }

    @Test
    fun `every change is counted for a screen that shows it`() {
        val before = kept.changes.value
        kept.keepRecording(audio(1.0), Problem.KeyRejected)
        kept.textRecognized()
        assertEquals(before + 2, kept.changes.value)
        // Nothing kept: nothing changes.
        kept.textRecognized()
        assertEquals(before + 2, kept.changes.value)
    }
}
