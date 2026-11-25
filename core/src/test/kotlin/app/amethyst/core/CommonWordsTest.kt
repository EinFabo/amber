package app.amethyst.core

import app.amethyst.core.text.CommonWords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommonWordsTest {

    @Test
    fun `small English words are common, whatever their sentence capital`() {
        assertTrue(CommonWords.isCommonLowerCase("The", "en"))
        assertTrue(CommonWords.isCommonLowerCase("the", "en"))
        assertTrue(CommonWords.isCommonLowerCase("And", "en-US"))
        assertTrue(CommonWords.isCommonLowerCase("Don't", "en"))
    }

    @Test
    fun `acronyms, shouting and I keep their capitals`() {
        assertFalse(CommonWords.isCommonLowerCase("IT", "en"), "IT is no \"it\"")
        assertFalse(CommonWords.isCommonLowerCase("US", "en"))
        assertFalse(CommonWords.isCommonLowerCase("THE", "en"))
        assertFalse(CommonWords.isCommonLowerCase("I", "en"))
        assertFalse(CommonWords.isCommonLowerCase("I'm", "en"))
    }

    @Test
    fun `German small words are common, German nouns and the formal you never`() {
        assertTrue(CommonWords.isCommonLowerCase("Der", "de"))
        assertTrue(CommonWords.isCommonLowerCase("Zum", "de-AT"))
        assertTrue(CommonWords.isCommonLowerCase("Ich", "de"))
        assertFalse(CommonWords.isCommonLowerCase("Bahnhof", "de"))
        assertFalse(CommonWords.isCommonLowerCase("Sie", "de"), "the formal you is written with a capital")
        assertFalse(CommonWords.isCommonLowerCase("Ihr", "de"))
        assertFalse(CommonWords.isCommonLowerCase("Ihnen", "de"))
    }

    @Test
    fun `each language has its own words`() {
        assertFalse(CommonWords.isCommonLowerCase("Der", "en"))
        assertFalse(CommonWords.isCommonLowerCase("The", "de"))
        assertFalse(CommonWords.isCommonLowerCase("The", "fr"), "no list for other languages")
        assertFalse(CommonWords.isCommonLowerCase("In", "fr,es"))
    }

    @Test
    fun `without a sure language only words common in both count`() {
        assertTrue(CommonWords.isCommonLowerCase("In", null))
        assertTrue(CommonWords.isCommonLowerCase("In", "de,en"))
        assertFalse(CommonWords.isCommonLowerCase("The", null))
        assertFalse(CommonWords.isCommonLowerCase("Die", null), "\"Die Hard\" in an English text")
    }

    @Test
    fun `lowers the leading word of a text when it is common`() {
        assertEquals("the station.", CommonWords.lowerLeading("The station.", "en"))
        assertEquals("der Bahnhof", CommonWords.lowerLeading("Der Bahnhof", "de"))
        assertEquals("Bahnhof.", CommonWords.lowerLeading("Bahnhof.", "de"))
        assertEquals("NASA called.", CommonWords.lowerLeading("NASA called.", "en"))
        assertEquals("I said so.", CommonWords.lowerLeading("I said so.", "en"))
    }

    @Test
    fun `keeps names that start with a common word`() {
        assertEquals("The Hague.", CommonWords.lowerLeading("The Hague.", "en"))
        assertEquals("A. Smith wrote.", CommonWords.lowerLeading("A. Smith wrote.", "en"), "an initial")
        assertEquals("\"The end\"", CommonWords.lowerLeading("\"The end\"", "en"), "a quote may be a sentence of its own")
        // Before a name, a preposition is still lower-case.
        assertEquals("in Berlin.", CommonWords.lowerLeading("In Berlin.", "en"))
    }
}
