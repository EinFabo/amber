package app.nocturne.core

import app.nocturne.core.insert.Clipboard
import app.nocturne.core.insert.EditableTarget
import app.nocturne.core.insert.InsertionEngine
import app.nocturne.core.insert.InsertionStrategy
import app.nocturne.core.insert.StrategyMemory
import app.nocturne.core.text.FieldState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InsertionEngineTest {

    /**
     * A text field that can be told to misbehave the way real ones do.
     *
     * [setTextLies] is the case that matters most: the platform reports a successful write and
     * the field keeps its old contents. That is what WebViews and several cross-platform
     * toolkits do, and it is invisible without reading the field back.
     */
    private class FakeTarget(
        override val packageName: String = "com.whatsapp",
        override val isEditable: Boolean = true,
        initialText: String = "",
        private val setTextLies: Boolean = false,
        private val setTextRefuses: Boolean = false,
        private val pasteWorks: Boolean = true,
        private val unreadable: Boolean = false,
    ) : EditableTarget {
        var text: String = initialText
            private set
        var cursor: Int = initialText.length
            private set
        var clipboardContent: String? = null
        var setTextCalls = 0
        var pasteCalls = 0

        override fun readField(): FieldState? =
            if (unreadable) null else FieldState(text, cursor, cursor)

        override fun setText(text: String): Boolean {
            setTextCalls++
            if (setTextRefuses) return false
            if (!setTextLies) this.text = text
            return true
        }

        override fun setSelection(cursor: Int) {
            // Real fields clamp an out-of-range caret instead of throwing.
            this.cursor = cursor.coerceIn(0, text.length)
        }

        override fun paste(): Boolean {
            pasteCalls++
            if (!pasteWorks) return false
            val pasted = clipboardContent ?: return false
            val at = cursor.coerceIn(0, text.length)
            text = text.substring(0, at) + pasted + text.substring(at)
            cursor = at + pasted.length
            return true
        }

        override fun readBack(): String? = if (unreadable) null else text
    }

    private fun engine(target: FakeTarget?, memory: StrategyMemory = StrategyMemory()) =
        InsertionEngine(
            memory = memory,
            clipboard = Clipboard { text ->
                target?.clipboardContent = text
                true
            },
        )

    @Test
    fun `writes into a well behaved field with set text`() {
        val target = FakeTarget(initialText = "Guten Morgen")
        val outcome = engine(target).insert(target, "wie geht es dir")

        assertEquals(InsertionStrategy.SET_TEXT, outcome.strategy)
        assertTrue(outcome.insertedIntoField)
        assertEquals("Guten Morgen wie geht es dir", target.text)
        assertEquals(target.text.length, target.cursor)
    }

    @Test
    fun `falls through to paste when set text silently does nothing`() {
        val target = FakeTarget(setTextLies = true)
        val outcome = engine(target).insert(target, "Hallo Welt")

        assertEquals(InsertionStrategy.PASTE, outcome.strategy)
        assertTrue(outcome.insertedIntoField)
        assertTrue(outcome.copiedToClipboard)
        assertEquals(1, target.setTextCalls, "set text must be tried exactly once")
        assertEquals("Hallo Welt", target.text)
    }

    @Test
    fun `falls through when set text refuses outright`() {
        val target = FakeTarget(setTextRefuses = true)
        val outcome = engine(target).insert(target, "Hallo")

        assertEquals(InsertionStrategy.PASTE, outcome.strategy)
        assertTrue(outcome.insertedIntoField)
    }

    @Test
    fun `ends on the clipboard when nothing can write to the field`() {
        val target = FakeTarget(setTextLies = true, pasteWorks = false)
        val outcome = engine(target).insert(target, "Hallo")

        assertEquals(InsertionStrategy.CLIPBOARD_ONLY, outcome.strategy)
        assertFalse(outcome.insertedIntoField)
        assertTrue(outcome.copiedToClipboard, "the dictation must not be lost")
        assertEquals("Hallo", target.clipboardContent)
    }

    @Test
    fun `skips field methods when the focused node is not editable`() {
        val target = FakeTarget(isEditable = false)
        val outcome = engine(target).insert(target, "Hallo")

        assertEquals(InsertionStrategy.CLIPBOARD_ONLY, outcome.strategy)
        assertFalse(outcome.insertedIntoField)
        assertTrue(outcome.copiedToClipboard)
        assertEquals(0, target.setTextCalls)
        assertEquals(0, target.pasteCalls)
    }

    @Test
    fun `copies to the clipboard when nothing at all is focused`() {
        var copied: String? = null
        val engine = InsertionEngine(StrategyMemory(), Clipboard { copied = it; true })

        val outcome = engine.insert(null, "Hallo")

        assertEquals(InsertionStrategy.CLIPBOARD_ONLY, outcome.strategy)
        assertFalse(outcome.insertedIntoField)
        assertTrue(outcome.copiedToClipboard)
        assertEquals("Hallo", copied)
        assertEquals(null, outcome.targetPackage)
    }

    @Test
    fun `reports no clipboard either when even copying fails`() {
        val engine = InsertionEngine(StrategyMemory(), Clipboard { false })
        val outcome = engine.insert(null, "Hallo")

        assertFalse(outcome.insertedIntoField)
        assertFalse(outcome.copiedToClipboard)
    }

    @Test
    fun `treats an unreadable field as a failure instead of claiming success`() {
        val target = FakeTarget(unreadable = true)
        val outcome = engine(target).insert(target, "Hallo")

        assertEquals(InsertionStrategy.CLIPBOARD_ONLY, outcome.strategy)
        assertFalse(outcome.insertedIntoField)
    }

    @Test
    fun `remembers the working method and uses it first next time`() {
        val memory = StrategyMemory()
        val liar = FakeTarget(setTextLies = true)
        engine(liar, memory).insert(liar, "erster Versuch")

        assertEquals(InsertionStrategy.PASTE, memory.order("com.whatsapp").first())

        // Second dictation in the same app must not probe the broken method again.
        val again = FakeTarget(setTextLies = true)
        val outcome = engine(again, memory).insert(again, "zweiter Versuch")

        assertEquals(InsertionStrategy.PASTE, outcome.strategy)
        assertEquals(0, again.setTextCalls, "the known-broken method must be skipped")
    }

    @Test
    fun `forgets a remembered method once it stops working`() {
        val memory = StrategyMemory(mapOf("com.whatsapp" to InsertionStrategy.PASTE))
        val target = FakeTarget(pasteWorks = false)

        val outcome = engine(target, memory).insert(target, "Hallo")

        // Paste was preferred, failed, and SET_TEXT took over - so the preference is gone.
        assertEquals(InsertionStrategy.SET_TEXT, outcome.strategy)
        assertTrue(outcome.insertedIntoField)
        assertEquals(InsertionStrategy.SET_TEXT, memory.order("com.whatsapp").first())
    }

    @Test
    fun `persists the memory through the callback`() {
        var saved: Map<String, InsertionStrategy>? = null
        val target = FakeTarget()
        val engine = InsertionEngine(
            memory = StrategyMemory(),
            clipboard = Clipboard { true },
            onMemoryChanged = { saved = it.snapshot() },
        )

        engine.insert(target, "Hallo")

        assertEquals(mapOf("com.whatsapp" to InsertionStrategy.SET_TEXT), saved)
    }

    @Test
    fun `keeps each app's memory separate`() {
        val memory = StrategyMemory()
        val liar = FakeTarget(packageName = "com.webview.app", setTextLies = true)
        engine(liar, memory).insert(liar, "Hallo")

        val healthy = FakeTarget(packageName = "com.google.android.gm")
        val outcome = engine(healthy, memory).insert(healthy, "Hallo")

        assertEquals(InsertionStrategy.SET_TEXT, outcome.strategy, "Gmail must not inherit")
        assertEquals(InsertionStrategy.PASTE, memory.order("com.webview.app").first())
    }

    @Test
    fun `splices at the cursor rather than appending`() {
        val target = FakeTarget(initialText = "Hallo Welt")
        // Cursor sits after "Hallo".
        target.setSelection(5)
        val outcome = engine(target).insert(target, "liebe")

        assertTrue(outcome.insertedIntoField)
        assertEquals("Hallo liebe Welt", target.text)
    }
}
