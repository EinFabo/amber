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

    @Test
    fun `a spoken sentence end counts before the spoken punctuation turns it into a mark`() {
        assertEquals("Hallo Anna Punkt", kept("Hallo Anna Punkt ich komme später Punkt streich das"))
        assertEquals("Kommst du Fragezeichen", kept("Kommst du Fragezeichen ich warte Punkt streich das"))
        assertEquals("See you then period", kept("See you then period I'm late period scratch that"))
        assertEquals("Liebe Anna Komma neue Zeile", kept("Liebe Anna Komma neue Zeile ich komme später streich das"))
        // Said right before the phrase, it ends the sentence that goes.
        assertEquals("Erster Satz Punkt", kept("Erster Satz Punkt zweiter Satz Punkt streich das"))
    }

    @Test
    fun `only as a sentence of its own - not after a word that makes it part of a sentence`() {
        listOf(
            "Wer streicht die Wand? Ich streich das.",
            "Bitte streich das.",
            "Bitte, streich das.",
            "Dann streich das.",
            "Und streich das",
            "Don't scratch that.",
            "Don’t scratch that.",
            "Please scratch that.",
            "I will scratch that",
            "Du kannst die Tür lassen, aber ich streich das",
        ).forEach { text ->
            val result = ScratchThat.apply(text)
            assertFalse(result.scratched, text)
            assertEquals(text, result.text)
        }
    }

    @Test
    fun `a sentence end, a comma, the start or an ordinary word before it`() {
        assertEquals("Ich komme.", kept("Ich komme. Doch nicht, streich das."))
        assertEquals("", kept("Ich komme um drei streich das"))
        assertEquals("Erste Zeile", kept("Erste Zeile\nzweite Zeile\nstreich das"))
    }

    @Test
    fun `a known abbreviation ends no sentence`() {
        assertEquals("", kept("Dr. Müller kommt. Streich das."))
        assertEquals("Hallo.", kept("Hallo. Dr. Müller kommt um drei. Streich das."))
        assertEquals("Wir brauchen Obst.", kept("Wir brauchen Obst. Z.B. Äpfel und Birnen. Streich das."))
        assertEquals("Wir brauchen Obst.", kept("Wir brauchen Obst. Ein paar, z. B. Äpfel. Streich das."))
        assertEquals("Hi.", kept("Hi. Mr. Smith will call. Scratch that."))
        assertEquals("Hi.", kept("Hi. Bring fruit, e.g. apples. Scratch that."))
        assertEquals("Hi.", kept("Hi. J. Smith will call. Scratch that."))
    }

    @Test
    fun `a dot before a word in lower case or a German date ends no sentence`() {
        assertEquals("Hallo.", kept("Hallo. Es kostet ca. drei Euro. Streich das."))
        assertEquals("Termine:", kept("Termine:\nAm 3. Mai komme ich. Streich das."))
        assertEquals("Ich komme am 1. Mai.", kept("Ich komme am 1. Mai. Er kommt am 5. Juni. Streich das."))
    }

    @Test
    fun `a sentence ending in an abbreviation that often ends one still ends there`() {
        assertEquals("We meet at 5 p.m.", kept("We meet at 5 p.m. Then lunch. Scratch that."))
        assertEquals("Äpfel, Birnen usw.", kept("Äpfel, Birnen usw. Dann Brot. Streich das."))
    }

    // --- As Parakeet may write it (Pixel 9a, 2026-11-16: only the phrase went, the sentence stayed)

    @Test
    fun `Fabi's two dictations, however the recognizer set the marks`() {
        listOf(
            "Ich komme um drei. Streich das.",
            "Ich komme um drei, streich das.",
            "Ich komme um drei streich das",
            "ich komme um drei streich das",
            "Ich komme um drei. Streich das!",
            "Ich komme um drei. streich das",
            "Ich komme um drei.\nStreich das.",
            "Ich komme um drei Punkt streich das",
        ).forEach { assertEquals("", kept(it), it) }

        listOf(
            "Hallo Anna. Ich komme um drei. Streich das.",
            "Hallo Anna. Ich komme um drei, streich das.",
            "Hallo Anna. Ich komme um drei streich das",
            "Hallo Anna! Ich komme um drei. Streich das!",
        ).forEach { assertTrue(kept(it).startsWith("Hallo Anna"), it) }
        assertEquals("Hallo Anna.", kept("Hallo Anna. Ich komme um drei. Streich das."))
        assertEquals("Hallo Anna Punkt", kept("Hallo Anna Punkt ich komme um drei Punkt streich das"))
    }

    @Test
    fun `the phrase misheard - streiche, dass, daß`() {
        listOf(
            "Ich komme um drei. Streiche das.",
            "Ich komme um drei. Streich dass.",
            "Ich komme um drei, streich daß",
            "Ich komme um drei streiche dass",
        ).forEach { assertEquals("", kept(it), it) }
        assertEquals("Hallo.", kept("Hallo. Ich komme um drei. Streiche das."))
    }

    @Test
    fun `streicht das only as a sentence of its own`() {
        assertEquals("Hallo.", kept("Hallo. Ich komme um drei. Streicht das."))
        assertEquals("", kept("Streicht das."))
        listOf(
            "Ich komme um drei, streicht das.",
            "Anna streicht das.",
            "Der Maler streicht das.",
            "Wer streicht die Wand? Anna streicht das.",
            "Ihr streicht das.",
        ).forEach { text ->
            val result = ScratchThat.apply(text)
            assertFalse(result.scratched, text)
            assertEquals(text, result.text)
        }
    }

    @Test
    fun `the variants are sentences too after a subject or a request`() {
        listOf(
            "Ich streiche das.",
            "Ich streich dass",
            "Bitte streiche das.",
            "Bitte, streich dass.",
            "Dann streiche das",
            "Wer streicht die Wand? Ich streich das.",
            "Wer streicht die Wand? Ich streiche das.",
        ).forEach { text ->
            val result = ScratchThat.apply(text)
            assertFalse(result.scratched, text)
            assertEquals(text, result.text)
            assertEquals(ScratchThat.Verdict.PART_OF_A_SENTENCE, result.verdict, text)
        }
    }

    @Test
    fun `what it came to, for the diagnostics`() {
        assertEquals(ScratchThat.Verdict.NOT_SAID, ScratchThat.apply("Ich komme um drei.").verdict)
        assertEquals(ScratchThat.Verdict.NOT_SAID, ScratchThat.apply("Streich das Zimmer blau.").verdict)
        assertEquals(ScratchThat.Verdict.LOOKS_LIKE_IT, ScratchThat.apply("Ich komme um drei. Streichen das.").verdict)
        assertEquals(ScratchThat.Verdict.LOOKS_LIKE_IT, ScratchThat.apply("I'm late. Scratched that.").verdict)
        ScratchThat.apply("Eins. Zwei. Drei. Streich das. Streich das.").let {
            assertEquals(ScratchThat.Verdict.TOOK_BACK, it.verdict)
            assertEquals(2, it.sentences)
        }
    }

    @Test
    fun `nothing left but marks counts as nothing left`() {
        assertTrue(ScratchThat.apply("Ich komme um drei streich das").nothingLeft)
        assertTrue(ScratchThat.apply("Streich das.").nothingLeft)
        assertFalse(ScratchThat.apply("Hallo. Ich komme. Streich das.").nothingLeft)
        assertFalse(ScratchThat.apply("Hallo.").nothingLeft, "nothing scratched, nothing taken away")
    }
}
