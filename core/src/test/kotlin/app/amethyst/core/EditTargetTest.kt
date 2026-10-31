package app.amethyst.core

import app.amethyst.core.bubble.TextFieldKind
import app.amethyst.core.commands.EditTarget
import app.amethyst.core.insert.InputTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditTargetTest {

    private val aliceChat = InputTarget("com.whatsapp", windowId = 41, fieldId = 7)

    @Test
    fun `the field the command was started in may be changed`() {
        assertNull(EditTarget.refusal(aliceChat, aliceChat.copy()))
        // Below Android 13 no field id is known on either side: the window decides.
        assertNull(EditTarget.refusal(aliceChat.copy(fieldId = null), aliceChat.copy(fieldId = null)))
    }

    @Test
    fun `another app, another chat or another field is not changed`() {
        val moved = "You switched to another app, chat or field meanwhile - nothing was changed."
        assertEquals(moved, EditTarget.refusal(aliceChat, aliceChat.copy(packageName = "org.telegram.messenger")))
        // Bob's chat in the same app: a new window - "einfügen" must not paste there.
        assertEquals(moved, EditTarget.refusal(aliceChat, aliceChat.copy(windowId = 52)))
        assertEquals(moved, EditTarget.refusal(aliceChat, aliceChat.copy(fieldId = 9)))
    }

    @Test
    fun `no field with the cursor any more`() {
        assertEquals("No text field has the cursor any more - nothing was changed.", EditTarget.refusal(aliceChat, null))
    }

    @Test
    fun `never a password field - neither the one started in nor one moved to`() {
        val login = InputTarget("com.bank", windowId = 3, fieldId = 1, kind = TextFieldKind.SECRET)
        assertEquals("Not in a password field.", EditTarget.refusal(login, login.copy()))
        assertEquals("Not in a password field.", EditTarget.refusal(aliceChat, login))
        assertEquals("Not in a password field.", EditTarget.refusal(null, login))
    }

    @Test
    fun `a command started where no field had the cursor has no field to change`() {
        // Unlike a dictation, which takes the field focused at the end: an edit started outside
        // a field and a field tapped into while it was recognized are not the same thing.
        assertEquals(
            "Text commands work in the field you start them in - tap into it, then double-tap the bubble.",
            EditTarget.refusal(null, aliceChat),
        )
        assertEquals("No text field has the cursor - tap into one first.", EditTarget.refusal(null, null))
    }
}
