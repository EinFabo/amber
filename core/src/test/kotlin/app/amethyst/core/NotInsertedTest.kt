package app.amethyst.core

import app.amethyst.core.dictation.NotInserted
import app.amethyst.core.dictation.NotInserted.Hint
import app.amethyst.core.dictation.NotInserted.Why
import app.amethyst.core.insert.TargetCheck
import kotlin.test.Test
import kotlin.test.assertEquals

class NotInsertedTest {

    @Test
    fun `a field that refused the text points to Copy, not to Insert again which fails there again`() {
        assertEquals(Hint.COPY, NotInserted.hint(Why.REFUSED, onClipboard = false))
    }

    @Test
    fun `words typed that never showed may still arrive - said before the way out`() {
        assertEquals(Hint.MAY_ARRIVE, NotInserted.hint(Why.UNCERTAIN, onClipboard = false))
        assertEquals(Hint.MAY_ARRIVE_ON_CLIPBOARD, NotInserted.hint(Why.UNCERTAIN, onClipboard = true))
    }

    @Test
    fun `a field that was gone or left points to Insert again`() {
        assertEquals(Hint.INSERT_AGAIN, NotInserted.hint(Why.NO_FIELD, onClipboard = false))
        assertEquals(Hint.INSERT_AGAIN, NotInserted.hint(Why.SWITCHED, onClipboard = false))
        assertEquals(Hint.INSERT_AGAIN, NotInserted.hint(Why.SCREEN_OFF, onClipboard = false))
    }

    @Test
    fun `text on the clipboard is pasted by hand, a password field gets nothing`() {
        assertEquals(Hint.ON_CLIPBOARD, NotInserted.hint(Why.SWITCHED, onClipboard = true))
        assertEquals(Hint.ON_CLIPBOARD, NotInserted.hint(Why.REFUSED, onClipboard = true))
        assertEquals(Hint.NONE, NotInserted.hint(Why.PASSWORD, onClipboard = false))
        assertEquals(Hint.NONE, NotInserted.hint(Why.PASSWORD, onClipboard = true))
    }

    @Test
    fun `the check before writing says the field was left, gone or a password field`() {
        assertEquals(Why.SWITCHED, NotInserted.beforeWrite(TargetCheck.Verdict.MOVED))
        assertEquals(Why.NO_FIELD, NotInserted.beforeWrite(TargetCheck.Verdict.NO_FIELD))
        assertEquals(Why.PASSWORD, NotInserted.beforeWrite(TargetCheck.Verdict.SECRET))
    }

    @Test
    fun `a write refused at the field check is a moved focus, one the field ignored is a refusal`() {
        assertEquals(Why.SWITCHED, NotInserted.afterWrite(TargetCheck.Verdict.MOVED, uncertain = false))
        assertEquals(Why.PASSWORD, NotInserted.afterWrite(TargetCheck.Verdict.SECRET, uncertain = false))
        assertEquals(Why.UNCERTAIN, NotInserted.afterWrite(null, uncertain = true))
        assertEquals(Why.REFUSED, NotInserted.afterWrite(null, uncertain = false))
    }
}
