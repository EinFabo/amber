package app.amethyst.core

import app.amethyst.core.text.LanguageGuess
import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageGuessTest {

    @Test
    fun `a tag naming one language gives its code`() {
        assertEquals("de", LanguageGuess.fromTag("de"))
        assertEquals("de", LanguageGuess.fromTag("de-DE"))
        assertEquals("de", LanguageGuess.fromTag("de_AT"))
        assertEquals("de", LanguageGuess.fromTag("german"))
        assertEquals("en", LanguageGuess.fromTag("English"))
        assertEquals("en", LanguageGuess.fromTag(" en-US "))
        assertEquals("fr", LanguageGuess.fromTag("fr"))
    }

    @Test
    fun `a list, nothing or something unknown gives none`() {
        assertEquals(null, LanguageGuess.fromTag("de,en"))
        assertEquals(null, LanguageGuess.fromTag(null))
        assertEquals(null, LanguageGuess.fromTag(""))
        assertEquals(null, LanguageGuess.fromTag("auto"))
        assertEquals(null, LanguageGuess.fromTag("xx-YY"))
    }

    @Test
    fun `the words decide only when they are clear`() {
        assertEquals("de", LanguageGuess.fromWords("Ich komme heute nicht, aber morgen."))
        assertEquals("en", LanguageGuess.fromWords("I think we should go there with you."))
        // Mixed: an English term in German is German.
        assertEquals("de", LanguageGuess.fromWords("Kannst du den Pull Request bis morgen reviewen, das wäre gut."))
        assertEquals("en", LanguageGuess.fromWords("See you later."))
        // Too short to tell.
        assertEquals(null, LanguageGuess.fromWords("Okay, danke."))
        assertEquals(null, LanguageGuess.fromWords("Bis gleich."))
        assertEquals(null, LanguageGuess.fromWords("Passt."))
        // Half and half.
        assertEquals(null, LanguageGuess.fromWords("ich und du and you and me und wir"))
    }

    @Test
    fun `clear words first, as the cleanup guard reads them, then detected, then the engine's one language`() {
        // An engine set to German that heard English: the prompt must not ask for German - the
        // model would translate, and the guard, going by the words, would throw the reply away.
        assertEquals("en", LanguageGuess.forDictation("See you later, I think.", null, "de-DE"))
        assertEquals("de", LanguageGuess.forDictation("Ich komme heute nicht, aber morgen.", null, "en-US"))
        assertEquals("de", LanguageGuess.forDictation("Ich komme heute nicht, aber morgen.", "english", "en-US"))
        // Words too few to tell: what the engine detected, else the one language it was set to.
        assertEquals("en", LanguageGuess.forDictation("Okay, cool.", "english", "de-DE"))
        assertEquals("de", LanguageGuess.forDictation("Okay, danke.", null, "de-DE"))
        assertEquals("de", LanguageGuess.forDictation("Ich komme heute nicht, aber morgen.", null, "de,en"))
        assertEquals(null, LanguageGuess.forDictation("Okay, danke.", null, "de,en"))
    }

    @Test
    fun `a language the few words lean away from is not named`() {
        // Short English from an engine set to German: "Reply in German" would translate it.
        assertEquals(null, LanguageGuess.forDictation("Sounds good to me.", null, "de-DE"))
        assertEquals(null, LanguageGuess.forDictation("Sounds good to me.", "german", null))
        assertEquals(null, LanguageGuess.forDictation("Okay, danke.", "english", "de-DE"))
        // Leaning the same way, or not at all: the engine's language stands.
        assertEquals("de", LanguageGuess.forDictation("Passt, danke.", null, "de-DE"))
        assertEquals("de", LanguageGuess.forDictation("Termin verschoben.", null, "de-DE"))
        assertEquals("fr", LanguageGuess.forDictation("Sounds good to me.", null, "fr"))
    }

    @Test
    fun `the words lean by their markers, however few`() {
        assertEquals("en", LanguageGuess.leaning("Sounds good to me."))
        assertEquals("de", LanguageGuess.leaning("Okay, danke."))
        assertEquals(null, LanguageGuess.leaning("Okay, cool."))
        assertEquals(null, LanguageGuess.leaning("danke, thanks"))
    }

    @Test
    fun `names for the prompt`() {
        assertEquals("German", LanguageGuess.nameOf("de"))
        assertEquals("German", LanguageGuess.nameOf("de-DE"))
        assertEquals("English", LanguageGuess.nameOf("en"))
        assertEquals(null, LanguageGuess.nameOf("de,en"))
        assertEquals(null, LanguageGuess.nameOf(null))
    }
}
