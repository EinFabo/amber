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
    fun `another app does not`() {
        assertEquals(Verdict.MOVED, TargetCheck.verdict(aliceChat, aliceChat.copy(packageName = "org.telegram.messenger")))
    }

    @Test
    fun `in the same app the field with the cursor now gets it, whatever its window`() {
        // Pixel 9a, 2026-11-10: out of a WhatsApp chat and back in made a new Conversation
        // activity - window 12004 became 12010, the field id stayed app:id/entry - and the
        // dictation was refused in the very chat it was meant for. Another chat looks the same
        // to Android, so window and field cannot tell them apart; the user can.
        assertEquals(Verdict.INSERT, TargetCheck.verdict(aliceChat, aliceChat.copy(windowId = 52)))
        assertEquals(Verdict.INSERT, TargetCheck.verdict(aliceChat, aliceChat.copy(fieldId = 9)))
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
    fun `started without a field, the field focused at the end is the user's choice - in the app that was in front`() {
        assertEquals(Verdict.INSERT, TargetCheck.verdict(null, aliceChat, appAtStart = "com.whatsapp"))
    }

    @Test
    fun `started without a field, a field of another app gets nothing`() {
        // Bubble shown everywhere: a field that took focus by itself meanwhile - an overlay's -
        // is not the user's choice.
        assertEquals(Verdict.MOVED, TargetCheck.verdict(null, aliceChat, appAtStart = "com.android.launcher"))
    }

    @Test
    fun `started without a field and without knowing the app in front, there is nothing to hold a field against`() {
        assertEquals(Verdict.INSERT, TargetCheck.verdict(null, aliceChat))
        assertEquals(Verdict.SECRET, TargetCheck.verdict(null, aliceChat.copy(kind = TextFieldKind.SECRET), appAtStart = "com.whatsapp"))
    }

    @Test
    fun `the start field's app counts, not the app in front then`() {
        // A field of the keyboard or of a dialog over the app: the field says whose it is.
        assertEquals(Verdict.INSERT, TargetCheck.verdict(aliceChat, aliceChat.copy(windowId = 52), appAtStart = "com.other"))
        assertEquals("com.whatsapp", TargetCheck.expectedPackage(aliceChat, appAtStart = "com.other"))
        assertEquals("com.other", TargetCheck.expectedPackage(null, appAtStart = "com.other"))
        assertEquals(null, TargetCheck.expectedPackage(null, appAtStart = null))
    }

    // --- the last look, at the field found for writing -----------------------------------

    @Test
    fun `at the write, another field of the same app is written`() {
        // The relaxation of 2026-11-10 holds here too: only the app must be the same.
        assertEquals(Verdict.INSERT, TargetCheck.atWrite("com.whatsapp", "com.whatsapp", secret = false))
    }

    @Test
    fun `at the write, another app's field is refused`() {
        assertEquals(Verdict.MOVED, TargetCheck.atWrite("com.whatsapp", "com.overlay", secret = false))
        assertEquals(Verdict.MOVED, TargetCheck.atWrite("com.whatsapp", "unknown", secret = false))
    }

    @Test
    fun `at the write, a password field is refused in any app`() {
        assertEquals(Verdict.SECRET, TargetCheck.atWrite("com.bank", "com.bank", secret = true))
        assertEquals(Verdict.SECRET, TargetCheck.atWrite(null, "com.bank", secret = true))
        assertEquals(Verdict.SECRET, TargetCheck.atWrite("com.whatsapp", "com.bank", secret = true))
    }

    @Test
    fun `at the write, a dictation started without a field goes into the app that was in front`() {
        val expected = TargetCheck.expectedPackage(null, appAtStart = "com.whatsapp")

        assertEquals(Verdict.INSERT, TargetCheck.atWrite(expected, "com.whatsapp", secret = false))
        assertEquals(Verdict.MOVED, TargetCheck.atWrite(expected, "com.overlay", secret = false))
    }

    @Test
    fun `at the write, with no app known any field that is no password field is written`() {
        assertEquals(Verdict.INSERT, TargetCheck.atWrite(null, "com.whatsapp", secret = false))
    }
}
