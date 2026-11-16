package app.amethyst.core

import app.amethyst.core.text.ScratchThat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScratchThatTest {

    private fun kept(text: String): String = ScratchThat.apply(text).also { assertTrue(it.scratched, text) }.text

    @Test
    fun `the phrase at the end takes back the sentence before it`() {
        assertEquals("Ich komme um drei.", kept("Ich komme um drei. Nein, um vier. Streich das."))
        assertEquals("I'll be there at three.", kept("I'll be there at three. Actually at four. Scratch that."))
        assertEquals("Hallo Anna! Wie geht's?", kept("Hallo Anna! Wie geht's? Das Wetter ist schlecht, streich das"))
    }

    @Test
    fun `in any case and with any marks around it`() {
        assertEquals("Erster Satz.", kept("Erster Satz. Zweiter Satz. streich das!"))
        assertEquals("First one.", kept("First one. Second one, SCRATCH THAT"))
        assertEquals("First one.", kept("First one. Second one. Scratch   that ."))
    }

    @Test
    fun `said twice, two sentences go`() {
        assertEquals("Eins.", kept("Eins. Zwei. Drei. Streich das. Streich das."))
    }

    @Test
    fun `a dictation of one sentence, or of the phrase alone, leaves nothing`() {
        assertEquals("", kept("Ich komme um drei streich das"))
        assertEquals("", kept("Ich komme um drei, streich das."))
        assertEquals("", kept("Streich das."))
        assertEquals("", kept("scratch that"))
    }

    @Test
    fun `a line break ends a sentence too`() {
        assertEquals("Liebe Anna,", kept("Liebe Anna,\nich komme später. Streich das."))
    }

    @Test
    fun `a decimal point is no sentence end`() {
        assertEquals("Der Preis ist hoch.", kept("Der Preis ist hoch. Er liegt bei 3.5 Prozent. Streich das."))
    }

    @Test
    fun `only at the very end, only these words`() {
        listOf(
            "Streich das Zimmer blau.",
            "Ich streich das morgen.",
            "Scratch that itch.",
            "Ach, vergiss das.",
            "Lösch das.",
            "Delete that.",
            "Bitte nicht durchstreichen das ist wichtig",
            "Unterstreich das",
            "I scratched that.",
        ).forEach { text ->
            val result = ScratchThat.apply(text)
            assertFalse(result.scratched, text)
            assertEquals(text, result.text)
        }
    }
}
