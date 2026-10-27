package app.nocturne.core

import app.nocturne.core.text.FieldState
import app.nocturne.core.text.SpliceOptions
import app.nocturne.core.text.TextSplicer
import kotlin.test.Test
import kotlin.test.assertEquals

class TextSplicerTest {

    @Test
    fun `inserts into an empty field and capitalizes`() {
        val result = TextSplicer.splice(FieldState.EMPTY, "hallo welt")
        assertEquals("Hallo welt", result.text)
        assertEquals(10, result.cursor)
    }

    @Test
    fun `appends with a separating space`() {
        val field = FieldState("Guten Morgen", 12)
        val result = TextSplicer.splice(field, "wie geht es dir")
        assertEquals("Guten Morgen wie geht es dir", result.text)
        assertEquals(result.text.length, result.cursor)
    }

    @Test
    fun `does not double an existing trailing space`() {
        val result = TextSplicer.splice(FieldState("Hallo ", 6), "Welt")
        assertEquals("Hallo Welt", result.text)
    }

    @Test
    fun `glues punctuation onto the previous word`() {
        val result = TextSplicer.splice(FieldState("Hallo Welt ", 11), ", wie geht es")
        assertEquals("Hallo Welt, wie geht es", result.text)
    }

    @Test
    fun `capitalizes after a sentence ending`() {
        val result = TextSplicer.splice(FieldState("Das war gut.", 12), "das war schlecht")
        assertEquals("Das war gut. Das war schlecht", result.text)
    }

    @Test
    fun `keeps lower case mid-sentence`() {
        val result = TextSplicer.splice(FieldState("Das war", 7), "gut")
        assertEquals("Das war gut", result.text)
    }

    @Test
    fun `replaces the selection`() {
        val field = FieldState("Hallo schoene Welt", selectionStart = 6, selectionEnd = 13)
        val result = TextSplicer.splice(field, "graue")
        assertEquals("Hallo graue Welt", result.text)
        assertEquals(11, result.cursor)
    }

    @Test
    fun `inserts in the middle and separates the following word`() {
        val field = FieldState("Hallo Welt", 5)
        val result = TextSplicer.splice(field, "liebe")
        assertEquals("Hallo liebe Welt", result.text)
        // Cursor sits right after the dictated word, not after the separator.
        assertEquals(11, result.cursor)
    }

    @Test
    fun `adds no space after an opening bracket`() {
        val result = TextSplicer.splice(FieldState("Hinweis (", 9), "wichtig")
        assertEquals("Hinweis (wichtig", result.text)
    }

    @Test
    fun `blank dictation leaves the field untouched`() {
        val field = FieldState("Hallo Welt", selectionStart = 2, selectionEnd = 7)
        val result = TextSplicer.splice(field, "   ")
        assertEquals("Hallo Welt", result.text)
        assertEquals(7, result.cursor)
    }

    @Test
    fun `respects disabled options`() {
        val options = SpliceOptions(autoSpace = false, autoCapitalize = false, gluePunctuation = false)
        val result = TextSplicer.splice(FieldState("Hallo", 5), "welt", options)
        assertEquals("Hallowelt", result.text)
    }

    @Test
    fun `clamps an out-of-range selection`() {
        val result = TextSplicer.splice(FieldState("Hi", selectionStart = 99, selectionEnd = 120), "da")
        assertEquals("Hi da", result.text)
    }
}
