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
}
