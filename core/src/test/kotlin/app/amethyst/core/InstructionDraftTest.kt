package app.amethyst.core

import app.amethyst.core.settings.InstructionDraft
import app.amethyst.core.text.ChatCleanup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An app's own instruction in two editors at once: its page in Amber and the "Style here" window. */
class InstructionDraftTest {

    @Test
    fun `an editor that was only looked at saves nothing`() {
        val page = InstructionDraft.of("use du")
        assertFalse(page.needsSave("use du"))
        // The window changed it meanwhile: leaving the page must not write the old text back.
        assertFalse(page.needsSave("sign with Fabi"))
    }

    @Test
    fun `an editor follows the stored instruction while nothing is typed in it`() {
        val page = InstructionDraft.of("use du").stored("sign with Fabi")
        assertEquals("sign with Fabi", page.text)
        assertEquals("", InstructionDraft.of("use du").stored(null).text)
    }

    @Test
    fun `what is typed is saved, and not overwritten by the other editor`() {
        val window = InstructionDraft.of("use du").typed("use du, sign with Fabi")
        assertTrue(window.needsSave("use du"))
        assertEquals(window, window.stored("something else"), "typed text stays until it is saved")
    }

    @Test
    fun `after a save the editor follows again, without stripping what was just typed`() {
        val typing = InstructionDraft.of(null).typed("hello ")
        assertEquals("hello", typing.instruction)
        val saved = typing.saved()
        assertFalse(saved.needsSave("hello"))
        assertEquals("hello ", saved.stored("hello").text, "the trailing space stays while typing goes on")
        assertEquals("hello there", saved.typed("hello there").stored("other").text)
    }

    @Test
    fun `typing back to what is stored saves nothing`() {
        assertFalse(InstructionDraft.of("use du").typed("use du ").needsSave("use du"))
    }

    @Test
    fun `clearing the field clears the instruction`() {
        val cleared = InstructionDraft.of("use du").typed("   ")
        assertNull(cleared.instruction)
        assertTrue(cleared.needsSave("use du"))
    }

    @Test
    fun `typed text is kept to the length the cleanup takes`() {
        assertEquals(ChatCleanup.INSTRUCTION_CHARS, InstructionDraft.of(null).typed("x".repeat(ChatCleanup.INSTRUCTION_CHARS + 50)).text.length)
    }
}
