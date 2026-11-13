package app.amethyst.core

import app.amethyst.core.ui.Contrast
import kotlin.test.Test
import kotlin.test.assertEquals

class ContrastTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun `black on white is 21 to 1, either way round`() {
        assertEquals(21.0, Contrast.ratio(black, white), 0.001)
        assertEquals(21.0, Contrast.ratio(white, black), 0.001)
    }

    @Test
    fun `a colour on itself is 1 to 1`() {
        assertEquals(1.0, Contrast.ratio(0xFFFFB020.toInt(), 0xFFFFB020.toInt()), 0.001)
    }

    @Test
    fun `the grey that just passes AA on white`() {
        // #767676 on white is the textbook 4.54:1.
        assertEquals(4.54, Contrast.ratio(0xFF767676.toInt(), white), 0.01)
    }

    @Test
    fun `a see-through colour is mixed with what is behind it`() {
        assertEquals(0xFF808080.toInt(), Contrast.over(0x80FFFFFF.toInt(), black))
        assertEquals(white, Contrast.over(white, black))
        assertEquals(black, Contrast.over(0x00FFFFFF, black))
    }
}
