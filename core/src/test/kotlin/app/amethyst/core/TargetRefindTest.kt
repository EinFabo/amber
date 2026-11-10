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
    private val startField = text.copy(isStart = true)

    private fun pick(
        fields: List<SeenField>,
        start: InputTarget? = chat,
        front: WindowInFront? = chatWindow,
        hasStartNode: Boolean = true,
    ) = TargetRefind.pick(start, front, fields, hasStartNode)

    @Test
    fun `the start field, still there, gets the words`() {
        assertEquals(Result.Found(1, How.START_FIELD), pick(listOf(text, startField, text)))
        // A field that names no input type.
        assertEquals(Result.Found(0, How.START_FIELD), pick(listOf(SeenField(TextFieldKind.NONE, isStart = true))))
    }

    @Test
    fun `another sole field in the same window is never chosen`() {
        // Telegram: every chat in one window, the same message box id. Chat A's box is gone,
        // chat B's is the only one there - and it has focus. The words must not go to B.
        val telegram = InputTarget("org.telegram.messenger", windowId = 12, fieldId = 3)
        val window = WindowInFront("org.telegram.messenger", 12)
        assertEquals(Result.NotFound(Why.NO_FIELD), pick(listOf(text), start = telegram, front = window))
        assertEquals(Result.NotFound(Why.NO_FIELD), pick(listOf(text, text), start = telegram, front = window))
    }

    @Test
    fun `a start field still there but not on screen gets nothing, and no other field either`() {
        // Another tab, scrolled away, disabled: the field in sight is not the one meant.
        assertEquals(Result.NotFound(Why.NOT_SHOWN), pick(listOf(startField.copy(shown = false), text)))
    }

    @Test
    fun `never a password field`() {
        assertEquals(Result.NotFound(Why.SECRET), pick(listOf(SeenField(TextFieldKind.SECRET, isStart = true))))
        assertEquals(Result.NotFound(Why.SECRET), pick(listOf(startField), start = chat.copy(kind = TextFieldKind.SECRET)))
    }

    @Test
    fun `no number field for words`() {
        assertEquals(Result.NotFound(Why.NO_FIELD), pick(listOf(SeenField(TextFieldKind.NUMERIC, isStart = true))))
    }

    @Test
    fun `only in the app and window the dictation started in`() {
        assertEquals(Result.NotFound(Why.NOT_IN_FRONT), pick(listOf(startField), front = WindowInFront("org.telegram.messenger", 41)))
        // Another chat in the same app is another window.
        assertEquals(Result.NotFound(Why.NOT_IN_FRONT), pick(listOf(startField), front = WindowInFront("com.whatsapp", 52)))
        assertEquals(Result.NotFound(Why.NOT_IN_FRONT), pick(listOf(startField), front = null))
        // Known only through the input connection at the start: the window cannot be confirmed.
        assertEquals(
            Result.NotFound(Why.NOT_IN_FRONT),
            pick(listOf(startField), start = chat.copy(windowId = -1), front = WindowInFront("com.whatsapp", -1)),
        )
    }

    @Test
    fun `without a start field or its node nothing is guessed`() {
        assertEquals(Result.NotFound(Why.NO_START), pick(listOf(text), start = null))
        assertEquals(Result.NotFound(Why.NO_START), pick(listOf(text), hasStartNode = false))
    }

    @Test
    fun `the window is worth searching only when it is the start window`() {
        assertTrue(TargetRefind.sameWindow(chat, chatWindow))
        assertFalse(TargetRefind.sameWindow(chat, chatWindow.copy(windowId = 52)))
        assertFalse(TargetRefind.sameWindow(null, chatWindow))
    }

    @Test
    fun `the start field gone`() {
        assertEquals(Result.NotFound(Why.NO_FIELD), pick(emptyList()))
    }
}
