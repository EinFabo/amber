package app.amethyst.core

import app.amethyst.core.bubble.FieldPresence
import app.amethyst.core.bubble.KeyboardPolicy
import app.amethyst.core.bubble.KeyboardPolicy.Reading
import app.amethyst.core.bubble.TextFieldKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FieldPresenceTest {

    private val presence = FieldPresence()

    @Test
    fun `a field is active at once, before its keyboard is seen`() {
        assertFalse(presence.active)
        assertTrue(presence.field(TextFieldKind.TEXT, fresh = true))
        assertTrue(presence.active)
        assertFalse(presence.keyboard(null), "nothing said changes nothing")
        assertFalse(presence.keyboard(true))
        assertTrue(presence.active)
    }

    @Test
    fun `the keyboard closing takes the field with it - no end of input ever comes`() {
        // YouTube after pressing Search: the field is gone or only left in focus, the keyboard closes.
        presence.field(TextFieldKind.TEXT, fresh = true)
        presence.keyboard(true)
        assertTrue(presence.keyboard(false))
        assertFalse(presence.active)
        assertEquals("field without its keyboard", presence.why)
    }

    @Test
    fun `the keyboard coming back brings the field back - a tap on the same field starts no new input`() {
        presence.field(TextFieldKind.TEXT, fresh = true)
        presence.keyboard(true)
        presence.keyboard(false)
        assertFalse(presence.keyboard(null), "a keyboard still moving says nothing yet")
        assertFalse(presence.active)
        assertTrue(presence.keyboard(true))
        assertTrue(presence.active)
    }

    @Test
    fun `an input restarted in the same field does not bring a closed keyboard's field back`() {
        // An app that sets the field's text after hiding the keyboard restarts the input.
        presence.field(TextFieldKind.TEXT, fresh = true)
        presence.keyboard(true)
        presence.keyboard(false)
        assertFalse(presence.field(TextFieldKind.TEXT, fresh = false))
        assertFalse(presence.active)
    }

    @Test
    fun `another field is active at once, whatever the keyboard said for the one before`() {
        presence.field(TextFieldKind.TEXT, fresh = true)
        presence.keyboard(false)
        assertTrue(presence.field(TextFieldKind.TEXT, fresh = true))
        assertTrue(presence.active)
    }

    @Test
    fun `the end of input ends it, and the next field starts clean`() {
        presence.field(TextFieldKind.TEXT, fresh = true)
        presence.keyboard(false)
        presence.field(TextFieldKind.NONE, fresh = false)
        assertFalse(presence.active)
        assertEquals("no field", presence.why)
        // Not fresh by the caller's word, but after none: the keyboard's "closed" was the old field's.
        assertTrue(presence.field(TextFieldKind.TEXT, fresh = false))
    }

    @Test
    fun `a field that takes no dictation is never active, keyboard or not`() {
        presence.field(TextFieldKind.SECRET, fresh = true)
        presence.keyboard(true)
        assertFalse(presence.active)
        presence.field(TextFieldKind.NUMERIC, fresh = true)
        assertFalse(presence.active)
    }

    // With the keyboard's own reading of the window list.

    private val screen = 2400
    private val policy = KeyboardPolicy(defaultTopFraction = 0.6f)
    private val up = Reading.Window(1500, 2400)

    @Test
    fun `a keyboard is shown once it rests, and not shown once it has closed for good`() {
        assertNull(policy.onFocusChanged(true, now = 0, screenHeight = screen).shown, "on its way")
        assertNull(policy.onReading(Reading.Window(2362, 3245), now = 60, screenHeight = screen).shown, "sliding in")
        assertEquals(true, policy.onReading(up, now = 300, screenHeight = screen).shown)
        assertNull(policy.onReading(Reading.Absent, now = 1_000, screenHeight = screen).shown, "a gap in the reports is no close")
        assertEquals(false, policy.onReading(Reading.Absent, now = 1_000 + KeyboardPolicy.CLOSE_CONFIRM_MS, screenHeight = screen).shown)
    }

    @Test
    fun `a keyboard starting to slide away is not shown any more`() {
        policy.onFocusChanged(true, now = 0, screenHeight = screen)
        policy.onReading(up, now = 300, screenHeight = screen)
        assertEquals(false, policy.onReading(Reading.Window(1900, 2800), now = 5_000, screenHeight = screen).shown)
        assertEquals(false, policy.onReading(Reading.Absent, now = 5_100, screenHeight = screen).shown)
    }

    @Test
    fun `a floating keyboard and one of unknown height are shown`() {
        policy.onFocusChanged(true, now = 0, screenHeight = screen)
        assertEquals(true, policy.onReading(Reading.Floating(app.amethyst.core.bubble.Box(100, 900, 900, 1500)), now = 300, screenHeight = screen).shown)
        assertEquals(true, policy.onReading(Reading.Unmeasurable, now = 400, screenHeight = screen).shown)
    }

    @Test
    fun `a field whose keyboard never comes has none - on a phone that reports its keyboard`() {
        // Seen once before, so missing means something here.
        policy.onFocusChanged(true, now = 0, screenHeight = screen)
        policy.onReading(up, now = 300, screenHeight = screen)
        policy.onFocusChanged(false, now = 1_000, screenHeight = screen)
        policy.onReading(Reading.Absent, now = 1_500, screenHeight = screen)

        policy.onFocusChanged(true, now = 10_000, screenHeight = screen)
        assertNull(policy.onReading(Reading.Absent, now = 10_100, screenHeight = screen).shown, "still on its way")
        assertEquals(false, policy.onReading(Reading.Absent, now = 10_000 + KeyboardPolicy.ARRIVAL_WINDOW_MS, screenHeight = screen).shown)
    }

    @Test
    fun `a phone that never reports a keyboard window says nothing, so the field alone decides`() {
        val silent = KeyboardPolicy(defaultTopFraction = 0.6f)
        silent.onFocusChanged(true, now = 0, screenHeight = screen)
        assertNull(silent.onReading(Reading.Absent, now = KeyboardPolicy.ARRIVAL_WINDOW_MS + 1, screenHeight = screen).shown)
        presence.field(TextFieldKind.TEXT, fresh = true)
        presence.keyboard(null)
        assertTrue(presence.active)
    }

    @Test
    fun `another field while one was in focus waits for its keyboard anew`() {
        policy.onFocusChanged(true, now = 0, screenHeight = screen)
        policy.onReading(up, now = 300, screenHeight = screen)
        assertEquals(false, policy.onReading(Reading.Absent, now = 2_000, screenHeight = screen).let {
            policy.onReading(Reading.Absent, now = 2_000 + KeyboardPolicy.CLOSE_CONFIRM_MS, screenHeight = screen).shown
        })
        // Stale focus, as after YouTube's search: the next field starts input without a focus change.
        val next = policy.onNewField(now = 9_000, screenHeight = screen)
        assertNull(next.shown)
        assertEquals(1500, next.top, "placed above the keyboard that is on its way")
        assertNull(policy.onReading(Reading.Absent, now = 9_100, screenHeight = screen).shown, "not closed again at once")
        assertEquals(true, policy.onReading(up, now = 9_300, screenHeight = screen).shown)
    }

    @Test
    fun `another field with the keyboard staying up moves nothing`() {
        policy.onFocusChanged(true, now = 0, screenHeight = screen)
        policy.onReading(up, now = 300, screenHeight = screen)
        assertEquals(1500, policy.onNewField(now = 5_000, screenHeight = screen).top)
        assertEquals(true, policy.onReading(up, now = 5_060, screenHeight = screen).shown)
    }

    @Test
    fun `a new field without one in focus is a focus like any other`() {
        assertEquals(1440, policy.onNewField(now = 0, screenHeight = screen).top)
    }
}
