package app.amethyst.core

import app.amethyst.core.insert.FieldText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FieldTextTest {

    @Test
    fun `a field that says it shows its hint is empty`() {
        assertEquals("", FieldText.contents("Message", "Message", showingHint = true, selectionStart = 0, selectionEnd = 0))
    }

    @Test
    fun `text equal to the hint with the cursor at the start is the placeholder`() {
        assertEquals("", FieldText.contents("Type a note", "Type a note", showingHint = false, selectionStart = 0, selectionEnd = 0))
        assertEquals("", FieldText.contents("Type a note", "Type a note", showingHint = false, selectionStart = -1, selectionEnd = -1))
    }

    @Test
    fun `the hint's words typed by someone, cursor behind them, stay`() {
        assertEquals("Message", FieldText.contents("Message", "Message", showingHint = false, selectionStart = 7, selectionEnd = 7))
    }

    @Test
    fun `other text stays, and no text reads as empty`() {
        assertEquals("Hallo", FieldText.contents("Hallo", "Message", showingHint = false, selectionStart = 0, selectionEnd = 0))
        assertEquals("", FieldText.contents(null, "Message", showingHint = false, selectionStart = 0, selectionEnd = 0))
        assertEquals("Hallo", FieldText.contents("Hallo", null, showingHint = false, selectionStart = 0, selectionEnd = 0))
        assertEquals("", FieldText.contents("", "", showingHint = false, selectionStart = 0, selectionEnd = 0))
    }

    @Test
    fun `rich text or a field that is no EditText would be flattened by set text`() {
        assertTrue(FieldText.setTextFlattens(FieldText.EDIT_TEXT, hasSpans = true))
        assertTrue(FieldText.setTextFlattens("android.view.View", hasSpans = false))
        assertFalse(FieldText.setTextFlattens(FieldText.EDIT_TEXT, hasSpans = false))
        assertFalse(FieldText.setTextFlattens(null, hasSpans = false), "an unknown class says nothing")
    }
}
