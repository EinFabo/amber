package app.amethyst.core

import app.amethyst.core.settings.WritingStyle
import app.amethyst.core.text.ChatCleanup
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a cleanup model puts around the text it hands back, and is not typed into the field. */
class CleanupWrapperTest {

    private fun accepted(spoken: String, reply: String): String =
        ChatCleanup.accepted(spoken, reply, WritingStyle.AS_SPOKEN, instruction = null, generalInstruction = null, language = "de").getOrThrow()

    @Test
    fun `quotation marks around the whole reply go`() {
        assertEquals("Ich komme morgen um drei.", accepted("ich komme morgen um drei", "„Ich komme morgen um drei.“"))
        assertEquals("I will be there at eight.", accepted("i will be there at eight", "“I will be there at eight.”"))
        assertEquals("Ich komme morgen um drei.", accepted("ich komme morgen um drei", "«Ich komme morgen um drei.»"))
        assertEquals("Ich komme morgen um drei.", accepted("ich komme morgen um drei", "»Ich komme morgen um drei.«"))
    }

    @Test
    fun `a code fence around the whole reply goes`() {
        assertEquals("Ich komme morgen um drei.", accepted("ich komme morgen um drei", "```\nIch komme morgen um drei.\n```"))
        assertEquals("Ich komme morgen um drei.", accepted("ich komme morgen um drei", "```text\nIch komme morgen um drei.\n```"))
    }

    @Test
    fun `quotes the speaker dictated stay`() {
        // Two quotations, not one around everything.
        assertEquals(
            "„Ich komme“, sagte er, „morgen.“",
            accepted("„ich komme“ sagte er „morgen“", "„Ich komme“, sagte er, „morgen.“"),
        )
        // The dictation itself was a quotation.
        assertEquals("„Ich komme morgen.“", accepted("„ich komme morgen“", "„Ich komme morgen.“"))
    }
}
