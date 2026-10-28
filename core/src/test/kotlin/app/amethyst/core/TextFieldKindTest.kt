package app.amethyst.core

import app.amethyst.core.bubble.TextFieldKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextFieldKindTest {

    // EditorInfo values, spelled out as the platform defines them.
    private val text = 0x1
    private val multiLine = 0x1 or 0x20000 or 0x4000 // text, multi-line, cap sentences
    private val email = 0x1 or 0x20
    private val uri = 0x1 or 0x10
    private val password = 0x1 or 0x80
    private val visiblePassword = 0x1 or 0x90
    private val webPassword = 0x1 or 0xe0
    private val number = 0x2
    private val pin = 0x2 or 0x10
    private val phone = 0x3
    private val date = 0x4 or 0x10

    @Test
    fun `messages, mail, addresses and search take dictation`() {
        for (type in listOf(text, multiLine, email, uri)) {
            assertTrue(TextFieldKind.of(type).wantsDictation, "inputType 0x${type.toString(16)}")
        }
    }

    @Test
    fun `passwords and pins are secret, whatever the variation`() {
        for (type in listOf(password, visiblePassword, webPassword, pin)) {
            assertEquals(TextFieldKind.SECRET, TextFieldKind.of(type), "inputType 0x${type.toString(16)}")
        }
        // A web form that only marks the node as a password.
        assertEquals(TextFieldKind.SECRET, TextFieldKind.of(text, isPassword = true))
    }

    @Test
    fun `numbers, phone numbers and dates get no bubble`() {
        for (type in listOf(number, phone, date)) {
            assertEquals(TextFieldKind.NUMERIC, TextFieldKind.of(type))
            assertFalse(TextFieldKind.of(type).wantsDictation)
        }
    }

    @Test
    fun `a view that takes no text is none, an unknown class is still offered`() {
        assertEquals(TextFieldKind.NONE, TextFieldKind.of(0))
        assertTrue(TextFieldKind.of(0x7).wantsDictation)
    }
}
