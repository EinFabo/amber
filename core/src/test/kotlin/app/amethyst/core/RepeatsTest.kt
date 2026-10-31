package app.amethyst.core

import app.amethyst.core.commands.Repeats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepeatsTest {

    private fun extract(text: String) = Repeats.extract(text.split(' '))

    @Test
    fun `digits and number words from one to twenty in both languages`() {
        val german = listOf(
            "eins", "zwei", "drei", "vier", "funf", "sechs", "sieben", "acht", "neun", "zehn",
            "elf", "zwolf", "dreizehn", "vierzehn", "funfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn", "zwanzig",
        )
        val english = listOf(
            "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
        )
        german.forEachIndexed { i, word -> assertEquals(i + 1, Repeats.number(word), word) }
        english.forEachIndexed { i, word -> assertEquals(i + 1, Repeats.number(word), word) }
        assertEquals(2, Repeats.number("zwo"))
        assertEquals(1, Repeats.number("ein"))
        assertEquals(7, Repeats.number("7"))
        assertEquals(35, Repeats.number("35"))
        assertNull(Repeats.number("runter"))
        assertNull(Repeats.number("null"))
    }

    @Test
    fun `a count with mal or times is taken out`() {
        assertEquals(Repeats.Counted(10, listOf("scroll", "nach", "unten")), extract("scroll 10 mal nach unten"))
        assertEquals(Repeats.Counted(5, listOf("swipe", "down")), extract("swipe 5 times down"))
        assertEquals(Repeats.Counted(3, listOf("runter")), extract("drei mal runter"))
        assertEquals(Repeats.Counted(2, listOf("go", "back")), extract("go back two times"))
        assertEquals(Repeats.Counted(4, listOf("runter")), extract("4 x runter"))
    }

    @Test
    fun `fused counts as speech engines write them`() {
        assertEquals(Repeats.Counted(10, listOf("runter")), extract("zehnmal runter"))
        assertEquals(Repeats.Counted(3, listOf("scroll", "runter")), extract("scroll dreimal runter"))
        assertEquals(Repeats.Counted(2, listOf("zuruck")), extract("zweimal zuruck"))
        assertEquals(Repeats.Counted(1, listOf("hoch")), extract("einmal hoch"))
        assertEquals(Repeats.Counted(2, listOf("zuruck")), extract("zwomal zuruck"))
        assertEquals(Repeats.Counted(12, listOf("runter")), extract("12mal runter"))
        assertEquals(Repeats.Counted(3, listOf("runter")), extract("3x runter"))
        assertEquals(Repeats.Counted(2, listOf("back")), extract("back twice"))
        assertEquals(Repeats.Counted(1, listOf("back")), extract("back once"))
        assertEquals(Repeats.Counted(3, listOf("up")), extract("thrice up"))
    }

    @Test
    fun `a number before what it counts keeps that word`() {
        assertEquals(Repeats.Counted(3, listOf("worter", "loschen")), extract("drei worter loschen"))
        assertEquals(Repeats.Counted(2, listOf("seiten", "runter")), extract("2 seiten runter"))
        assertEquals(Repeats.Counted(3, listOf("schritte", "zuruck")), extract("drei schritte zuruck"))
        assertEquals(Repeats.Counted(1, listOf("seite", "runter")), extract("eine seite runter"))
    }

    @Test
    fun `numbers that count nothing stay where they are`() {
        assertEquals(Repeats.Counted(null, listOf("wir", "sehen", "uns", "um", "acht")), extract("wir sehen uns um acht"))
        assertEquals(Repeats.Counted(null, listOf("ein", "bisschen", "runter")), extract("ein bisschen runter"))
        assertEquals(Repeats.Counted(null, listOf("scroll", "mal", "runter")), extract("scroll mal runter"))
        assertEquals(Repeats.Counted(null, listOf("normal", "manchmal")), extract("normal manchmal"))
    }

    @Test
    fun `a zero count is kept so it can be refused`() {
        assertEquals(Repeats.Counted(0, listOf("runter")), extract("0 mal runter"))
    }

    @Test
    fun `a count past twenty is unreadable only when the whole word is a number`() {
        listOf(
            "dreissigmal", "einundzwanzigmal", "hundertmal", "dreihundert", "zweihundertfunfzig", "neunzehnhundert",
            "tausend", "fifty", "thousand", "hundred", "99999999999", "99999999999mal",
        ).forEach { assertTrue(Repeats.unreadable(it), it) }
        // Words that merely contain a number word, and counts that are read.
        listOf(
            "jahrhundert", "tausendfussler", "hunderte", "witzig", "und", "zwanzig", "zehnmal", "12mal", "normal", "mal", "",
        ).forEach { assertFalse(Repeats.unreadable(it), it) }
    }
}
