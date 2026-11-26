package app.amethyst.core

import app.amethyst.core.text.OwnWords
import app.amethyst.core.text.PromptEcho
import kotlin.test.Test
import kotlin.test.assertEquals

class PromptEchoTest {

    private val hint = OwnWords.hint(listOf("Anna Lüdtke", "Kubernetes", "Amber"))

    @Test
    fun `the own words written back in front of the words go`() {
        assertEquals("Ja.", PromptEcho.withoutLeadingHint("Anna Lüdtke, Kubernetes, Amber. Ja.", hint))
        assertEquals("Ich komme gleich.", PromptEcho.withoutLeadingHint(" anna lüdtke, kubernetes, amber.  Ich komme gleich.", hint))
        // Only the start of the list, as Whisper may write back.
        assertEquals("Ja.", PromptEcho.withoutLeadingHint("Anna Lüdtke, Kubernetes. Ja.", hint))
    }

    @Test
    fun `own words the speaker said stay`() {
        for (text in listOf(
            "Anna Lüdtke, Kubernetes und Amber sind heute da.",
            "Anna Lüdtke kommt morgen.",
            "Kubernetes, Amber. Ja.",
            "Anna Lüdtke. Ja.",
            "Anna Lüdtke, Kubernetes, Amber.",
            "Ja.",
        )) {
            assertEquals(text, PromptEcho.withoutLeadingHint(text, hint), text)
        }
        assertEquals("Anna Lüdtke, Kubernetes. Ja.", PromptEcho.withoutLeadingHint("Anna Lüdtke, Kubernetes. Ja.", null))
    }
}
