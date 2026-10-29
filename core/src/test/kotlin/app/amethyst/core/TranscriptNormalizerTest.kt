package app.amethyst.core

import app.amethyst.core.text.NormalizeOptions
import app.amethyst.core.text.TranscriptNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals

class TranscriptNormalizerTest {

    @Test
    fun `strips hesitation sounds including attached punctuation`() {
        assertEquals(
            "ich denke das passt",
            TranscriptNormalizer.normalize("ähm, ich denke äh das passt"),
        )
    }

    @Test
    fun `um and eh are words in German and fillers only in English`() {
        assertEquals(
            "Wir treffen uns um 10 Uhr, um zu reden, das ist eh klar.",
            TranscriptNormalizer.normalize("Wir treffen uns um 10 Uhr, um zu reden, das ist eh klar.", language = "de"),
        )
        // Told by the words alone when the engine names no language.
        assertEquals("Ich gehe raus, um einzukaufen", TranscriptNormalizer.normalize("Ich gehe raus um einzukaufen"))
        assertEquals("I think we should go", TranscriptNormalizer.normalize("um I think we should uh go", language = "en"))
    }

    @Test
    fun `keeps words that merely contain a filler`() {
        assertEquals("ähnlich umgehen", TranscriptNormalizer.normalize("ähnlich umgehen"))
    }

    @Test
    fun `collapses whitespace and newlines`() {
        assertEquals("eins zwei drei", TranscriptNormalizer.normalize("eins   zwei\n\ndrei"))
    }

    @Test
    fun `removes the space before punctuation`() {
        assertEquals("Hallo, Welt!", TranscriptNormalizer.normalize("Hallo , Welt !"))
    }

    @Test
    fun `collapses repeated punctuation`() {
        assertEquals("Fertig.", TranscriptNormalizer.normalize("Fertig.."))
    }

    @Test
    fun `leaves text alone when filler stripping is off`() {
        val options = NormalizeOptions(stripFillers = false)
        assertEquals("ähm ja", TranscriptNormalizer.normalize("ähm   ja", options))
    }

    @Test
    fun `handles an empty or all-filler transcript`() {
        assertEquals("", TranscriptNormalizer.normalize("   "))
        assertEquals("", TranscriptNormalizer.normalize("ähm äh hm"))
    }

    @Test
    fun `German gets its missing commas, other languages and a switched-off rule do not`() {
        val raw = "ich glaube dass es klappt"
        assertEquals("ich glaube, dass es klappt", TranscriptNormalizer.normalize(raw, language = "de"))
        assertEquals(raw, TranscriptNormalizer.normalize(raw, language = "en"))
        assertEquals(raw, TranscriptNormalizer.normalize(raw, NormalizeOptions(germanCommas = false), language = "de"))
    }

    @Test
    fun `invisible and direction-changing characters are removed`() {
        // A right-to-left override would show "txt.exe" as "exe.txt"; zero-width spaces hide.
        assertEquals("Datei txt.exe senden", TranscriptNormalizer.normalize("Datei \u202Etxt.exe\u202C sen\u200Bden"))
        assertEquals("eins zwei", TranscriptNormalizer.normalize("eins\u2028zwei\u0007"))
        // A joiner inside an emoji sequence is kept.
        assertEquals("Familie \uD83D\uDC68\u200D\uD83D\uDC69", TranscriptNormalizer.normalize("Familie \uD83D\uDC68\u200D\uD83D\uDC69"))
    }

    @Test
    fun `a formatted text keeps its paragraphs and list lines`() {
        val email = "Hallo Anna ,\n\n\n\nich komme morgen   um zehn.  \n\nViele Grüße\nFabi"
        assertEquals(
            "Hallo Anna,\n\nich komme morgen um zehn.\n\nViele Grüße\nFabi",
            TranscriptNormalizer.normalize(email, language = "de", keepLineBreaks = true),
        )
        val list = "Einkaufen:\n- Milch\n- äh Brot"
        assertEquals("Einkaufen:\n- Milch\n- Brot", TranscriptNormalizer.normalize(list, language = "de", keepLineBreaks = true))
    }

    @Test
    fun `raw engine output and one-line fields stay on one line`() {
        assertEquals("eins zwei drei", TranscriptNormalizer.normalize("eins\nzwei\n\ndrei"))
        assertEquals("Hallo Anna, bis morgen", TranscriptNormalizer.oneLine("Hallo Anna,\n\nbis morgen"))
    }

    @Test
    fun `German commas are added line by line`() {
        assertEquals(
            "Ich glaube, dass es klappt.\n\nWeil es regnet, bleiben wir.",
            TranscriptNormalizer.normalize("Ich glaube dass es klappt.\n\nWeil es regnet, bleiben wir.", language = "de", keepLineBreaks = true),
        )
    }
}
