package app.amethyst.core

import app.amethyst.core.text.FieldState
import app.amethyst.core.insert.UndoLast
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
}
