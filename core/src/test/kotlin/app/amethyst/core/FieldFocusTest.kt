package app.amethyst.core

import app.amethyst.core.bubble.FieldFocus
import app.amethyst.core.bubble.FieldFocus.Signal
import app.amethyst.core.bubble.TextFieldKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class FieldFocusTest {

    private val below13 = FieldFocus(inputMethodTells = false)
    private val from13 = FieldFocus(inputMethodTells = true)

    private val nothingFocused = { TextFieldKind.NONE }
    private val notAsked: () -> TextFieldKind = { fail("no lookup expected") }

    @Test
    fun `Amber's own field below Android 13 is a new text field when its screen says so`() {
        // The A20e: a Compose field sends nothing the service could read as a field.
        assertEquals(Signal(TextFieldKind.TEXT, fresh = true), below13.own(true, notAsked))
        assertTrue(below13.ownFocused)
    }

    @Test
    fun `the focus event that follows the screen's word is the same field, not another one`() {
        below13.own(true, notAsked)
        assertEquals(Signal(TextFieldKind.TEXT, fresh = false), below13.focusEvent(TextFieldKind.TEXT, inOwnApp = true))
    }

    @Test
    fun `a focus event that came first makes the screen's word no new field`() {
        assertEquals(Signal(TextFieldKind.TEXT, fresh = true), below13.focusEvent(TextFieldKind.TEXT, inOwnApp = true))
        assertEquals(Signal(TextFieldKind.TEXT, fresh = false), below13.own(true, notAsked))
    }

    @Test
    fun `window changes do not end Amber's field while its screen says it has focus`() {
        // Android names no focused node for a Compose field; Amber's own toasts change windows too.
        below13.own(true, notAsked)
        assertEquals(Signal(TextFieldKind.TEXT, fresh = false), below13.windowChanged(fromKeyboard = false, lookup = nothingFocused))
        assertEquals(Signal(TextFieldKind.TEXT, fresh = false), below13.focusEvent(TextFieldKind.NONE, inOwnApp = true))
    }

    @Test
    fun `losing focus or leaving the screen ends it, with what Android names as focused now`() {
        below13.own(true, notAsked)
        assertEquals(Signal(TextFieldKind.NONE, fresh = false), below13.own(false, nothingFocused))
        assertFalse(below13.ownFocused)
        below13.own(true, notAsked)
        assertEquals(Signal(TextFieldKind.TEXT, fresh = false), below13.own(false) { TextFieldKind.TEXT })
    }

    @Test
    fun `the screen saying the same twice passes nothing on`() {
        assertNull(below13.own(false, notAsked))
        below13.own(true, notAsked)
        assertNull(below13.own(true, notAsked))
    }

    @Test
    fun `a field in another app takes the input from Amber's`() {
        below13.own(true, notAsked)
        assertEquals(Signal(TextFieldKind.TEXT, fresh = true), below13.focusEvent(TextFieldKind.TEXT, inOwnApp = false))
        assertFalse(below13.ownFocused)
        assertEquals(Signal(TextFieldKind.NONE, fresh = false), below13.windowChanged(fromKeyboard = false, lookup = nothingFocused))
    }

    @Test
    fun `a window of the keyboard asks nothing and passes nothing on`() {
        // A Compose field in another app: its focus event is the only sign, and the keyboard opening must not end it.
        below13.focusEvent(TextFieldKind.TEXT, inOwnApp = false)
        assertNull(below13.windowChanged(fromKeyboard = true, lookup = notAsked))
    }

    @Test
    fun `other apps below Android 13 work as before`() {
        assertEquals(Signal(TextFieldKind.TEXT, fresh = true), below13.focusEvent(TextFieldKind.TEXT, inOwnApp = false))
        assertEquals(Signal(TextFieldKind.SECRET, fresh = true), below13.focusEvent(TextFieldKind.SECRET, inOwnApp = false))
        assertEquals(Signal(TextFieldKind.NONE, fresh = false), below13.focusEvent(TextFieldKind.NONE, inOwnApp = false))
        assertEquals(Signal(TextFieldKind.TEXT, fresh = false), below13.windowChanged(fromKeyboard = false) { TextFieldKind.TEXT })
    }

    @Test
    fun `from Android 13 the screen's word is ignored - the input method announced the field already`() {
        assertEquals(Signal(TextFieldKind.TEXT, fresh = true), from13.inputStarted(TextFieldKind.TEXT, restarting = false))
        assertNull(from13.own(true, notAsked))
        assertFalse(from13.ownFocused)
        assertNull(from13.own(false, notAsked))
        assertNull(from13.focusEvent(TextFieldKind.TEXT, inOwnApp = true))
        assertNull(from13.windowChanged(fromKeyboard = false, lookup = notAsked))
        assertFalse(from13.watchesFocus)
        assertTrue(below13.watchesFocus)
    }

    @Test
    fun `from Android 13 the input method decides, as before`() {
        assertEquals(Signal(TextFieldKind.TEXT, fresh = true), from13.inputStarted(TextFieldKind.TEXT, restarting = false))
        assertEquals(Signal(TextFieldKind.TEXT, fresh = false), from13.inputStarted(TextFieldKind.TEXT, restarting = true))
        assertEquals(Signal(TextFieldKind.SECRET, fresh = true), from13.inputStarted(TextFieldKind.SECRET, restarting = false))
        assertEquals(Signal(TextFieldKind.NONE, fresh = false), from13.inputFinished())
        // A view that takes no text: nothing.
        assertEquals(Signal(TextFieldKind.NONE, fresh = true), from13.inputStarted(TextFieldKind.NONE, restarting = false))
    }
}
