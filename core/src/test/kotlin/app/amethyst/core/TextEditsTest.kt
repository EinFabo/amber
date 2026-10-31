package app.amethyst.core

import app.amethyst.core.commands.TextEdits
import app.amethyst.core.text.FieldState
import kotlin.test.Test
import kotlin.test.assertEquals

class TextEditsTest {

    @Test
    fun `the last word goes, the space before it stays`() {
        assertEquals(FieldState("Hallo ", 6), TextEdits.deleteWords(FieldState("Hallo Welt", 10), 1))
        assertEquals(FieldState("", 0), TextEdits.deleteWords(FieldState("Hallo ", 6), 1))
    }

    @Test
    fun `punctuation and trailing spaces go with the word`() {
        assertEquals(FieldState("Wir sehen uns ", 14), TextEdits.deleteWords(FieldState("Wir sehen uns morgen.  ", 23), 1))
    }

    @Test
    fun `several words at once`() {
        assertEquals(FieldState("Wir ", 4), TextEdits.deleteWords(FieldState("Wir sehen uns morgen", 20), 3))
        assertEquals(FieldState("", 0), TextEdits.deleteWords(FieldState("Wir sehen", 9), 20))
    }

    @Test
    fun `only words before the cursor, what follows it stays`() {
        assertEquals(FieldState("Hallo  Welt", 6), TextEdits.deleteWords(FieldState("Hallo schöne Welt", 12), 1))
    }

    @Test
    fun `line breaks separate words`() {
        assertEquals(FieldState("Liebe Grüße\n", 12), TextEdits.deleteWords(FieldState("Liebe Grüße\nFabi", 16), 1))
        assertEquals(FieldState("Liebe ", 6), TextEdits.deleteWords(FieldState("Liebe Grüße\n", 12), 1))
    }

    @Test
    fun `a selection is never deleted - the word before it is`() {
        // "schöne" selected: the word before it goes, the selection moves with the text.
        assertEquals(FieldState("schöne Welt", 0, 6), TextEdits.deleteWords(FieldState("Hallo schöne Welt", 6, 12), 1))
        // Everything selected: nothing lies before it, nothing goes.
        assertEquals(FieldState("Hallo Welt", 0, 10), TextEdits.deleteWords(FieldState("Hallo Welt", 0, 10), 1))
    }

    @Test
    fun `nothing before the cursor, nothing goes`() {
        assertEquals(FieldState("Hallo", 0), TextEdits.deleteWords(FieldState("Hallo", 0), 1))
        assertEquals(FieldState("", 0), TextEdits.deleteWords(FieldState.EMPTY, 1))
    }

    @Test
    fun `a cursor out of range is held to the text`() {
        assertEquals(FieldState("Hallo ", 6), TextEdits.deleteWords(FieldState("Hallo Welt", 99), 1))
    }

    @Test
    fun `how much to delete before the cursor, for an input connection`() {
        assertEquals(4, TextEdits.charsBeforeCursor(FieldState("Hallo Welt", 10), 1))
        assertEquals(10, TextEdits.charsBeforeCursor(FieldState("Hallo Welt", 10), 2))
        assertEquals(0, TextEdits.charsBeforeCursor(FieldState("Hallo Welt", 0), 1))
        // Before the selection, never into it.
        assertEquals(6, TextEdits.charsBeforeCursor(FieldState("Hallo schöne Welt", 6, 12), 1))
    }

    @Test
    fun `a new line goes in at the cursor, after a selection rather than over it`() {
        assertEquals(FieldState("Hallo\n", 6), TextEdits.newLine(FieldState("Hallo", 5)))
        assertEquals(FieldState("Hallo\n Welt", 6), TextEdits.newLine(FieldState("Hallo Welt", 5)))
        assertEquals(FieldState("Hallo Welt\n", 11), TextEdits.newLine(FieldState("Hallo Welt", 0, 10)))
    }
}
