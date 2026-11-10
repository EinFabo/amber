package app.amethyst.core

import app.amethyst.core.insert.Clipboard
import app.amethyst.core.insert.EditableTarget
import app.amethyst.core.insert.InsertionEngine
import app.amethyst.core.insert.InsertionStrategy
import app.amethyst.core.insert.StrategyMemory
import app.amethyst.core.text.FieldState
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
        private val hasInputConnection: Boolean = false,
        /** The connection accepts the words and they never arrive: it went stale. */
        private val commitLost: Boolean = false,
        override val takesLineBreaks: Boolean = false,
        /** Reads after a write that still show the old text, like a Compose field mid-frame. */
        private val readLag: Int = 0,
        /** Reads after a commit that still show the old text: an app slow to take in a long text. */
        private val commitLag: Int = 0,
        /** What the field makes of the words it is given: emoji, line breaks, a length limit. */
        private val reformat: (String) -> String = { it },
    ) : EditableTarget {
        private var staleText: String? = null
        private var staleReadsLeft = 0
        var text: String = initialText
            private set
        var cursor: Int = initialText.length
            private set

        /** The other end of the selection; equal to [cursor] when nothing is selected. */
        private var anchor: Int = initialText.length
        var clipboardContent: String? = null
        var setTextCalls = 0
        var pasteCalls = 0

        override fun readField(): FieldState? =
            if (unreadable) null else FieldState(text, anchor, cursor)

        /** Selects [start] to [end], as the user would before dictating over it. */
        fun select(start: Int, end: Int) {
            anchor = start
            cursor = end
        }

        /** Puts [inserted] in place of the selection, as typing and pasting do. */
        private fun replaceSelection(inserted: String) {
            val from = minOf(anchor, cursor).coerceIn(0, text.length)
            val to = maxOf(anchor, cursor).coerceIn(0, text.length)
            text = text.substring(0, from) + inserted + text.substring(to)
            cursor = from + inserted.length
            anchor = cursor
        }

        override fun setText(text: String): Boolean {
            setTextCalls++
            if (setTextRefuses) return false
            if (!setTextLies) {
                staleText = this.text
                staleReadsLeft = readLag
                this.text = reformat(text)
            }
            return true
        }

        override fun setSelection(cursor: Int) {
            // Real fields clamp an out-of-range caret instead of throwing.
            this.cursor = cursor.coerceIn(0, text.length)
            anchor = this.cursor
        }

        override fun paste(): Boolean {
            pasteCalls++
            if (!pasteWorks) return false
            val pasted = clipboardContent ?: return false
            replaceSelection(pasted)
            return true
        }

        override fun readBack(): String? {
            if (unreadable) return null
            if (staleReadsLeft > 0) {
                staleReadsLeft--
                return staleText
            }
            return text
        }

        var committed: String? = null

        override fun commitText(text: String): Boolean {
            if (!hasInputConnection) return false
            committed = text
            if (commitLost) return true
            staleText = this.text
            staleReadsLeft = commitLag
            replaceSelection(reformat(text))
            return true
        }
    }

    private fun engine(target: FakeTarget?, memory: StrategyMemory = StrategyMemory(), pauses: MutableList<Long>? = null) =
        InsertionEngine(
            memory = memory,
            clipboard = Clipboard { text ->
                target?.clipboardContent = text
                true
            },
            pause = { pauses?.add(it) },
        )

    /** About [chars] characters of dictation, sentences that do not repeat. */
    private fun longText(chars: Int): String = buildString {
        var n = 1
        while (length < chars) {
            if (isNotEmpty()) append(' ')
            append("Satz Nummer $n handelt von etwas anderem als der davor.")
            n++
        }
    }

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

    // --- direct input, no clipboard ------------------------------------------------------

    @Test
    fun `an input connection is used first and types only the new words with their seam`() {
        val target = FakeTarget(initialText = "Guten Morgen.", hasInputConnection = true)
        val outcome = engine(target).insert(target, "wie geht es dir")

        assertEquals(InsertionStrategy.COMMIT_TEXT, outcome.strategy)
        assertEquals(" Wie geht es dir", target.committed)
        assertEquals("Guten Morgen. Wie geht es dir", target.text)
        assertEquals(0, target.setTextCalls)
        assertEquals(null, target.clipboardContent)
    }

    @Test
    fun `typing into a field that cannot be read still works`() {
        val target = FakeTarget(unreadable = true, hasInputConnection = true)
        val outcome = engine(target).insert(target, "  hallo  ")

        assertTrue(outcome.insertedIntoField)
        assertEquals("hallo", target.committed)
    }

    @Test
    fun `typing comes first even after SET_TEXT worked in another field of the same app`() {
        // Keep: the title took SET_TEXT, the note body showed it and dropped it.
        val memory = StrategyMemory().apply { recordSuccess("com.google.android.keep", InsertionStrategy.SET_TEXT) }
        val body = FakeTarget(packageName = "com.google.android.keep", hasInputConnection = true)
        val outcome = engine(body, memory).insert(body, "Milch")

        assertEquals(InsertionStrategy.COMMIT_TEXT, outcome.strategy)
        assertEquals(0, body.setTextCalls)
    }

    @Test
    fun `line breaks go in where Enter makes a new line`() {
        // Keep's note body, Gmail's message: multi-line, no Enter action.
        val target = FakeTarget(initialText = "", hasInputConnection = true, takesLineBreaks = true)
        val outcome = engine(target).insert(target, "Einkaufen:\n- Milch")

        assertEquals(InsertionStrategy.COMMIT_TEXT, outcome.strategy)
        assertEquals("Einkaufen:\n- Milch", target.text)
    }

    @Test
    fun `a field whose Enter does something else gets one line, by every method`() {
        // Keep's title (Enter jumps to the body), a search box, a messenger set to "Enter sends".
        val typed = FakeTarget(initialText = "", hasInputConnection = true)
        engine(typed).insert(typed, "Wocheneinkauf\nSamstag")
        assertEquals("Wocheneinkauf Samstag", typed.text)

        val written = FakeTarget(initialText = "")
        engine(written).insert(written, "Hallo Anna,\n\nbis morgen")
        assertEquals("Hallo Anna, bis morgen", written.text)
    }

    @Test
    fun `typed words that never arrive are reported, kept on the clipboard, and not typed twice`() {
        val target = FakeTarget(initialText = "Hallo", hasInputConnection = true, commitLost = true)
        val outcome = engine(target).insert(target, "Welt")

        assertFalse(outcome.insertedIntoField)
        assertTrue(outcome.uncertain)
        assertTrue(outcome.copiedToClipboard)
        assertEquals("Welt", target.clipboardContent)
        assertEquals(0, target.setTextCalls, "a late commit plus SET_TEXT would give the words twice")
        assertEquals("Hallo", target.text)
    }

    @Test
    fun `a lost commit with the clipboard off is reported as not inserted`() {
        val target = FakeTarget(initialText = "Hallo", hasInputConnection = true, commitLost = true)
        val outcome = engine(target).insert(target, "Welt", allowClipboard = false)

        assertFalse(outcome.insertedIntoField)
        assertFalse(outcome.copiedToClipboard)
        assertTrue(outcome.uncertain)
        assertEquals(null, target.clipboardContent)
    }

    @Test
    fun `without an input connection the chain falls back to set text`() {
        val target = FakeTarget(initialText = "Hallo")
        val outcome = engine(target).insert(target, "Welt")

        assertEquals(InsertionStrategy.SET_TEXT, outcome.strategy)
        assertEquals("Hallo Welt", target.text)
    }

    @Test
    fun `with the clipboard disallowed nothing is ever copied or pasted`() {
        val target = FakeTarget(setTextLies = true, pasteWorks = true)
        val outcome = engine(target).insert(target, "geheim", allowClipboard = false)

        assertFalse(outcome.insertedIntoField)
        assertFalse(outcome.copiedToClipboard)
        assertEquals(null, target.clipboardContent)
        assertEquals(0, target.pasteCalls)
    }

    @Test
    fun `with nothing focused and the clipboard disallowed the text is not copied`() {
        var copied = false
        val engine = InsertionEngine(StrategyMemory(), Clipboard { copied = true; true })
        val outcome = engine.insert(null, "hallo", allowClipboard = false)

        assertFalse(copied)
        assertFalse(outcome.copiedToClipboard)
    }

    @Test
    fun `a field that shows the new text a frame late is not written twice`() {
        val target = FakeTarget(initialText = "Hallo", readLag = 2)
        val outcome = engine(target).insert(target, "Welt")

        assertEquals(InsertionStrategy.SET_TEXT, outcome.strategy)
        assertTrue(outcome.insertedIntoField)
        assertEquals("Hallo Welt", target.text)
        assertEquals(0, target.pasteCalls, "a late field must not get the words pasted again")
    }

    // --- long dictations -------------------------------------------------------------------

    @Test
    fun `a long text the app takes a while to show is not reported lost`() {
        // Five minutes of speech: thousands of characters, and the app needs well over 0.7 s.
        val text = longText(4_000)
        val target = FakeTarget(initialText = "", hasInputConnection = true, commitLag = 30)
        val pauses = mutableListOf<Long>()
        val outcome = engine(target, pauses = pauses).insert(target, text)

        assertTrue(outcome.insertedIntoField)
        assertFalse(outcome.uncertain)
        assertEquals(null, target.clipboardContent)
        assertEquals(30, pauses.size, "read until the words showed, without sleeping in the test")
    }

    @Test
    fun `a short text still gets the short wait`() {
        val target = FakeTarget(initialText = "", hasInputConnection = true, commitLag = 30)
        val pauses = mutableListOf<Long>()
        val outcome = engine(target, pauses = pauses).insert(target, "Hallo Welt")

        assertFalse(outcome.insertedIntoField)
        assertTrue(outcome.uncertain)
        assertEquals(11, pauses.size)
    }

    @Test
    fun `the wait for a very long text ends after about three seconds`() {
        val target = FakeTarget(initialText = "Hallo", hasInputConnection = true, commitLost = true)
        val pauses = mutableListOf<Long>()
        val outcome = engine(target, pauses = pauses).insert(target, longText(20_000))

        assertFalse(outcome.insertedIntoField)
        assertTrue(outcome.uncertain)
        assertTrue(pauses.sum() in 2_500L..3_000L, "waited ${pauses.sum()} ms")
    }

    @Test
    fun `a long text the app reformats in the middle counts as typed`() {
        val text = longText(1_500).replaceFirst("Satz Nummer 9", "Satz :) Nummer 9")
        val target = FakeTarget(
            initialText = "",
            hasInputConnection = true,
            takesLineBreaks = true,
            reformat = { it.replace(":)", "\uD83D\uDE42").replace("\n", " \n ") },
        )
        val outcome = engine(target).insert(target, text.replaceFirst(". Satz Nummer 4", ".\nSatz Nummer 4"))

        assertTrue(outcome.insertedIntoField)
        assertFalse(outcome.uncertain)
        assertEquals(null, target.clipboardContent)
    }

    @Test
    fun `a long text cut off by the field is reported, not passed as typed`() {
        val text = longText(1_500)
        val target = FakeTarget(initialText = "", hasInputConnection = true, reformat = { it.take(1_000) })
        val outcome = engine(target).insert(target, text)

        assertFalse(outcome.insertedIntoField)
        assertTrue(outcome.uncertain)
        assertEquals(1_000, target.text.length, "typed once, never a second time")
    }

    @Test
    fun `the same words already in the field do not pass for the new ones`() {
        // Insert again into the field that holds the first copy: the commit is lost this time.
        val text = longText(600)
        val target = FakeTarget(initialText = text, hasInputConnection = true, commitLost = true)
        val outcome = engine(target).insert(target, text)

        assertFalse(outcome.insertedIntoField)
        assertTrue(outcome.uncertain)
        assertEquals(text, target.text)
    }

    @Test
    fun `line breaks that come back as spaces still match`() {
        val target = FakeTarget(initialText = "", takesLineBreaks = true, reformat = { it.replace("\n", "  ") })
        val outcome = engine(target).insert(target, "Liebe Anna,\nbis morgen")

        assertEquals(InsertionStrategy.SET_TEXT, outcome.strategy)
        assertTrue(outcome.insertedIntoField)
        assertEquals(0, target.pasteCalls, "a reformatted write must not be pasted a second time")
    }

    // --- dictating over a selection ----------------------------------------------------------

    @Test
    fun `typed over a selection holding the same words counts as typed`() {
        val target = FakeTarget(initialText = "See you tomorrow at 5", hasInputConnection = true)
        target.select(0, target.text.length)
        val outcome = engine(target).insert(target, "see you tomorrow")

        assertTrue(outcome.insertedIntoField)
        assertFalse(outcome.uncertain)
        assertEquals(null, target.clipboardContent)
        assertEquals("See you tomorrow", target.text)
    }

    @Test
    fun `written over a selection holding the same words is not pasted a second time`() {
        val target = FakeTarget(initialText = "Ok. See you tomorrow at 5")
        target.select(4, target.text.length)
        val outcome = engine(target).insert(target, "see you tomorrow")

        assertEquals(InsertionStrategy.SET_TEXT, outcome.strategy)
        assertTrue(outcome.insertedIntoField)
        assertEquals(0, target.pasteCalls)
        assertEquals(1, Regex("(?i)see you tomorrow").findAll(target.text).count(), target.text)
    }

    @Test
    fun `pasted over a selection holding the same words counts as pasted`() {
        val target = FakeTarget(initialText = "See you tomorrow at 5", setTextLies = true)
        target.select(0, target.text.length)
        val outcome = engine(target).insert(target, "see you tomorrow")

        assertEquals(InsertionStrategy.PASTE, outcome.strategy)
        assertTrue(outcome.insertedIntoField)
        assertEquals(1, target.setTextCalls, "set text did nothing, though the selection held the words")
        assertEquals("see you tomorrow", target.text)
    }

    @Test
    fun `a lost commit over a selection holding the same words is still lost`() {
        val target = FakeTarget(initialText = "See you tomorrow at 5", hasInputConnection = true, commitLost = true)
        target.select(0, target.text.length)
        val outcome = engine(target).insert(target, "see you tomorrow")

        assertFalse(outcome.insertedIntoField)
        assertTrue(outcome.uncertain)
    }
}
