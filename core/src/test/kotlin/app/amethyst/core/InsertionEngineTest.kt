package app.amethyst.core

import app.amethyst.core.insert.Clipboard
import app.amethyst.core.insert.EditableTarget
import app.amethyst.core.insert.InsertionEngine
import app.amethyst.core.insert.InsertionStrategy
import app.amethyst.core.insert.OwnClip
import app.amethyst.core.insert.StrategyMemory
import app.amethyst.core.insert.TargetCheck
import app.amethyst.core.text.FieldKind
import app.amethyst.core.text.FieldState
import app.amethyst.core.text.NormalizeOptions
import app.amethyst.core.text.ScratchThat
import app.amethyst.core.text.SpliceOptions
import app.amethyst.core.text.TranscriptNormalizer
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
        override val isSecret: Boolean = false,
        /** Where the field leaves the cursor after taking typed words in, from where typing put it. */
        private val cursorAfterCommit: (text: String, cursor: Int) -> Int = { _, cursor -> cursor },
        /** Rich text or no EditText: a SET_TEXT write would flatten it. */
        override val setTextFlattens: Boolean = false,
        /**
         * The keyboard is still writing the last this many characters before the cursor - the
         * word it underlines. A commit replaces them, as Android's input connection does.
         */
        private var composing: Int = 0,
        /** Typing the words again in place of the ones just typed works ([EditableTarget.retype]). */
        private val retypes: Boolean = true,
        /** The words typed again are accepted and never arrive. */
        private val retypeLost: Boolean = false,
        override val fieldKind: FieldKind = FieldKind.TEXT,
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
            if (composing > 0) {
                anchor = (cursor - composing).coerceAtLeast(0)
                composing = 0
            }
            replaceSelection(reformat(text))
            cursor = cursorAfterCommit(this.text, cursor)
            anchor = cursor
            return true
        }

        val retyped = mutableListOf<Pair<String, String>>()

        override fun retype(shown: String, text: String): Boolean {
            retyped += shown to text
            if (!hasInputConnection || !retypes) return false
            if (anchor != cursor || !this.text.substring(0, cursor).endsWith(shown)) return false
            if (retypeLost) return true
            val from = cursor - shown.length
            this.text = this.text.substring(0, from) + text + this.text.substring(cursor)
            cursor = from + text.length
            anchor = cursor
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
    fun `a field that set text would flatten gets the words pasted, seam included`() {
        val target = FakeTarget(initialText = "Guten Morgen", setTextFlattens = true)
        val clipboard = ForgettingClipboard(target)
        val outcome = InsertionEngine(StrategyMemory(), clipboard, pause = {}).insert(target, "wie geht es dir")

        assertEquals(InsertionStrategy.PASTE, outcome.strategy)
        assertEquals(0, target.setTextCalls, "the whole field is never written back")
        assertEquals("Guten Morgen wie geht es dir", target.text)
        assertEquals(" wie geht es dir", outcome.fragment, "Undo last takes the seam along")
        assertEquals(listOf(" wie geht es dir"), clipboard.forgotten, "the clip that went in comes off again")
    }

    @Test
    fun `pasting goes first even where set text worked last time`() {
        val memory = StrategyMemory(mapOf("com.whatsapp" to InsertionStrategy.SET_TEXT))
        val target = FakeTarget(initialText = "Hi", setTextFlattens = true)
        val outcome = engine(target, memory).insert(target, "Anna")

        assertEquals(InsertionStrategy.PASTE, outcome.strategy)
        assertEquals(0, target.setTextCalls)
    }

    @Test
    fun `typing still goes before pasting into a field set text would flatten`() {
        val target = FakeTarget(initialText = "Hi", setTextFlattens = true, hasInputConnection = true)
        val outcome = engine(target).insert(target, "Anna")

        assertEquals(InsertionStrategy.COMMIT_TEXT, outcome.strategy)
        assertEquals(0, target.pasteCalls)
    }

    @Test
    fun `without the clipboard a field set text would flatten still gets set text`() {
        val target = FakeTarget(initialText = "Hi", setTextFlattens = true)
        val outcome = engine(target).insert(target, "Anna", allowClipboard = false)

        assertEquals(InsertionStrategy.SET_TEXT, outcome.strategy)
        assertEquals(0, target.pasteCalls)
        assertEquals("Hi Anna", target.text)
    }

    @Test
    fun `a failed paste into such a field falls back to set text`() {
        val target = FakeTarget(initialText = "Hi", setTextFlattens = true, pasteWorks = false)
        val outcome = engine(target).insert(target, "Anna")

        assertEquals(InsertionStrategy.SET_TEXT, outcome.strategy)
        assertEquals(1, target.pasteCalls)
        assertEquals("Hi Anna", target.text)
    }

    @Test
    fun `a failed paste followed by set text takes the pasted clip off again`() {
        val target = FakeTarget(initialText = "Hi", setTextFlattens = true, pasteWorks = false)
        val clipboard = ForgettingClipboard(target)
        val outcome = InsertionEngine(StrategyMemory(), clipboard, pause = {}).insert(target, "Anna")

        assertEquals(InsertionStrategy.SET_TEXT, outcome.strategy)
        assertEquals(listOf(" Anna"), clipboard.forgotten, "what was copied - seam included - is what comes off")
        assertFalse(outcome.copiedToClipboard)
    }

    @Test
    fun `a paste that went first only for a rich field leaves the app's method as it was`() {
        val memory = StrategyMemory()
        val rich = FakeTarget(initialText = "Hi", setTextFlattens = true)
        assertEquals(InsertionStrategy.PASTE, engine(rich, memory).insert(rich, "Anna").strategy)
        assertEquals(emptyMap(), memory.snapshot())

        val plain = FakeTarget(initialText = "Hi")
        val outcome = engine(plain, memory).insert(plain, "Anna")
        assertEquals(InsertionStrategy.SET_TEXT, outcome.strategy, "a plain field of the same app is still written directly")
        assertEquals(0, plain.pasteCalls)

        val remembered = StrategyMemory(mapOf("com.whatsapp" to InsertionStrategy.SET_TEXT))
        val again = FakeTarget(initialText = "Hi", setTextFlattens = true)
        engine(again, remembered).insert(again, "Anna")
        assertEquals(mapOf("com.whatsapp" to InsertionStrategy.SET_TEXT), remembered.snapshot())
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

    /** A clipboard that also takes a pasted clip off again, as Amber's does; [forgotten] says what. */
    private class ForgettingClipboard(private val target: FakeTarget?) : Clipboard {
        val forgotten = mutableListOf<String>()

        override fun copy(text: String): Boolean {
            target?.clipboardContent = text
            return true
        }

        override fun forget(text: String): Boolean {
            forgotten += text
            target?.clipboardContent = null
            return true
        }
    }

    @Test
    fun `a pasted dictation is taken off the clipboard once it is in the field`() {
        val target = FakeTarget(setTextLies = true)
        val clipboard = ForgettingClipboard(target)
        val outcome = InsertionEngine(StrategyMemory(), clipboard, pause = {}).insert(target, "Hallo Welt")

        assertEquals(InsertionStrategy.PASTE, outcome.strategy)
        assertTrue(outcome.insertedIntoField)
        assertEquals(listOf("Hallo Welt"), clipboard.forgotten)
        assertFalse(outcome.copiedToClipboard, "the clip is gone")
        assertEquals(null, target.clipboardContent)
    }

    @Test
    fun `a clip that is the fallback stays on the clipboard`() {
        val notWritable = FakeTarget(setTextLies = true, pasteWorks = false)
        val clipboard = ForgettingClipboard(notWritable)
        val outcome = InsertionEngine(StrategyMemory(), clipboard, pause = {}).insert(notWritable, "Hallo")
        assertFalse(outcome.insertedIntoField)
        assertTrue(outcome.copiedToClipboard)
        assertEquals("Hallo", notWritable.clipboardContent)

        val lost = FakeTarget(hasInputConnection = true, commitLost = true)
        val lostClipboard = ForgettingClipboard(lost)
        val uncertain = InsertionEngine(StrategyMemory(), lostClipboard, pause = {}).insert(lost, "Hallo")
        assertTrue(uncertain.uncertain)
        assertTrue(uncertain.copiedToClipboard)

        val nothingFocused = ForgettingClipboard(null)
        assertTrue(InsertionEngine(StrategyMemory(), nothingFocused, pause = {}).insert(null, "Hallo").copiedToClipboard)
        assertEquals(emptyList(), clipboard.forgotten + lostClipboard.forgotten + nothingFocused.forgotten)
    }

    @Test
    fun `words typed or set without the clipboard leave it alone`() {
        val target = FakeTarget(initialText = "Guten Morgen")
        val clipboard = ForgettingClipboard(target)
        InsertionEngine(StrategyMemory(), clipboard, pause = {}).insert(target, "wie geht es dir")
        assertEquals(emptyList(), clipboard.forgotten)
    }

    @Test
    fun `a pasted clip is cleared only while it is still the app's own`() {
        assertTrue(OwnClip.clearAfterPaste(ours = true, writtenAgoMs = 60_000))
        assertFalse(OwnClip.clearAfterPaste(ours = false, writtenAgoMs = 100), "the user copied something since")
        // Android 10+ shows the clipboard to the app in front only - after a paste, the other app.
        assertTrue(OwnClip.clearAfterPaste(ours = null, writtenAgoMs = 2_500))
        assertFalse(OwnClip.clearAfterPaste(ours = null, writtenAgoMs = OwnClip.PASTE_WINDOW_MS + 1))
        assertFalse(OwnClip.clearAfterPaste(ours = null, writtenAgoMs = -1), "a clock that went back tells nothing")
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

    // --- the last look before writing ----------------------------------------------------

    @Test
    fun `a password field found at the write gets nothing, by no method, and nothing is copied`() {
        // The check before saw a plain text field - a Compose password field with the default
        // keyboard type - or focus moved into one after it.
        val memory = StrategyMemory().apply { recordSuccess("com.bank", InsertionStrategy.SET_TEXT) }
        val login = FakeTarget(packageName = "com.bank", hasInputConnection = true, isSecret = true)
        val outcome = engine(login, memory).insert(login, "see you tomorrow")

        assertFalse(outcome.insertedIntoField)
        assertFalse(outcome.copiedToClipboard)
        assertFalse(outcome.uncertain)
        assertEquals(TargetCheck.Verdict.SECRET, outcome.refused)
        assertEquals("", login.text)
        assertEquals(null, login.committed)
        assertEquals(0, login.setTextCalls)
        assertEquals(0, login.pasteCalls)
        assertEquals(null, login.clipboardContent)
        assertEquals(InsertionStrategy.SET_TEXT, memory.order("com.bank").first(), "a refusal says nothing about what works there")
    }

    @Test
    fun `a field that is no secret is written as ever`() {
        val target = FakeTarget(hasInputConnection = true)
        val outcome = engine(target).insert(target, "hallo")

        assertTrue(outcome.insertedIntoField)
        assertEquals(null, outcome.refused)
    }

    @Test
    fun `another app's field found at the write gets nothing, and nothing is copied`() {
        // Focus went to another app's field after the check - an overlay that takes it.
        val overlay = FakeTarget(packageName = "com.overlay", hasInputConnection = true)
        val outcome = engine(overlay).insert(overlay, "see you tomorrow", expectedPackage = "com.whatsapp")

        assertFalse(outcome.insertedIntoField)
        assertFalse(outcome.copiedToClipboard)
        assertEquals(TargetCheck.Verdict.MOVED, outcome.refused)
        assertEquals("", overlay.text)
        assertEquals(null, overlay.committed)
        assertEquals(0, overlay.setTextCalls)
        assertEquals(0, overlay.pasteCalls)
        assertEquals(null, overlay.clipboardContent, "a field that took focus could read the clipboard")
    }

    @Test
    fun `the expected app's field is written, whichever field of it`() {
        val target = FakeTarget(packageName = "com.whatsapp", hasInputConnection = true)
        val outcome = engine(target).insert(target, "see you tomorrow", expectedPackage = "com.whatsapp")

        assertTrue(outcome.insertedIntoField)
        assertEquals(null, outcome.refused)
    }

    @Test
    fun `with nothing focused the expected app changes nothing`() {
        val outcome = engine(null).insert(null, "hallo", expectedPackage = "com.whatsapp")

        assertFalse(outcome.insertedIntoField)
        assertEquals(null, outcome.refused)
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
        // Pasted as typing would put it: a capital at the start of the field.
        assertEquals("See you tomorrow", target.text)
    }

    @Test
    fun `a lost commit over a selection holding the same words is still lost`() {
        val target = FakeTarget(initialText = "See you tomorrow at 5", hasInputConnection = true, commitLost = true)
        target.select(0, target.text.length)
        val outcome = engine(target).insert(target, "see you tomorrow")

        assertFalse(outcome.insertedIntoField)
        assertTrue(outcome.uncertain)
    }

    // --- the cursor after the words ----------------------------------------------------------

    /**
     * "Hallo Anna Komma neue Zeile wie geht es dir Fragezeichen" into WhatsApp (Pixel 9a,
     * 2026-11-16): the words came in, the cursor stood before the final "?". The whole text
     * path on the phone, from what Parakeet may have written to the field.
     */
    @Test
    fun `a spoken question mark at the end leaves the cursor behind it, whatever the recognizer wrote`() {
        val spoken = listOf(
            "Hallo Anna Komma neue Zeile wie geht es dir Fragezeichen",
            "Hallo Anna, Komma, neue Zeile, wie geht es dir Fragezeichen",
            "Hallo Anna, Komma, neue Zeile, wie geht es dir? Fragezeichen.",
            "Hallo Anna, Komma, neue Zeile, wie geht es dir, Fragezeichen?",
            "Hallo Anna Komma neue Zeile wie geht es dir Fragezeichen?",
            // What a cleanup model may already have made of it.
            "Hallo Anna,\nwie geht es dir?",
        )
        for (raw in spoken) {
            for (formatted in listOf(false, true)) {
                if ('\n' in raw && !formatted) continue
                val text = TranscriptNormalizer.normalize(
                    ScratchThat.apply(raw).text,
                    NormalizeOptions(spokenPunctuation = true),
                    language = "de",
                    keepLineBreaks = formatted,
                )
                assertEquals("Hallo Anna,\nwie geht es dir?", text, raw)
                for (lineBreaks in listOf(true, false)) {
                    val field = FakeTarget(initialText = "", hasInputConnection = true, takesLineBreaks = lineBreaks)
                    val outcome = engine(field).insert(field, text)

                    val expected = if (lineBreaks) "Hallo Anna,\nwie geht es dir?" else "Hallo Anna, wie geht es dir?"
                    assertEquals(InsertionStrategy.COMMIT_TEXT, outcome.strategy, raw)
                    assertEquals(expected, field.text, raw)
                    assertEquals(expected.length, field.cursor, "cursor behind the \"?\": $raw")
                    assertFalse(outcome.cursorPutBack, raw)
                }
            }
        }
    }

    @Test
    fun `an app that leaves the cursor before the final mark has it put behind the words`() {
        val field = FakeTarget(
            initialText = "",
            hasInputConnection = true,
            takesLineBreaks = true,
            cursorAfterCommit = { _, cursor -> cursor - 1 },
        )
        val outcome = engine(field).insert(field, "Hallo Anna,\nwie geht es dir?")

        assertEquals("Hallo Anna,\nwie geht es dir?", field.text)
        assertEquals(field.text.length, field.cursor)
        assertTrue(outcome.cursorPutBack)
    }

    @Test
    fun `with text behind the cursor, the cursor goes right behind the words, before that text`() {
        val field = FakeTarget(
            initialText = "Bis dann",
            hasInputConnection = true,
            cursorAfterCommit = { text, _ -> text.length },
        )
        field.select(0, 0)
        val outcome = engine(field).insert(field, "wie geht es dir?")

        assertEquals("Wie geht es dir? Bis dann", field.text)
        assertEquals("Wie geht es dir? ".length, field.cursor)
        assertTrue(outcome.cursorPutBack)
    }

    @Test
    fun `a field that writes the words differently still gets the cursor behind them`() {
        val field = FakeTarget(initialText = "Ende", hasInputConnection = true, cursorAfterCommit = { _, _ -> 0 }, reformat = { "$it!" })
        field.select(0, 0)
        val outcome = engine(field).insert(field, "Anfang")

        assertTrue(outcome.insertedIntoField)
        assertEquals("Anfang !Ende", field.text)
        assertEquals("Anfang !".length, field.cursor)
    }

    @Test
    fun `a written field that makes a line break two characters still gets the cursor at the end`() {
        // No input connection: SET_TEXT, and the cursor the splice worked out for its own text
        // would land one before the end - before the "?".
        val field = FakeTarget(initialText = "", takesLineBreaks = true, reformat = { it.replace("\n", "\r\n") })
        val outcome = engine(field).insert(field, "Hallo Anna,\nwie geht es dir?")

        assertEquals(InsertionStrategy.SET_TEXT, outcome.strategy)
        assertEquals("Hallo Anna,\r\nwie geht es dir?", field.text)
        assertEquals(field.text.length, field.cursor)
    }

    @Test
    fun `a written field keeps the cursor before the text that stood behind it`() {
        val field = FakeTarget(initialText = "Ende", takesLineBreaks = true, reformat = { it.replace("\n", "\r\n") })
        field.select(0, 0)
        engine(field).insert(field, "Erste Zeile\nzweite Zeile.")

        assertEquals("Erste Zeile\r\nzweite Zeile. Ende", field.text)
        // As the splice places it: right behind the words, before the space it added.
        assertEquals("Erste Zeile\r\nzweite Zeile.".length, field.cursor)
    }

    // --- the keyboard's unfinished word -----------------------------------------------------

    @Test
    fun `typing over the word the keyboard is still writing puts that word back`() {
        val target = FakeTarget(initialText = "Hallo", hasInputConnection = true, composing = 5)
        val outcome = engine(target).insert(target, "wie geht's")

        assertEquals(InsertionStrategy.COMMIT_TEXT, outcome.strategy)
        assertTrue(outcome.insertedIntoField)
        assertEquals("Hallo wie geht's", target.text)
        assertEquals(target.text.length, target.cursor)
        assertEquals(InsertionEngine.PrefixRepair(lost = 5, how = InsertionEngine.PrefixRepair.How.TYPED_AGAIN), outcome.prefixRepair)
        assertEquals(listOf(" wie geht's" to "Hallo wie geht's"), target.retyped)
        assertEquals(0, target.setTextCalls)
        assertEquals(" wie geht's", outcome.fragment, "Undo last still takes only the words")
    }

    @Test
    fun `the word comes back in the middle of a text too, the cursor behind the words`() {
        val plain = FakeTarget(initialText = "Ich sage Hallo und tschüss", hasInputConnection = true)
        plain.select(14, 14)
        engine(plain).insert(plain, "wie geht's")

        val target = FakeTarget(initialText = "Ich sage Hallo und tschüss", hasInputConnection = true, composing = 5)
        target.select(14, 14)
        val outcome = engine(target).insert(target, "wie geht's")

        assertEquals(plain.text, target.text)
        assertEquals(plain.cursor, target.cursor)
        assertEquals(InsertionEngine.PrefixRepair.How.TYPED_AGAIN, outcome.prefixRepair?.how)
    }

    @Test
    fun `where typing it again is not possible the field is written back whole`() {
        val target = FakeTarget(initialText = "Hallo", hasInputConnection = true, composing = 5, retypes = false)
        val outcome = engine(target).insert(target, "wie geht's")

        assertEquals("Hallo wie geht's", target.text)
        assertEquals(target.text.length, target.cursor)
        assertEquals(1, target.setTextCalls)
        assertEquals(InsertionEngine.PrefixRepair.How.WRITTEN_BACK, outcome.prefixRepair?.how)
        assertEquals(InsertionStrategy.COMMIT_TEXT, outcome.strategy)
    }

    @Test
    fun `a rich field is not written back for it, and the loss is reported`() {
        val target = FakeTarget(initialText = "Hallo", hasInputConnection = true, composing = 5, retypes = false, setTextFlattens = true)
        val outcome = engine(target).insert(target, "wie geht's")

        assertEquals(" wie geht's", target.text)
        assertEquals(0, target.setTextCalls)
        assertEquals(InsertionEngine.PrefixRepair(5, InsertionEngine.PrefixRepair.How.NOT_RESTORED), outcome.prefixRepair)
        assertTrue(outcome.insertedIntoField, "the words themselves are in")
    }

    @Test
    fun `words typed again that never show are not written a second way`() {
        val target = FakeTarget(initialText = "Hallo", hasInputConnection = true, composing = 5, retypeLost = true)
        val outcome = engine(target).insert(target, "wie geht's")

        assertEquals(0, target.setTextCalls, "a late retype on top of a written field would be text twice")
        assertEquals(1, target.retyped.size)
        assertEquals(InsertionEngine.PrefixRepair.How.NOT_RESTORED, outcome.prefixRepair?.how)
    }

    @Test
    fun `a field that lost nothing is left alone`() {
        val target = FakeTarget(initialText = "Hallo", hasInputConnection = true)
        val outcome = engine(target).insert(target, "wie geht's")

        assertEquals("Hallo wie geht's", target.text)
        assertEquals(emptyList(), target.retyped)
        assertEquals(0, target.setTextCalls)
        assertEquals(null, outcome.prefixRepair)
    }

    @Test
    fun `a selection the words replace on purpose is not put back`() {
        val target = FakeTarget(initialText = "Hallo Welt", hasInputConnection = true)
        target.select(6, 10)
        val outcome = engine(target).insert(target, "Leute")

        assertEquals("Hallo Leute", target.text)
        assertEquals(emptyList(), target.retyped)
        assertEquals(null, outcome.prefixRepair)
    }

    @Test
    fun `a field that formats what it is given gets nothing back`() {
        // An amount field: "12" and "5" become "12,5" - nothing to restore, nothing added.
        val target = FakeTarget(initialText = "12", hasInputConnection = true, reformat = { ",${it.trim()}" })
        val outcome = engine(target).insert(target, "5")

        assertEquals("12,5", target.text)
        assertEquals(emptyList(), target.retyped)
        assertEquals(null, outcome.prefixRepair)
    }

    @Test
    fun `an unreadable field is not checked`() {
        val target = FakeTarget(initialText = "Hallo", hasInputConnection = true, composing = 5, unreadable = true)
        val outcome = engine(target).insert(target, "wie geht's")

        assertTrue(outcome.insertedIntoField)
        assertEquals(emptyList(), target.retyped)
        assertEquals(null, outcome.prefixRepair)
    }

    @Test
    fun `typed into a search field, the words go in without capital and full stop and count as there`() {
        val target = FakeTarget(initialText = "", hasInputConnection = true, fieldKind = FieldKind.SEARCH)
        val outcome = engine(target).insert(target, "Pizza near me.", SpliceOptions(language = "en"))

        assertEquals(InsertionStrategy.COMMIT_TEXT, outcome.strategy)
        assertTrue(outcome.insertedIntoField, "the words without their full stop are the words")
        assertFalse(outcome.uncertain)
        assertEquals("pizza near me", target.text)
        assertEquals("pizza near me", outcome.fragment)
    }

    @Test
    fun `written into a search field, the words go in without capital and full stop and count as there`() {
        val target = FakeTarget(initialText = "", fieldKind = FieldKind.SEARCH)
        val outcome = engine(target).insert(target, "Pizza near me.", SpliceOptions(language = "en"))

        assertEquals(InsertionStrategy.SET_TEXT, outcome.strategy)
        assertEquals("pizza near me", target.text)
        assertEquals(1, target.setTextCalls, "nothing tried twice")
    }

    @Test
    fun `pasted into an address field, the words go in without full stop and count as there`() {
        val target = FakeTarget(initialText = "", setTextLies = true, fieldKind = FieldKind.URI)
        val outcome = engine(target).insert(target, "Example.com.")

        assertEquals(InsertionStrategy.PASTE, outcome.strategy)
        assertTrue(outcome.insertedIntoField)
        assertEquals("example.com", target.text)
    }

    @Test
    fun `dictated in front of the rest of a sentence, the full stop goes and the words count as there`() {
        val target = FakeTarget(initialText = "tomorrow", hasInputConnection = true)
        target.select(0, 0)
        val outcome = engine(target).insert(target, "See you.", SpliceOptions(language = "en"))

        assertTrue(outcome.insertedIntoField)
        assertFalse(outcome.uncertain)
        assertEquals("See you tomorrow", target.text)
    }
}
