package app.amethyst.core

import app.amethyst.core.bubble.TextFieldKind
import app.amethyst.core.insert.InputTarget
import app.amethyst.core.insert.TargetCheck
import app.amethyst.core.insert.TargetCheck.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals

class TargetCheckTest {

    private val aliceChat = InputTarget("com.whatsapp", windowId = 41, fieldId = 7)

    @Test
    fun `the same field gets the text`() {
        assertEquals(Verdict.INSERT, TargetCheck.verdict(aliceChat, aliceChat.copy()))
    }

    @Test
    fun `another app, another chat or another field does not`() {
        assertEquals(Verdict.MOVED, TargetCheck.verdict(aliceChat, aliceChat.copy(packageName = "org.telegram.messenger")))
        // Bob's chat in the same app: a new window.
        assertEquals(Verdict.MOVED, TargetCheck.verdict(aliceChat, aliceChat.copy(windowId = 52)))
        assertEquals(Verdict.MOVED, TargetCheck.verdict(aliceChat, aliceChat.copy(fieldId = 9)))
    }

    @Test
    fun `an unknown field id on one side is no reason to refuse`() {
        assertEquals(Verdict.INSERT, TargetCheck.verdict(aliceChat.copy(fieldId = null), aliceChat))
    }

    @Test
    fun `no field to check against means no insertion`() {
        assertEquals(Verdict.NO_FIELD, TargetCheck.verdict(aliceChat, null))
        assertEquals(Verdict.NO_FIELD, TargetCheck.verdict(null, null))
    }

    @Test
    fun `a password field never gets dictated text`() {
        val login = InputTarget("com.bank", windowId = 3, fieldId = 1)
        assertEquals(Verdict.SECRET, TargetCheck.verdict(login, login.copy(fieldId = 2, kind = TextFieldKind.SECRET)))
        assertEquals(Verdict.SECRET, TargetCheck.verdict(null, login.copy(kind = TextFieldKind.SECRET)))
    }

    @Test
    fun `started without a field, the field focused at the end is the user's choice`() {
        assertEquals(Verdict.INSERT, TargetCheck.verdict(null, aliceChat))
    }
}
