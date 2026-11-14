package app.amethyst.core

import app.amethyst.core.insert.FieldWindow
import app.amethyst.core.text.FieldState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FieldWindowTest {

    // --- what the connection hands out ---------------------------------------------------

    @Test
    fun `a connection that hands out no text gives no window`() {
        assertNull(FieldWindow.of(null, 0, 0, offset = -1, askedBefore = 1_000))
    }

    @Test
    fun `a selection that is not in the text gives no window`() {
        assertNull(FieldWindow.of("Hello", -1, -1, offset = -1, askedBefore = 1_000))
        assertNull(FieldWindow.of("Hello", 2, 9, offset = -1, askedBefore = 1_000))
    }

    @Test
    fun `an empty field is a window with nothing in it`() {
        val window = FieldWindow.of("", 0, 0, offset = -1, askedBefore = 1_000)

        assertEquals(FieldWindow("", 0, 0, offset = 0), window)
        assertEquals(FieldState.EMPTY, window?.state)
    }

    @Test
    fun `where the connection says where the window begins, that is taken`() {
        assertEquals(4_000, FieldWindow.of("x".repeat(1_000), 1_000, 1_000, offset = 4_000, askedBefore = 1_000)?.offset)
    }

    @Test
    fun `a window shorter than asked for begins at the field's start`() {
        assertEquals(0, FieldWindow.of("Hello there.", 12, 12, offset = -1, askedBefore = 1_000)?.offset)
    }

    @Test
    fun `a window as long as asked for may begin anywhere`() {
        assertNull(FieldWindow.of("x".repeat(1_000), 1_000, 1_000, offset = -1, askedBefore = 1_000)?.offset)
    }

    @Test
    fun `the selection keeps its direction`() {
        val window = FieldWindow.of("Hello there", 11, 6, offset = -1, askedBefore = 1_000)

        assertEquals(FieldState("Hello there", 11, 6), window?.state)
        assertEquals("Hello ", window?.head)
    }

    @Test
    fun `half an emoji at the window's start is left out`() {
        // The window began in the middle of the pair: its second half is no character.
        val emoji = "😀"
        val cut = emoji.substring(1) + " ok"
        val window = FieldWindow.of(cut, cut.length, cut.length, offset = 500, askedBefore = 4)

        assertEquals(FieldWindow(" ok", 3, 3, offset = 501), window)
    }

    @Test
    fun `half an emoji at the window's end is left out`() {
        val emoji = "😀"
        val cut = "ok " + emoji.substring(0, 1)
        val window = FieldWindow.of(cut, 2, 2, offset = -1, askedBefore = 1_000)

        assertEquals(FieldWindow("ok ", 2, 2, offset = 0), window)
    }

    @Test
    fun `a whole emoji next to the cursor stays`() {
        val text = "😀"
        assertEquals(text, FieldWindow.of(text, 2, 2, offset = -1, askedBefore = 1_000)?.text)
        // Half of one at the field's own start, the cursor before it, is the field's business.
        assertEquals("\uDE00", FieldWindow.of("\uDE00", 0, 0, offset = -1, askedBefore = 1_000)?.text)
    }

    // --- the read after typing, from where the first one began ---------------------------

    @Test
    fun `both windows from the field's start need no cutting`() {
        val before = FieldWindow("Hello there.", 12, offset = 0)
        val after = FieldWindow("Hello there. How are you", 24, offset = 0)

        assertEquals("Hello there. How are you", FieldWindow.readBack(before, after))
    }

    @Test
    fun `what the later window shows on top is cut off by where they begin`() {
        val onTop = "How are you. Where "
        val before = FieldWindow("is it. ", 7, offset = 100)
        val after = FieldWindow(onTop + "is it. How are you", onTop.length + 18, offset = 100 - onTop.length)

        assertEquals("is it. How are you", FieldWindow.readBack(before, after))
    }

    @Test
    fun `a later window that begins further on is taken as it is`() {
        val before = FieldWindow("abcdef", 6, offset = 100)
        val after = FieldWindow("def typed", 9, offset = 103)

        assertEquals("def typed", FieldWindow.readBack(before, after))
    }

    @Test
    fun `text cut away before the first window's start leaves nothing to compare`() {
        val before = FieldWindow("abcdef", 6, offset = 100)
        val after = FieldWindow("ab", 2, offset = 10)

        assertEquals("", FieldWindow.readBack(before, after))
    }

    @Test
    fun `without a word on where they begin, the text before the cursor says it`() {
        val head = "x".repeat(20) + " and so on. "
        val before = FieldWindow(head, head.length)
        val after = FieldWindow("How are you. $head" + "How are you", ("How are you. $head" + "How are you").length)

        assertEquals(head + "How are you", FieldWindow.readBack(before, after))
    }

    @Test
    fun `nothing typed and nothing on top is counted when the words never arrived`() {
        val head = "x".repeat(20) + " and so on. "
        val before = FieldWindow(head, head.length)
        // Read further back, the same words show up - from long ago, not from now.
        val after = FieldWindow("How are you. $head", ("How are you. $head").length)

        assertEquals(head, FieldWindow.readBack(before, after))
    }

    @Test
    fun `a selection typed over is not part of what is looked for`() {
        val before = FieldWindow("See you at five tomorrow", 11, 15)
        val after = FieldWindow("Much earlier. See you at six tomorrow", 28)

        assertEquals("See you at six tomorrow", FieldWindow.readBack(before, after))
    }

    @Test
    fun `where the text before the cursor changed, no more is kept than there was`() {
        val before = FieldWindow("0123456789", 10)
        // The app rewrote what stood there; where the first window began is anyone's guess.
        val after = FieldWindow("typed long ago. 01234-6789 typed", 32)

        assertEquals("6789 typed", FieldWindow.readBack(before, after))
    }

    @Test
    fun `a field that lost text keeps what is left`() {
        val before = FieldWindow("0123456789", 10)
        val after = FieldWindow("0123", 4)

        assertEquals("0123", FieldWindow.readBack(before, after))
    }

    @Test
    fun `the read after typing reaches back by what was typed`() {
        assertEquals(FieldWindow.BEFORE + 3_000 + 32, FieldWindow.beforeAfterTyping(3_000))
        assertEquals(FieldWindow.BEFORE + 32, FieldWindow.beforeAfterTyping(-1))
    }
}
