package app.amethyst.core

import app.amethyst.core.bubble.TextFieldKind
import app.amethyst.core.insert.InputTarget
import app.amethyst.core.insert.SeenField
import app.amethyst.core.insert.TargetRefind
import app.amethyst.core.insert.TargetRefind.How
import app.amethyst.core.insert.TargetRefind.Result
import app.amethyst.core.insert.TargetRefind.Why
import app.amethyst.core.insert.WindowInFront
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TargetRefindTest {

    private val chat = InputTarget("com.whatsapp", windowId = 41, fieldId = 7)
    private val chatWindow = WindowInFront("com.whatsapp", 41)
    private val text = SeenField(TextFieldKind.TEXT)

    private fun pick(fields: List<SeenField>, start: InputTarget? = chat, front: WindowInFront? = chatWindow) =
        TargetRefind.pick(start, front, fields)

    @Test
    fun `the start field, still there, gets the words`() {
        assertEquals(Result.Found(1, How.START_FIELD), pick(listOf(text, text.copy(isStart = true), text)))
    }

    @Test
    fun `else the one field that says it is focused`() {
        assertEquals(Result.Found(2, How.FOCUSED), pick(listOf(text, text, text.copy(focused = true))))
    }

    @Test
    fun `else the only field in the window - the field the app rebuilt`() {
        assertEquals(Result.Found(0, How.ONLY_FIELD), pick(listOf(text)))
        // A Compose field names no input type.
        assertEquals(Result.Found(0, How.ONLY_FIELD), pick(listOf(SeenField(TextFieldKind.NONE))))
    }

    @Test
    fun `two fields that could be meant get nothing`() {
        assertEquals(Result.NotFound(Why.SEVERAL), pick(listOf(text, text)))
        assertEquals(Result.NotFound(Why.SEVERAL), pick(listOf(text.copy(focused = true), text.copy(focused = true))))
    }

    @Test
    fun `a field off screen or disabled does not count`() {
        assertEquals(Result.Found(1, How.ONLY_FIELD), pick(listOf(text.copy(shown = false), text)))
        assertEquals(Result.NotFound(Why.NO_FIELD), pick(listOf(text.copy(shown = false, isStart = true))))
    }

    @Test
    fun `never a password field`() {
        val password = SeenField(TextFieldKind.SECRET)
        assertEquals(Result.NotFound(Why.NO_FIELD), pick(listOf(password)))
        assertEquals(Result.NotFound(Why.SECRET), pick(listOf(text, password.copy(focused = true))))
        assertEquals(Result.NotFound(Why.SECRET), pick(listOf(text, password.copy(isStart = true))))
        // The login form's user name field, beside the password: the only field for words.
        assertEquals(Result.Found(0, How.ONLY_FIELD), pick(listOf(text, password)))
        assertEquals(Result.NotFound(Why.SECRET), pick(listOf(text), start = chat.copy(kind = TextFieldKind.SECRET)))
    }

    @Test
    fun `no number field for words`() {
        assertEquals(Result.NotFound(Why.NO_FIELD), pick(listOf(SeenField(TextFieldKind.NUMERIC))))
        assertEquals(Result.NotFound(Why.NO_FIELD), pick(listOf(text, SeenField(TextFieldKind.NUMERIC, focused = true))))
    }

    @Test
    fun `only in the app and window the dictation started in`() {
        assertEquals(Result.NotFound(Why.NOT_IN_FRONT), pick(listOf(text), front = WindowInFront("org.telegram.messenger", 41)))
        // Another chat in the same app is another window.
        assertEquals(Result.NotFound(Why.NOT_IN_FRONT), pick(listOf(text), front = WindowInFront("com.whatsapp", 52)))
        assertEquals(Result.NotFound(Why.NOT_IN_FRONT), pick(listOf(text), front = null))
        // Known only through the input connection at the start: the window cannot be confirmed.
        assertEquals(
            Result.NotFound(Why.NOT_IN_FRONT),
            pick(listOf(text), start = chat.copy(windowId = -1), front = WindowInFront("com.whatsapp", -1)),
        )
    }

    @Test
    fun `started without a field, nothing is guessed`() {
        assertEquals(Result.NotFound(Why.NO_START), pick(listOf(text), start = null))
    }

    @Test
    fun `the window is worth searching only when it is the start window`() {
        assertTrue(TargetRefind.sameWindow(chat, chatWindow))
        assertFalse(TargetRefind.sameWindow(chat, chatWindow.copy(windowId = 52)))
        assertFalse(TargetRefind.sameWindow(null, chatWindow))
    }

    @Test
    fun `no field at all`() {
        assertEquals(Result.NotFound(Why.NO_FIELD), pick(emptyList()))
    }
}
