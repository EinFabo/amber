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
    fun `what the node shows while its editor holds nothing is a placeholder`() {
        // WhatsApp's and Telegram's message fields hand out their placeholder as text, neither
        // saying they show a hint nor naming it (Pixel 9a, 2026-11-26).
        assertEquals("", FieldText.contents("Message", null, showingHint = false, selectionStart = -1, selectionEnd = -1, editorEmpty = true))
        assertEquals("", FieldText.contents("Nachricht", null, showingHint = false, selectionStart = 9, selectionEnd = 9, editorEmpty = true))
        assertEquals("", FieldText.contents("Message", "Message", showingHint = false, selectionStart = 7, selectionEnd = 7, editorEmpty = true))
    }

    @Test
    fun `an editor that holds text, or cannot be asked, leaves the node's text as it is`() {
        assertEquals("Message", FieldText.contents("Message", "Message", showingHint = false, selectionStart = 7, selectionEnd = 7, editorEmpty = false))
        assertEquals("Hallo", FieldText.contents("Hallo", null, showingHint = false, selectionStart = 5, selectionEnd = 5, editorEmpty = null))
        // A field with a hint of its own holds real text when it shows another: an editor that
        // hands out nothing does not empty it.
        assertEquals("Hallo", FieldText.contents("Hallo", "Message", showingHint = false, selectionStart = 5, selectionEnd = 5, editorEmpty = true))
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

    @Test
    fun `the framework's autocomplete fields are plain EditTexts that name themselves`() {
        // Android 15's AutoCompleteTextView (search boxes, address fields) and every
        // MultiAutoCompleteTextView (recipient fields) report their own class name.
        assertFalse(FieldText.setTextFlattens("android.widget.AutoCompleteTextView", hasSpans = false))
        assertFalse(FieldText.setTextFlattens("android.widget.MultiAutoCompleteTextView", hasSpans = false))
        assertTrue(FieldText.setTextFlattens("android.widget.AutoCompleteTextView", hasSpans = true))
    }
}
