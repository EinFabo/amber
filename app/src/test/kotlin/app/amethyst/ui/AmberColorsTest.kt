package app.amethyst.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.amethyst.core.ui.Contrast
import app.amethyst.ui.theme.AmberColors
import org.junit.Assert.assertTrue
import org.junit.Test

/** Both themes read: every text colour on every ground it is put on, measured rather than judged by eye. */
class AmberColorsTest {

    private val themes = listOf("dark" to AmberColors.Dark, "light" to AmberColors.Light)

    /** The grounds text stands on: the page, a card, a raised part, and each with the amber tint of a selected row. */
    private fun grounds(c: AmberColors): Map<String, Int> {
        val plain = mapOf("background" to c.background, "surface" to c.surface, "elevated" to c.elevated).mapValues { it.value.toArgb() }
        return plain + plain.map { (name, ground) -> "deep on $name" to Contrast.over(c.deep.toArgb(), ground) }
    }

    private fun check(what: String, colour: Color, ground: Int, least: Double, theme: String, on: String) {
        val ratio = Contrast.ratio(colour.toArgb(), ground)
        assertTrue("$theme: $what on $on is %.2f:1, below %.1f:1".format(ratio, least), ratio >= least)
    }

    @Test
    fun `text, muted text, amber and the error colour hold AA on every ground`() {
        for ((theme, c) in themes) {
            for ((on, ground) in grounds(c)) {
                check("text", c.text, ground, Contrast.AA_TEXT, theme, on)
                check("amber", c.primary, ground, Contrast.AA_TEXT, theme, on)
                // Notes and errors stand on the plain grounds, never on a selected row's tint.
                if (!on.startsWith("deep")) {
                    check("muted", c.muted, ground, Contrast.AA_TEXT, theme, on)
                    check("error", c.error, ground, Contrast.AA_TEXT, theme, on)
                }
            }
        }
    }

    @Test
    fun `a button's label reads on amber and on the error colour`() {
        for ((theme, c) in themes) {
            check("background", c.background, c.primary.toArgb(), Contrast.AA_TEXT, theme, "amber")
            check("background", c.background, c.error.toArgb(), Contrast.AA_TEXT, theme, "error")
        }
    }

    @Test
    fun `the trust dots stand out from the page and the cards`() {
        for ((theme, c) in themes) {
            for (on in listOf("background" to c.background, "surface" to c.surface, "elevated" to c.elevated)) {
                check("on-device dot", c.trustOnDevice, on.second.toArgb(), Contrast.AA_LARGE, theme, on.first)
                check("cloud dot", c.trustCloud, on.second.toArgb(), Contrast.AA_LARGE, theme, on.first)
            }
        }
    }
}
