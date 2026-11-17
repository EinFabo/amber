package app.amethyst.core

import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.dictation.DictationLog
import app.amethyst.core.dictation.DictationMemory
import app.amethyst.core.dictation.KeptDictation
import app.amethyst.core.engine.Problem
import app.amethyst.core.insert.InsertionStrategy
import app.amethyst.core.insert.OwnClip
import app.amethyst.core.insert.UndoLast
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.text.Transcript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DictationMemoryTest {

    private var now = 1_000_000L
    private val memory = DictationMemory(KeptDictation({ now }), DictationLog { now })

    private fun dictate(text: String) = memory.log.recordSuccess(
        Transcript(text = text, engineId = "parakeet", trustLevel = TrustLevel.ON_DEVICE, latencyMs = 300),
        InsertionStrategy.COMMIT_TEXT, inserted = true, targetPackage = "com.whatsapp",
    )

    @Test
    fun `the last dictation comes from the records while it is kept`() {
        dictate("Bin gleich da")
        assertEquals("Bin gleich da", memory.sessionDictation()?.text)
    }

    @Test
    fun `wipe forgets every holder for real`() {
        dictate("Bin gleich da")
        memory.log.recordFailure("not inserted", text = "Noch ein Satz")
        memory.kept.keepRecording(PcmAudio(ShortArray(16_000) { 100 }), Problem.NoConnection("Groq"))
        memory.inserted(UndoLast.Insertion("com.whatsapp", "Bin gleich da"))
        var clipboard = 0
        var history = 0
        memory.hold { clipboard++ }
        memory.hold { history++ }

        memory.wipe()

        // Dropped, not hidden: the records hold no words any more.
        assertTrue(memory.log.records.value.isEmpty())
        assertNull(memory.kept.recording())
        assertNull(memory.sessionDictation())
        assertNull(memory.lastInsertion)
        assertEquals(1, clipboard)
        assertEquals(1, history)
    }

    @Test
    fun `a holder that fails does not keep the others from forgetting`() {
        var asked = 0
        memory.hold { error("clipboard refused") }
        memory.hold { asked++ }
        dictate("geheim")
        assertFailsWith<IllegalStateException> { memory.wipe() }
        assertEquals(1, asked)
        assertTrue(memory.log.records.value.isEmpty())
    }

    @Test
    fun `undo is offered until it was done`() {
        val insertion = UndoLast.Insertion("com.whatsapp", "Hallo")
        memory.inserted(insertion)
        assertEquals(insertion, memory.lastInsertion)
        memory.undone()
        assertNull(memory.lastInsertion)
    }

    @Test
    fun `the app knows its own clip by its mark, or by the label it had before the mark`() {
        assertTrue(OwnClip.isOurs("anything", marked = true))
        assertTrue(OwnClip.isOurs("Amber", marked = false))
        assertTrue(OwnClip.isOurs("Amber diagnostics", marked = false))
        assertFalse(OwnClip.isOurs("Amber notes", marked = false))
        assertFalse(OwnClip.isOurs(null, marked = false))
    }
}
