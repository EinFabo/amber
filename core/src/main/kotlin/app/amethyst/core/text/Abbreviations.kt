package app.amethyst.core.text

/**
 * Whether a text ends in an abbreviation, whose "." is no sentence end ("etc.", "z. B.",
 * "Dr."). Unlike ScratchThat's list this one holds the abbreviations that often end a sentence
 * too ("etc.", "usw."): here a wrong "yes" only keeps a full stop, which is the safe side.
 */
object Abbreviations {

    private val KNOWN = setOf(
        "dr", "prof", "hr", "fr", "frl", "nr", "st", "z.b", "zb", "d.h", "dh", "bzw", "ca", "evtl", "ggf", "inkl",
        "exkl", "bspw", "vgl", "sog", "z.t", "z.hd", "tel", "mio", "mrd", "abs", "geb", "allg", "ehem", "eigtl",
        "gem", "zzgl", "u.u", "v.a", "usw", "etc", "u.a", "o.ä", "u.ä", "s.o", "s.u", "usf",
        "mr", "mrs", "ms", "e.g", "i.e", "vs", "approx", "dept", "a.m", "p.m", "inc", "ltd", "jr", "sr",
    )

    /** [text] ends in an abbreviation with its "." or in a single letter with one ("z. B.", "J."). */
    fun endsWith(text: String): Boolean {
        val trimmed = text.trimEnd()
        if (!trimmed.endsWith('.')) return false
        val word = trimmed.dropLast(1).takeLastWhile { !it.isWhitespace() }.trimStart { !it.isLetterOrDigit() }
        if (word.length == 1 && word[0].isLetter()) return true
        return word.lowercase() in KNOWN
    }
}
