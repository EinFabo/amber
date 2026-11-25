package app.amethyst.core

import app.amethyst.core.text.FieldKind
import app.amethyst.core.text.FieldState
import app.amethyst.core.text.SpliceOptions
import app.amethyst.core.text.TextSplicer
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

    private fun en(text: String, cursor: Int, chunk: String, kind: FieldKind = FieldKind.TEXT) =
        TextSplicer.splice(FieldState(text, cursor), chunk, SpliceOptions(language = "en", fieldKind = kind)).text

    private fun de(text: String, cursor: Int, chunk: String, kind: FieldKind = FieldKind.TEXT) =
        TextSplicer.splice(FieldState(text, cursor), chunk, SpliceOptions(language = "de", fieldKind = kind)).text

    @Test
    fun `a common English word mid-sentence loses the recognizer's capital`() {
        assertEquals("Meet me at the station.", en("Meet me at ", 11, "The station."))
        assertEquals("Meet me at the station.", en("Meet me at", 10, "The station."))
        assertEquals("Well, and then", en("Well,", 5, "And then"))
    }

    @Test
    fun `a German noun mid-sentence keeps its capital, a German small word loses it`() {
        assertEquals("Ich gehe zum Bahnhof.", de("Ich gehe zum ", 13, "Bahnhof."))
        assertEquals("Ich gehe zum Bahnhof.", de("Ich gehe", 8, "Zum Bahnhof."))
        assertEquals("Danke, ich komme", de("Danke,", 6, "Ich komme"))
    }

    @Test
    fun `names, acronyms and I keep their capitals mid-sentence`() {
        assertEquals("We flew to The Hague.", en("We flew to ", 11, "The Hague."))
        assertEquals("I work at NASA.", en("I work at ", 10, "NASA."))
        assertEquals("I work in IT.", en("I work in ", 10, "IT."))
        assertEquals("Then I said so.", en("Then ", 5, "I said so."))
        assertEquals("We met Anna.", en("We met ", 7, "Anna."))
    }

    @Test
    fun `a sentence start keeps the capital`() {
        assertEquals("Done. The station.", en("Done.", 5, "The station."))
        assertEquals("Hello\nThe station", en("Hello\n", 6, "the station"), "a new line starts a sentence")
        assertEquals("He said \" The end", en("He said \"", 9, "The end"), "after a quote it may be a sentence of its own")
    }

    @Test
    fun `turning capitals off keeps the recognizer's capital`() {
        val options = SpliceOptions(autoCapitalize = false, language = "en")
        assertEquals("Meet me at The station.", TextSplicer.splice(FieldState("Meet me at ", 11), "The station.", options).text)
    }

    @Test
    fun `without a sure language only words common in both languages are lowered`() {
        val unsure = SpliceOptions(language = null)
        assertEquals("Meet me in Berlin.", TextSplicer.splice(FieldState("Meet me ", 8), "In Berlin.", unsure).text)
        assertEquals("Meet me at The station.", TextSplicer.splice(FieldState("Meet me at ", 11), "The station.", unsure).text)
    }

    @Test
    fun `a full stop before the rest of the sentence goes`() {
        val result = TextSplicer.splice(FieldState("tomorrow", 0), "See you.", SpliceOptions(language = "en"))
        assertEquals("See you tomorrow", result.text)
        assertEquals(7, result.cursor)
        assertEquals("See you tomorrow", en(" tomorrow", 0, "See you."))
        assertEquals("Bis morgen", de("morgen", 0, "Bis."))
        assertEquals("See you, then", en(", then", 0, "See you."), "no stop before a comma")
    }

    @Test
    fun `a full stop before a new sentence or a new line stays`() {
        assertEquals("See you. Tomorrow we go.", en("Tomorrow we go.", 0, "See you."))
        assertEquals("See you.\ntomorrow", en("\ntomorrow", 0, "See you."))
        assertEquals("See you.", en("", 0, "See you."))
        assertEquals("Wait... tomorrow", en("tomorrow", 0, "Wait..."), "an ellipsis is no full stop")
    }

    @Test
    fun `a search field gets no capital and no full stop`() {
        assertEquals("pizza near me", en("", 0, "pizza near me.", FieldKind.SEARCH))
        assertEquals("pizza near me", en("", 0, "Pizza near me.", FieldKind.SEARCH))
        assertEquals("NASA launches", en("", 0, "NASA launches.", FieldKind.SEARCH))
        assertEquals("I want pizza", en("", 0, "I want pizza.", FieldKind.SEARCH))
        // A German noun keeps its capital, a German small word does not.
        assertEquals("Pizza in der Nähe", de("", 0, "Pizza in der Nähe.", FieldKind.SEARCH))
        assertEquals("die beste Pizza", de("", 0, "Die beste Pizza.", FieldKind.SEARCH))
    }

    @Test
    fun `an address field gets no capital and no full stop`() {
        assertEquals("example.com", de("", 0, "Example.com.", FieldKind.URI))
        assertEquals("fabi@example.com", de("", 0, "Fabi@example.com.", FieldKind.EMAIL))
        assertEquals("NASA.gov", en("", 0, "NASA.gov.", FieldKind.URI))
    }

    @Test
    fun `the words are what the splice puts between the text around them`() {
        val field = FieldState("Meet me at tomorrow", 11)
        val options = SpliceOptions(language = "en")
        assertEquals("the station", TextSplicer.words(field, " The station. ", options))
        assertEquals("Meet me at the station tomorrow", TextSplicer.splice(field, "The station.", options).text)
    }
}
