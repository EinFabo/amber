package app.amethyst.core

import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.dictation.DictationRecord
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
        kept.keepRecording(audio(1.0), Problem.KeyRejected("Groq"))
        now += KeptDictation.KEEP_MS
        kept.expire()
        assertNull(kept.expiresAt())
    }

    @Test
    fun `a failure that goes away by itself or by a fix in Settings is worth trying again`() {
        for (why in listOf(
            Problem.NoConnection("Groq"), Problem.NoAnswerInTime("Groq"), Problem.KeyRejected("Groq"), Problem.NoApiKey("Groq"),
            Problem.NoCredit("Groq"), Problem.ServerError(500, "Groq"), Problem.ServiceDown(503), Problem.RateLimited,
            Problem.ModelNotOnServer("whisper-large-v3", "Groq"), Problem.AddressNotFound, Problem.AnswerUnreadable("Groq"), null,
        )) {
            assertTrue(KeptDictation.worthTryingAgain(why), why.toString())
        }
    }

    @Test
    fun `a failure the same recording would meet again is not`() {
        for (why in listOf(
            Problem.NothingRecognized, Problem.NoSpeechHeard, Problem.RecordingTooLong, Problem.AudioFormatRefused,
            Problem.ProcessorTooOld, Problem.UnencryptedRefused, Problem.EnclaveOtherAddress, Problem.EnclaveCannotBind,
            Problem.NotToVerifiedEnclave(Problem.Payload.RECORDING), Problem.EnclaveNotVerified("Tinfoil", Problem.Payload.RECORDING),
            Problem.EnclaveKeyMismatch("Tinfoil", Problem.Payload.RECORDING), Problem.RecordsByItself("Android"),
            Problem.OutOfMemory, Problem.LocalModelFailed, Problem.NotSupported,
        )) {
            assertFalse(KeptDictation.worthTryingAgain(why), why.toString())
        }
        assertFalse(kept.keepRecording(audio(2.0), Problem.NothingRecognized))
        assertNull(kept.recording())
    }

    @Test
    fun `the toast is the reason and Try again, except after a refused key, whose toast names its fix first`() {
        assertTrue(KeptDictation.toastSaysTryAgain(Problem.NoConnection("Groq")))
        assertTrue(KeptDictation.toastSaysTryAgain(null))
        assertFalse(KeptDictation.toastSaysTryAgain(Problem.KeyRejected("Groq")))
        assertTrue(KeptDictation.worthTryingAgain(Problem.KeyRejected("Groq")), "the recording is still kept")
        assertFalse(KeptDictation.toastSaysTryAgain(Problem.NothingRecognized))
    }

    @Test
    fun `a retry that fails for good leaves the kept recording and its time as they were`() {
        val audio = audio(2.0)
        kept.keepRecording(audio, Problem.NoConnection("Groq"))
        val expires = kept.expiresAt()
        now += 60_000
        // "Try again" with Android's recognizer, which records by itself: not kept anew.
        assertFalse(kept.keepRecording(audio, Problem.RecordsByItself("Android")))
        assertSame(audio, kept.recording())
        assertEquals(expires, kept.expiresAt())
    }

    @Test
    fun `only the last one is kept`() {
        val first = audio(1.0)
        val second = audio(2.0)
        kept.keepRecording(first, Problem.KeyRejected("Groq"))
        now += 1_000
        kept.keepRecording(second, null)
        assertSame(second, kept.recording())
        assertEquals(now + KeptDictation.KEEP_MS, kept.expiresAt())
    }

    @Test
    fun `a recording longer than five and a half minutes is not kept, nor an empty one`() {
        assertFalse(kept.keepRecording(audio(KeptDictation.MAX_RECORDING_MS / 1000.0 + 1), Problem.KeyRejected("Groq")))
        assertFalse(kept.keepRecording(PcmAudio(ShortArray(0)), Problem.KeyRejected("Groq")))
        assertTrue(kept.keepRecording(audio(300.0), Problem.KeyRejected("Groq")))
    }

    @Test
    fun `a dictation whose words came drops it`() {
        kept.keepRecording(audio(1.0), Problem.KeyRejected("Groq"))
        kept.textRecognized()
        assertNull(kept.recording())
        assertFalse(kept.hasRecording)
    }

    @Test
    fun `clear forgets it`() {
        kept.keepRecording(audio(1.0), Problem.KeyRejected("Groq"))
        kept.clear()
        assertNull(kept.recording())
    }

    @Test
    fun `keeping off forgets it at once and keeps nothing until it is on again`() {
        kept.keepRecording(audio(1.0), Problem.KeyRejected("Groq"))
        kept.keeping = false
        assertNull(kept.recording())
        assertFalse(kept.keepRecording(audio(1.0), Problem.KeyRejected("Groq")))
        assertNull(kept.recording())
        kept.keeping = true
        assertTrue(kept.keepRecording(audio(1.0), Problem.KeyRejected("Groq")))
    }

    private fun dictation(text: String, at: Long, failed: Boolean = false) = DictationRecord(
        text = text, engineId = "parakeet", trustLevel = null, latencyMs = null, strategy = null,
        inserted = !failed, targetPackage = null, error = if (failed) "not inserted" else null, atMillis = at,
    )

    @Test
    fun `Home offers the last dictation for the same ten minutes, also one that did not go in`() {
        val records = listOf(dictation("Bin gleich da", at = now, failed = true), dictation("older", at = now - 5_000))
        assertEquals("Bin gleich da", kept.lastDictation(records)?.text)
        now += KeptDictation.KEEP_MS - 1
        assertEquals("Bin gleich da", kept.lastDictation(records)?.text)
        now += 1
        assertNull(kept.lastDictation(records))
        // The quick menu still has it for the session.
        assertEquals("Bin gleich da", kept.sessionDictation(records)?.text)
    }

    @Test
    fun `a failure without words or a command is no last dictation`() {
        val command = dictation("open WhatsApp", at = now).copy(command = true)
        val noWords = dictation("", at = now, failed = true)
        assertNull(kept.lastDictation(listOf(command, noWords)))
    }

    @Test
    fun `clearing forgets the last dictation, and keeping off hides it, a newer one is offered again`() {
        val records = listOf(dictation("secret", at = now))
        kept.clear()
        assertNull(kept.lastDictation(records))
        assertNull(kept.sessionDictation(records))
        now += 1_000
        val newer = listOf(dictation("new", at = now)) + records
        assertEquals("new", kept.lastDictation(newer)?.text)
        kept.keeping = false
        assertNull(kept.lastDictation(newer))
        assertNull(kept.sessionDictation(newer))
    }

    @Test
    fun `every change is counted for a screen that shows it`() {
        val before = kept.changes.value
        kept.keepRecording(audio(1.0), Problem.KeyRejected("Groq"))
        kept.textRecognized()
        assertEquals(before + 2, kept.changes.value)
        // Nothing kept: nothing changes.
        kept.textRecognized()
        assertEquals(before + 2, kept.changes.value)
    }
}
