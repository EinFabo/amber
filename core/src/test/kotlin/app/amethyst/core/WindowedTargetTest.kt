package app.amethyst.core

import app.amethyst.core.insert.Clipboard
import app.amethyst.core.insert.FieldWindow
import app.amethyst.core.insert.InsertionEngine
import app.amethyst.core.insert.InsertionStrategy
import app.amethyst.core.insert.StrategyMemory
import app.amethyst.core.insert.WindowedTarget
import app.amethyst.core.text.SpliceOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dictating into a field Android names no node for - a Compose field up to 1.7: the Claude
 * app's, Amber's own test field. All there is of it is the input connection.
 */
class WindowedTargetTest {

    /** A field behind an input connection, read the way a keyboard reads it. */
    private class ConnectionField(
        initialText: String = "",
        /** Compose's connection does not say where a window begins; a View's does. */
        private val saysOffset: Boolean = false,
        /** The connection hands out nothing at all. */
        private val answers: Boolean = true,
        /** A terminal, a game: whatever is typed, the connection reads as empty. */
        private val blind: Boolean = false,
        /** The connection accepts the words and they never arrive. */
        private val commitLost: Boolean = false,
        /** Reads after typing that still show the old text. */
        private val lag: Int = 0,
        override val showsText: Boolean = true,
        private val reformat: (String) -> String = { it },
    ) : WindowedTarget() {
        override val packageName: String = "com.anthropic.claude"

        var text: String = initialText
            private set
        private var anchor = initialText.length
        private var cursor = initialText.length

        private var staleText: String? = null
        private var staleAnchor = 0
        private var staleCursor = 0
        private var staleReadsLeft = 0

        val typed = mutableListOf<String>()
        var reads = 0
            private set
        var longestAsked = 0
            private set

        fun select(start: Int, end: Int) {
            anchor = start
            cursor = end
        }

        /** The user, or the app, changing the field between the two reads. */
        fun rewrite(text: String) {
            this.text = text
            anchor = text.length
            cursor = text.length
        }

        override fun read(before: Int, after: Int): FieldWindow? {
            reads++
            longestAsked = maxOf(longestAsked, before)
            if (!answers) return null
            if (blind) return FieldWindow.of("", 0, 0, offset = -1, askedBefore = before)
            val stale = staleReadsLeft > 0
            if (stale) staleReadsLeft--
            val shown = if (stale) staleText!! else text
            val from = if (stale) staleAnchor else anchor
            val to = if (stale) staleCursor else cursor
            val begins = (minOf(from, to) - before).coerceAtLeast(0)
            val ends = (maxOf(from, to) + after).coerceAtMost(shown.length)
            return FieldWindow.of(shown.substring(begins, ends), from - begins, to - begins, if (saysOffset) begins else -1, before)
        }

        override fun type(text: String): Boolean {
            typed += text
            if (commitLost || blind) return true
            staleText = this.text
            staleAnchor = anchor
            staleCursor = cursor
            staleReadsLeft = lag
            val from = minOf(anchor, cursor)
            val to = maxOf(anchor, cursor)
            val arrives = reformat(text)
            this.text = this.text.substring(0, from) + arrives + this.text.substring(to)
            cursor = from + arrives.length
            anchor = cursor
            return true
        }
    }

    private var onClipboard: String? = null

    private fun engine(memory: StrategyMemory = StrategyMemory(), pauses: MutableList<Long>? = null) = InsertionEngine(
        memory = memory,
        clipboard = Clipboard { text ->
            onClipboard = text
            true
        },
        pause = { pauses?.add(it) },
    )

    /** About [chars] characters of dictation, sentences that do not repeat. */
    private fun longText(chars: Int, from: Int = 1): String = buildString {
        var n = from
        while (length < chars) {
            if (isNotEmpty()) append(' ')
            append("Satz Nummer $n handelt von etwas anderem als der davor.")
            n++
        }
    }

    // --- the seam ------------------------------------------------------------------------

    @Test
    fun `a second dictation gets its space and its capital`() {
        // Reported: "Hello there.how are you" in the Claude app and in Amber's test field.
        val field = ConnectionField("Hello there.")
        val outcome = engine().insert(field, "how are you")

        assertEquals(InsertionStrategy.COMMIT_TEXT, outcome.strategy)
        assertTrue(outcome.insertedIntoField)
        assertEquals(listOf(" How are you"), field.typed)
        assertEquals("Hello there. How are you", field.text)
        assertEquals(" How are you", outcome.fragment, "what Undo last takes out again")
    }

    @Test
    fun `words after typed text get a space and stay small`() {
        val field = ConnectionField("Hello")
        engine().insert(field, "there")

        assertEquals("Hello there", field.text)
    }

    @Test
    fun `a space that is there already is not doubled`() {
        val field = ConnectionField("Hello there. ")
        engine().insert(field, "how are you")

        assertEquals("Hello there. How are you", field.text)
    }

    @Test
    fun `an empty field gets a capital and no space`() {
        val field = ConnectionField("")
        val outcome = engine().insert(field, "  hello there  ")

        assertTrue(outcome.insertedIntoField)
        assertEquals("Hello there", field.text)
    }

    @Test
    fun `the switches for space and capital hold here too`() {
        val field = ConnectionField("Hello there.")
        engine().insert(field, "how are you", SpliceOptions(autoSpace = false, autoCapitalize = false))

        assertEquals("Hello there.how are you", field.text)
    }

    @Test
    fun `words in the middle get a space on both sides`() {
        val field = ConnectionField("Hello Fabi")
        field.select(5, 5)
        engine().insert(field, "dear")

        assertEquals("Hello dear Fabi", field.text)
    }

    @Test
    fun `a selection is typed over, with the seam of what stands around it`() {
        val field = ConnectionField("See you at five. Bye")
        field.select(11, 15)
        val outcome = engine().insert(field, "six")

        assertTrue(outcome.insertedIntoField)
        assertEquals("See you at six. Bye", field.text)
    }

    @Test
    fun `a whole field selected is replaced as a new beginning`() {
        val field = ConnectionField("old words")
        field.select(9, 0)
        engine().insert(field, "new words")

        assertEquals("New words", field.text)
    }

    @Test
    fun `an emoji before the cursor is a word like any other`() {
        val field = ConnectionField("Great 😀")
        engine().insert(field, "see you")

        assertEquals("Great 😀 see you", field.text)
    }

    @Test
    fun `a punctuation mark is typed without a space before it`() {
        val field = ConnectionField("Hello there")
        engine().insert(field, ", how are you")

        assertEquals("Hello there, how are you", field.text)
    }

    // --- did the words arrive ------------------------------------------------------------

    @Test
    fun `words that show up a few reads late are waited for`() {
        val pauses = mutableListOf<Long>()
        val field = ConnectionField("Hello there.", lag = 3)
        val outcome = engine(pauses = pauses).insert(field, "how are you")

        assertTrue(outcome.insertedIntoField)
        assertEquals(3, pauses.size)
        assertEquals(1, field.typed.size)
    }

    @Test
    fun `typed words that never arrive are reported and not typed twice`() {
        val field = ConnectionField("Hello there.", commitLost = true)
        val outcome = engine().insert(field, "how are you")

        assertFalse(outcome.insertedIntoField)
        assertTrue(outcome.uncertain)
        assertEquals(1, field.typed.size)
        assertEquals("how are you", onClipboard)
        assertEquals("Hello there.", field.text)
    }

    /** Exactly one window of text, so that what stands before it is just out of the first read. */
    private val oneWindow = longText(FieldWindow.BEFORE).take(FieldWindow.BEFORE)

    @Test
    fun `the same words from long ago do not pass for the ones typed now`() {
        // More before the cursor than one window holds, and no word on where a window begins:
        // the read after typing reaches further back, to where the same sentence stands.
        val field = ConnectionField("How are you. $oneWindow", commitLost = true)
        val outcome = engine().insert(field, "how are you")

        assertFalse(outcome.insertedIntoField, "a text that never arrived must not count as there")
        assertTrue(outcome.uncertain)
    }

    @Test
    fun `the same words from long ago do not hide the ones typed now`() {
        val field = ConnectionField("How are you. $oneWindow")
        val outcome = engine().insert(field, "how are you")

        assertTrue(outcome.insertedIntoField)
        assertEquals("How are you. $oneWindow how are you", field.text)
    }

    @Test
    fun `a dictation longer than the window is found from its first word`() {
        val dictation = longText(3 * FieldWindow.BEFORE, from = 500)
        for (saysOffset in listOf(false, true)) {
            val field = ConnectionField(longText(2 * FieldWindow.BEFORE), saysOffset = saysOffset)
            val outcome = engine().insert(field, dictation)

            assertTrue(outcome.insertedIntoField, "saysOffset=$saysOffset")
            assertTrue(field.text.endsWith(" $dictation"))
            assertTrue(field.longestAsked >= FieldWindow.BEFORE + dictation.length)
        }
    }

    @Test
    fun `a long dictation cut off by the field is reported`() {
        val dictation = longText(3 * FieldWindow.BEFORE, from = 500)
        val field = ConnectionField(longText(2 * FieldWindow.BEFORE), reformat = { it.take(500) })
        val outcome = engine().insert(field, dictation)

        assertFalse(outcome.insertedIntoField)
        assertTrue(outcome.uncertain)
    }

    @Test
    fun `a connection that says where its windows begin is compared by that`() {
        val lost = ConnectionField("How are you. $oneWindow", saysOffset = true, commitLost = true)
        assertFalse(engine().insert(lost, "how are you").insertedIntoField)

        val arrived = ConnectionField("How are you. $oneWindow", saysOffset = true)
        assertTrue(engine().insert(arrived, "how are you").insertedIntoField)
    }

    @Test
    fun `typed over a selection holding the same words counts as typed`() {
        val field = ConnectionField("See you tomorrow at 5")
        field.select(0, 16)
        val outcome = engine().insert(field, "see you tomorrow")

        assertTrue(outcome.insertedIntoField)
        assertEquals("See you tomorrow at 5", field.text)
    }

    @Test
    fun `a field changed under the dictation is not taken for it`() {
        val field = ConnectionField("Hello there.", commitLost = true)
        val engine = InsertionEngine(
            memory = StrategyMemory(),
            clipboard = Clipboard { true },
            // The app empties its field while the words are on their way and never shows them.
            pause = { field.rewrite("") },
        )

        assertFalse(engine.insert(field, "how are you").insertedIntoField)
    }

    // --- nothing to read -----------------------------------------------------------------

    @Test
    fun `a connection that hands out nothing gets the words as they are, unchecked`() {
        val field = ConnectionField("Hello there.", answers = false)
        val outcome = engine().insert(field, "  how are you  ")

        assertTrue(outcome.insertedIntoField)
        assertEquals(listOf("how are you"), field.typed)
        assertEquals(1, field.reads, "nothing to read back either")
    }

    @Test
    fun `a field that only takes key presses is not read at all`() {
        // Input type "none": a terminal. An empty answer there is not an empty field.
        val field = ConnectionField(blind = true, showsText = false)
        val outcome = engine().insert(field, "git status")

        assertTrue(outcome.insertedIntoField)
        assertEquals(listOf("git status"), field.typed)
        assertEquals(0, field.reads)
    }

    @Test
    fun `a field that reads empty before and after cannot be checked, so nothing is reported lost`() {
        // A web editor drawn on a canvas: it calls itself a text field and shows no text.
        val pauses = mutableListOf<Long>()
        val field = ConnectionField(blind = true)
        val outcome = engine(pauses = pauses).insert(field, "hello there")

        assertTrue(outcome.insertedIntoField)
        assertFalse(outcome.uncertain)
        assertEquals(1, field.typed.size)
        assertEquals(0, pauses.size)
        assertNull(onClipboard)
    }

    @Test
    fun `nothing but typing is ever tried through a connection`() {
        // A window written back as the whole field would cut the field down to it.
        val field = ConnectionField(longText(2 * FieldWindow.BEFORE))
        assertFalse(field.setText("anything"))
        assertFalse(field.paste())

        val gone = object : WindowedTarget() {
            override val packageName = "com.anthropic.claude"
            override fun read(before: Int, after: Int) = FieldWindow("Hello", 5, offset = 0)
            override fun type(text: String) = false
        }
        val outcome = engine().insert(gone, "there")

        assertFalse(outcome.insertedIntoField)
        assertEquals(InsertionStrategy.CLIPBOARD_ONLY, outcome.strategy)
        assertEquals("there", onClipboard)
    }

    @Test
    fun `line breaks go in only where Enter makes a new line`() {
        val field = ConnectionField("")
        engine().insert(field, "Einkaufen:\n- Milch")

        assertEquals("Einkaufen: - Milch", field.text)
    }
}
