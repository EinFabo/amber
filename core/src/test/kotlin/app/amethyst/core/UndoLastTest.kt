package app.amethyst.core

import app.amethyst.core.text.FieldState
import app.amethyst.core.insert.UndoLast
import app.amethyst.core.insert.UndoLast.Match
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UndoLastTest {

    private val last = UndoLast.Insertion("com.whatsapp", " bis morgen um vier.")

    @Test
    fun `the dictation right before the cursor is taken out, seam and all`() {
        assertEquals(20, UndoLast.charsToDelete("Okay, bis morgen um vier.", last))
        assertEquals(20, UndoLast.charsToDelete("Okay, Bis morgen um vier.", last), "a field may capitalize")
    }

    @Test
    fun `anything typed or changed since leaves the field alone`() {
        assertNull(UndoLast.charsToDelete("Okay, bis morgen um vier. Danke", last))
        assertNull(UndoLast.charsToDelete("Okay, bis morgen um fünf.", last))
        assertNull(UndoLast.charsToDelete("vier.", last))
        assertNull(UndoLast.charsToDelete("abc", UndoLast.Insertion("x", "")))
    }

    @Test
    fun `a whole-field write gives what it added, if it only added`() {
        val before = FieldState("Hallo Welt", 5)
        assertEquals(" liebe", UndoLast.added(before, "Hallo liebe Welt", 11))
        // A splice that pulled a space or punctuation back before the cursor changed old text.
        assertNull(UndoLast.added(FieldState("Hallo ", 6), "Hallo, du", 9))
        assertNull(UndoLast.added(before, "Hallo Welt", 5), "nothing added")
    }

    /** A 42 s dictation in five chunks, joined with single spaces (ChunkedTranscription.join). */
    private val chunks = listOf(
        "Ich wollte dir nur kurz sagen, dass ich heute etwas später komme, weil der Zug wieder einmal ausgefallen ist und ich auf den nächsten warten muss.",
        "Der fährt erst in einer halben Stunde, also bin ich wahrscheinlich gegen halb acht da, wenn nichts mehr dazwischenkommt.",
        "Kannst du bitte schon mal den Ofen vorheizen und die Kartoffeln aufsetzen, dann sind wir schneller fertig, wenn ich ankomme.",
        "Und falls Paul anruft, sag ihm bitte, dass ich mich morgen früh bei ihm melde, heute schaffe ich das nicht mehr.",
        "Bis später, ich freue mich auf dich.",
    )
    private val long = UndoLast.Insertion("com.google.android.apps.messaging", " " + chunks.joinToString(" "))

    @Test
    fun `a long dictation of several chunks is taken out whole`() {
        val field = "Hallo Schatz," + long.fragment
        assertEquals(long.fragment.length, UndoLast.charsToDelete(field, long))
        assertEquals(Match.Found(long.fragment.length), UndoLast.match(field, long))
    }

    @Test
    fun `a field that only changed the spacing still gives the dictation back`() {
        val prefix = "Hallo Schatz,"
        // A field that turned a space into a line break, a no-break space, two spaces.
        val respaced = (prefix + long.fragment).replaceFirst(". Der", ".\nDer").replaceFirst("halben Stunde", "halben\u00A0Stunde").replaceFirst("Ofen", " Ofen")
        assertEquals(respaced.length - prefix.length, UndoLast.charsToDelete(respaced, long))
        // A field that trimmed the seam at its very start.
        val trimmed = long.fragment.trimStart()
        assertEquals(trimmed.length, UndoLast.charsToDelete(trimmed, long))
        // A trailing space the field dropped.
        val spaced = UndoLast.Insertion("x", " bis morgen ")
        assertEquals(11, UndoLast.charsToDelete("Okay, bis morgen", spaced))
    }

    @Test
    fun `spacing never stands in for words or the user's own text`() {
        // The seam's space gone between the user's text and the dictation: something changed.
        assertNull(UndoLast.charsToDelete("Hallo Schatz," + long.fragment.trimStart(), long))
        // Words run together, or the user typed a space after the dictation.
        assertNull(UndoLast.charsToDelete("Okay, bis morgenum vier.", last))
        assertNull(UndoLast.charsToDelete("Okay, bis morgen um vier. ", last))
        // One word changed in the middle of a long text.
        assertEquals(Match.Different, UndoLast.match("Hallo Schatz," + long.fragment.replace("Kartoffeln", "Nudeln"), long))
    }

    @Test
    fun `a read that stops before the dictation's start says so`() {
        // An input connection handing back the last 300 characters before the cursor only.
        val field = "Hallo Schatz," + long.fragment
        assertEquals(Match.TooShort, UndoLast.match(field.takeLast(300), long))
        assertNull(UndoLast.charsToDelete(field.takeLast(300), long))
        assertEquals(Match.TooShort, UndoLast.match("vier.", last))
        assertEquals(Match.Different, UndoLast.match("abc", UndoLast.Insertion("x", "")))
    }
}
