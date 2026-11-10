package app.amethyst.core

import app.amethyst.core.insert.Landed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LandedTest {

    private val long = (1..40).joinToString(" ") { "Das ist der Satz $it von vielen." }

    @Test
    fun `a short text must be there as a whole, case and spacing aside`() {
        assertTrue(Landed.inField("", "Hallo  Welt\n", "hallo welt"))
        assertTrue(Landed.inField("Gut. ", "Gut. Wie geht es dir", "wie geht\nes dir"))
        assertFalse(Landed.inField("", "Hallo", "Hallo Welt"))
    }

    @Test
    fun `words that were there before do not count as arrived`() {
        assertFalse(Landed.inField("Danke", "Danke", "Danke"))
        assertTrue(Landed.inField("Danke", "Danke Danke", "Danke"))
    }

    @Test
    fun `an unknown field before only asks that the words are there`() {
        assertTrue(Landed.inField(null, "Hallo Welt", "Hallo Welt"))
    }

    @Test
    fun `a long text may change in the middle but not lose its end`() {
        val middleChanged = long.replace("Satz 20 von", "Satz 🙂 20 von")
        assertTrue(Landed.inField("", middleChanged, long))
        assertFalse(Landed.inField("", long.take(long.length - 30), long), "cut off")
        assertFalse(Landed.inField("", long.drop(30), long), "start missing")
    }

    @Test
    fun `a long text already in the field must arrive once more`() {
        assertFalse(Landed.inField(long, long, long))
        val again = long + " " + long.replace("Satz 20 von", "Satz 20 :) von")
        assertTrue(Landed.inField(long, again, long))
    }

    @Test
    fun `short texts are not given the long text's leniency`() {
        val short = "Ein kurzer Satz, der in der Mitte anders ankommt als gesagt."
        assertFalse(Landed.inField("", short.replace("Mitte", "MITTE!"), short))
    }

    @Test
    fun `the wait grows with the text and stops at about three seconds`() {
        assertEquals(12, Landed.attempts(12, 50))
        assertEquals(12, Landed.attempts(12, Landed.LONG_TEXT))
        assertEquals(20, Landed.attempts(12, 1_000))
        assertEquals(Landed.MAX_ATTEMPTS, Landed.attempts(12, 50_000))
        assertEquals(5, Landed.attempts(5, 100))
    }
}
