package app.amethyst.core

import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.SettingsCodec
import app.amethyst.core.text.LanguageGuess
import app.amethyst.core.text.NormalizeOptions
import app.amethyst.core.text.TranscriptNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spoken punctuation through the whole normalizer, as a dictation gets it: in German text the
 * German words, in English text the English ones - and the many places the same words are
 * ordinary words and stay.
 */
class SpokenPunctuationTest {

    private val on = NormalizeOptions(spokenPunctuation = true)

    private fun de(raw: String, keepLineBreaks: Boolean = false) =
        TranscriptNormalizer.normalize(raw, on, language = "de", keepLineBreaks = keepLineBreaks)

    private fun en(raw: String, keepLineBreaks: Boolean = false) =
        TranscriptNormalizer.normalize(raw, on, language = "en", keepLineBreaks = keepLineBreaks)

    // --- German: what becomes a mark ---------------------------------------------------

    @Test
    fun `german marks with the spacing right`() {
        assertEquals("Hallo, wie geht's", de("Hallo Komma wie geht's"))
        assertEquals("Ich komme morgen.", de("Ich komme morgen Punkt"))
        assertEquals("Kommst du heute?", de("Kommst du heute Fragezeichen"))
        assertEquals("Das ist super!", de("Das ist super Ausrufezeichen"))
        assertEquals("Einkaufen: Milch und Eier", de("Einkaufen Doppelpunkt Milch und Eier"))
    }

    @Test
    fun `a sentence's end brings a capital, a comma does not`() {
        assertEquals("Ich komme morgen. Bis dann", de("Ich komme morgen Punkt bis dann"))
        assertEquals("Kommst du? Ich warte", de("Kommst du Fragezeichen ich warte"))
        assertEquals("Toll! Danke", de("Toll Ausrufezeichen danke"))
        assertEquals("Hallo, wie geht's", de("Hallo Komma wie geht's"))
    }

    @Test
    fun `a mark the recognizer put beside the word is taken in, not doubled`() {
        assertEquals("Hallo, wie geht's?", de("Hallo, Komma, wie geht's?"))
        assertEquals("Hallo, wie geht's?", de("Hallo Komma, wie geht's?"))
        assertEquals("Ich komme morgen.", de("Ich komme morgen. Punkt."))
        assertEquals("Ich komme morgen.", de("Ich komme morgen Punkt."))
        assertEquals("Wirklich?", de("Wirklich, Fragezeichen?"))
    }

    @Test
    fun `two spoken marks in a row stay both`() {
        assertEquals("Wirklich?!", de("Wirklich Fragezeichen Ausrufezeichen"))
    }

    @Test
    fun `line breaks and paragraphs`() {
        assertEquals("Liebe Anna,\nwie geht's?", de("Liebe Anna Komma neue Zeile wie geht's?"))
        assertEquals("Hallo.\n\nZweiter Absatz", de("Hallo Punkt neuer Absatz zweiter Absatz"))
        assertEquals("Einkaufen:\nMilch\nEier", de("Einkaufen Doppelpunkt neue Zeile Milch neue Zeile Eier"))
        // The recognizer's period after the words goes; what stands before the break stays.
        assertEquals("Danke,\nAnna", de("Danke, neue Zeile. Anna"))
        // After a comma a German letter goes on in lower case.
        assertEquals("Hallo,\nich bin's", de("Hallo, neue Zeile ich bin's"))
    }

    @Test
    fun `a raw transcript's own line breaks still become spaces - only spoken ones stay`() {
        assertEquals("Hallo Welt.\nNeu", de("Hallo\nWelt Punkt neue Zeile neu"))
    }

    @Test
    fun `a spoken comma or colon at the very end stays - for the next dictation`() {
        assertEquals("Liebe Anna,", de("Liebe Anna Komma"))
        assertEquals("Einkaufsliste:", de("Einkaufsliste Doppelpunkt"))
        // A comma the recognizer left at the end still goes.
        assertEquals("Liebe Anna", de("Liebe Anna,"))
    }

    @Test
    fun `case does not matter in German`() {
        assertEquals("Hallo, du", de("Hallo komma du"))
        assertEquals("Hallo.", de("Hallo PUNKT"))
    }

    // --- German: what stays a word -----------------------------------------------------

    @Test
    fun `german words inside other words stay`() {
        listOf(
            "Das ist der Mittelpunkt",
            "Wir treffen uns am Treffpunkt",
            "Die Doppelpunkt-Notation ist praktisch",
            "Das war ein Kommafehler",
            "Der Kommandant kommt",
        ).forEach { assertEquals(it, de(it)) }
    }

    @Test
    fun `german nouns after an article, a possessive, a quantifier or a preposition stay`() {
        listOf(
            "Da fehlt ein Komma",
            "Setz das Komma hinter Anna",
            "Lass uns zum Punkt kommen",
            "Das bringt es auf den Punkt",
            "Das ist mein Punkt",
            "Da steht kein Fragezeichen",
            "Hinter dem Doppelpunkt steht nichts",
            "Schreib das ohne Komma",
            "Bis Punkt drei bin ich da",
            "Fang eine neue Zeile an",
            "Das kommt in die neue Zeile",
            "Jede neue Zeile ist zu viel",
            "Ein neuer Absatz wäre gut",
            "Da sind viele Fragezeichen",
        ).forEach { assertEquals(it, de(it)) }
    }

    @Test
    fun `german nouns after an adjective stay`() {
        listOf(
            "Guter Punkt",
            "Das ist ein wichtiger Punkt",
            "Das ist der springende Punkt",
            "Da ist ein sehr großes Fragezeichen",
            "Nächster Punkt ist das Budget",
            "Wir sind am toten Punkt",
        ).forEach { assertEquals(it, de(it)) }
    }

    @Test
    fun `german numbers around Komma and Punkt stay - a decimal, a time`() {
        listOf(
            "Das kostet drei Komma fünf Euro",
            "Wir treffen uns um Punkt acht",
            "Er kam Punkt 12 Uhr",
            "Er kam Punkt halb drei",
        ).forEach { assertEquals(it, de(it)) }
    }

    @Test
    fun `a number's comma stays - after a number word, before one put together`() {
        listOf(
            "Das war in null Komma nichts erledigt",
            "Das ging in null Komma nix",
            "Es sind eins Komma fünfundzwanzig Liter",
            "Es sind drei Komma vierundsechzig Prozent",
            "Er lief zehn Komma zweihundert Meter",
            "Das sind zwanzig Komma acht",
            "It was one comma twenty-five",
        ).forEach { assertEquals(it, if (it.startsWith("It")) en(it) else de(it)) }
        // A number at a sentence's end still takes its period.
        assertEquals("Wir sehen uns um acht. Bis dann", de("Wir sehen uns um acht Punkt bis dann"))
        assertEquals("Ich bin fünfundzwanzig.", de("Ich bin fünfundzwanzig Punkt"))
        // "und" and "ein" alone are no numbers.
        assertEquals("Äpfel und Birnen, bitte", de("Äpfel und Birnen Komma bitte"))
    }

    @Test
    fun `Punkt before a time stays`() {
        listOf(
            "Wir treffen uns um Punkt Mitternacht",
            "Er kam Punkt Viertel nach acht",
            "Sie kam um Punkt Mittag",
            "Wir essen Punkt dreiviertel sieben",
            "Er kam Punkt neunzehn Uhr",
        ).forEach { assertEquals(it, de(it)) }
    }

    @Test
    fun `german words talked about, or a noun with what follows, stay`() {
        assertEquals("Das Wort Komma ist schwer", de("Das Wort Komma ist schwer"))
        assertEquals("Wir gehen Punkt für Punkt durch", de("Wir gehen Punkt für Punkt durch"))
    }

    @Test
    fun `nothing before the word - nothing to end`() {
        assertEquals("Punkt eins der Liste", de("Punkt eins der Liste"))
        assertEquals("Komma", de("Komma"))
        assertEquals("Neue Zeile beginnt hier", de("Neue Zeile beginnt hier"))
    }

    @Test
    fun `a verb's part before the mark does not stop it`() {
        assertEquals("Ruf mich an.", de("Ruf mich an Punkt"))
        assertEquals("Kommst du mit?", de("Kommst du mit Fragezeichen"))
        assertEquals("Wir sehen uns morgen.", de("Wir sehen uns morgen Punkt"))
    }

    // --- English: what becomes a mark --------------------------------------------------

    @Test
    fun `english marks with the spacing right`() {
        assertEquals("Hello, how are you", en("Hello comma how are you"))
        assertEquals("See you then.", en("See you then period"))
        assertEquals("See you then.", en("See you then full stop"))
        assertEquals("Are you coming?", en("Are you coming question mark"))
        assertEquals("That is great!", en("That is great exclamation mark"))
        assertEquals("That is great!", en("That is great exclamation point"))
        assertEquals("Shopping: milk and eggs", en("Shopping colon milk and eggs"))
    }

    @Test
    fun `english breaks start the next line with a capital`() {
        assertEquals("Dear Anna,\nHow are you?", en("Dear Anna comma new line how are you?"))
        assertEquals("Thanks.\n\nSecond part", en("Thanks period new paragraph second part"))
    }

    @Test
    fun `english period mid-text only before a new sentence`() {
        assertEquals("I'm home. See you", en("I'm home period. See you"))
        assertEquals("I'm home. See you", en("I'm home period See you"))
        assertEquals("I'm done.", en("I'm done. Period."))
        assertEquals("Call me back.", en("Call me back period"))
        // A lower-case word after it: perhaps a noun of the sentence; it stays.
        assertEquals("I'm home period see you", en("I'm home period see you"))
    }

    @Test
    fun `english words that a recognizer already wrapped in marks`() {
        assertEquals("Hello, how are you?", en("Hello, comma, how are you?"))
        assertEquals("Are you coming?", en("Are you coming, question mark?"))
    }

    // --- English: what stays a word ----------------------------------------------------

    @Test
    fun `english nouns stay`() {
        listOf(
            "Add a comma here",
            "I love the Oxford comma",
            "We are in a trial period",
            "It was a difficult period.",
            "It was a very long period.",
            "The period ends today",
            "The period of time was short",
            "That leaves a big question mark",
            "The car came to a full stop",
            "They launched a new line of shoes",
            "Start a new paragraph",
            "The doctor checked the colon",
            "He has colon cancer",
            "Use comma separated values",
            "It's three comma five percent",
            "During that period we met",
            "My period is late",
        ).forEach { assertEquals(it, en(it)) }
    }

    @Test
    fun `english words with a capital mid-sentence are names`() {
        assertEquals("I work at Comma now", en("I work at Comma now"))
        assertEquals("Watch the film by New Line soon", en("Watch the film by New Line soon"))
        assertEquals("Ask Mr Colon about it", en("Ask Mr Colon about it"))
    }

    // --- the language, the switch, the field -------------------------------------------

    @Test
    fun `only the dictation's language's words become marks`() {
        assertEquals("Hello Komma how are you", en("Hello Komma how are you"))
        assertEquals("Hallo comma wie geht's", de("Hallo comma wie geht's"))
        // Several languages: the words of the text decide.
        assertEquals(
            "Ich komme morgen, ich bin da",
            TranscriptNormalizer.normalize("Ich komme morgen Komma ich bin da", on, language = "de,en"),
        )
    }

    @Test
    fun `switched off, the words stay as heard - today's behaviour`() {
        val off = NormalizeOptions()
        assertEquals("Hallo Komma wie geht's", TranscriptNormalizer.normalize("Hallo Komma wie geht's", off, language = "de"))
        assertEquals("See you then period", TranscriptNormalizer.normalize("See you then period", off, language = "en"))
        assertEquals(
            "Liebe Anna neue Zeile wie geht's",
            TranscriptNormalizer.normalize("Liebe Anna neue Zeile wie geht's", off, language = "de"),
        )
    }

    @Test
    fun `a cleaned text keeps its lines and gets the spoken ones too`() {
        assertEquals("Hallo Anna,\nwie geht's?\n\nBis bald", de("Hallo Anna,\nwie geht's? neuer Absatz bis bald", keepLineBreaks = true))
    }

    @Test
    fun `a single-line field gets a space for a spoken break`() {
        assertEquals("Liebe Anna, wie geht's?", TranscriptNormalizer.oneLine(de("Liebe Anna Komma neue Zeile wie geht's?")))
    }

    @Test
    fun `the setting is on by default, also for settings from before it`() {
        assertTrue(AmethystSettings().normalizeOptions().spokenPunctuation)
        val old = SettingsCodec.encode(AmethystSettings()).replace(Regex(",\\s*\"spokenPunctuation\":\\s*true"), "")
        assertFalse("spokenPunctuation" in old)
        assertTrue(SettingsCodec.decode(old, listOf("de-DE")).words.spokenPunctuation)
        assertFalse(AmethystSettings().let { it.copy(words = it.words.copy(spokenPunctuation = false)) }.normalizeOptions().spokenPunctuation)
    }

    // --- Numbers in digits (Pixel 9a, 2026-11-16: "Punkt 2" came out as ".2") -----------

    @Test
    fun `Punkt and Komma before a number in digits stay words`() {
        listOf(
            "Das ist Punkt 2",
            "Siehe Punkt 2 der Liste",
            "Wir sprechen über Punkt 2 und Punkt 3",
            "Er kam Punkt 12 Uhr",
            "Wir treffen uns Punkt 8",
            "Seite 3 Punkt 4",
            "Kapitel 3 Punkt 2 ist neu",
            "Das war Komma 5",
            "Ich meine Punkt 2, nicht Punkt 3",
            "Das ist Punkt 2.",
            "Das ist Punkt 2a",
            "Das ist Punkt 2-3",
            "Das ist Punkt 10%",
        ).forEach { assertEquals(it, de(it), it) }
        // The recognizer's own marks next to the word change nothing.
        assertEquals("Das ist Punkt, 2", de("Das ist Punkt, 2"))
        assertEquals("Das ist Punkt. 2", de("Das ist Punkt. 2"))
        // In English, too.
        listOf("It is period 2 today", "See comma 5", "Version 3 period 2").forEach { assertEquals(it, en(it), it) }
    }

    @Test
    fun `no dot is ever glued to a number`() {
        val said = listOf(
            "Punkt 2", "Das ist Punkt 2", "das ist Punkt 2 Punkt", "Punkt 2 Punkt 3", "Seite 3 Punkt 4 Punkt",
            "Erstens Punkt 1 dann Punkt 2", "Kapitel Punkt 2", "Mach Punkt 2 neue Zeile Punkt 3",
        )
        val glued = Regex("""\.\p{N}""")
        for (raw in said) {
            for (formatted in listOf(false, true)) {
                val out = de(raw, keepLineBreaks = formatted)
                assertFalse(glued.containsMatchIn(out), "$raw -> $out")
            }
        }
    }

    @Test
    fun `a German decimal in digits is written as one`() {
        assertEquals("Das sind 3,5 Prozent", de("Das sind 3 Komma 5 Prozent"))
        assertEquals("Es kostet 12,50 Euro.", de("Es kostet 12 Komma 50 Euro Punkt"))
        assertEquals("Die Note ist 1,3", de("Die Note ist 1 komma 3"))
        // Words, the recognizer's marks, or more than a number around it: left as said.
        listOf(
            "Das kostet drei Komma fünf Euro",
            "Das sind 3, Komma, 5 Prozent",
            "Das sind 3.5 Komma 5 Prozent",
            "Seite 3 Punkt 4",
        ).forEach { assertEquals(it, de(it), it) }
        // Not in English, where a decimal takes a point.
        assertEquals("It was 3 comma 5", en("It was 3 comma 5"))
    }

    /**
     * Parakeet detects the language itself, so the hint is the phone's: on a phone set to
     * English alone, "en-DE". Normalized as English, German "Komma" stayed a word and "um"
     * went as an English filler; the words decide first, as they do for the cleanup.
     */
    @Test
    fun `German words on an English phone are normalized as German`() {
        val raw = "Hallo Anna Komma ich komme um drei Fragezeichen"
        fun normalized(hint: String?) = TranscriptNormalizer.normalize(raw, on, language = LanguageGuess.forDictation(raw, null, hint))

        assertEquals("Hallo Anna, ich komme um drei?", normalized("en-DE"))
        assertEquals("Hallo Anna, ich komme um drei?", normalized("en-DE,de-DE"))
        assertEquals("Hallo Anna, ich komme um drei?", normalized(null))
        // Before, the phone's English alone settled it.
        assertEquals("Hallo Anna Komma ich komme drei Fragezeichen", TranscriptNormalizer.normalize(raw, on, language = "en-DE"))
        // Short, with few small words to go by: still German, the English hint contradicted.
        val short = "Ich komme um drei Punkt"
        assertEquals("Ich komme um drei.", TranscriptNormalizer.normalize(short, on, language = LanguageGuess.forDictation(short, null, "en-DE")))
        // English words stay English with a German hint.
        val english = "I think we should meet at the station comma right"
        assertEquals(
            "I think we should meet at the station, right",
            TranscriptNormalizer.normalize(english, on, language = LanguageGuess.forDictation(english, null, "de-DE")),
        )
    }
}
