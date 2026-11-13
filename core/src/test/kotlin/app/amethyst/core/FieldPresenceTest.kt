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
    fun `a keyboard is shown from its first sight, and not shown once it has closed for good`() {
        assertNull(policy.onFocusChanged(true, now = 0, screenHeight = screen).shown, "on its way, on a phone not known to report it")
        assertEquals(true, policy.onReading(Reading.Window(2362, 3245), now = 60, screenHeight = screen).shown, "sliding in")
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
        assertEquals(false, next.shown, "no keyboard up: the bubble waits for it")
        assertEquals(1500, next.top, "the keyboard that is on its way is kept clear of")
        assertNull(policy.onReading(Reading.Absent, now = 9_100, screenHeight = screen).shown, "not closed again at once")
        assertEquals(true, policy.onReading(up, now = 9_300, screenHeight = screen).shown)
    }

    @Test
    fun `another field with the keyboard staying up moves nothing`() {
        policy.onFocusChanged(true, now = 0, screenHeight = screen)
        policy.onReading(up, now = 300, screenHeight = screen)
        val next = policy.onNewField(now = 5_000, screenHeight = screen)
        assertEquals(1500, next.top)
        assertNull(next.shown, "the keyboard is up: nothing new, the bubble stays")
        assertEquals(true, policy.onReading(up, now = 5_060, screenHeight = screen).shown)
    }

    /** A phone whose keyboard was seen and has closed again, no field in focus. */
    private fun reporting() = KeyboardPolicy(defaultTopFraction = 0.6f).also {
        it.onFocusChanged(true, now = 0, screenHeight = screen)
        it.onReading(up, now = 300, screenHeight = screen)
        it.onFocusChanged(false, now = 1_000, screenHeight = screen)
        it.onReading(Reading.Absent, now = 1_500, screenHeight = screen)
    }

    @Test
    fun `a chat that opens with its input in focus and no keyboard shows no bubble`() {
        // WhatsApp on a Pixel 9a, 2026-11-13: input started, no keyboard - the bubble was
        // there for the 1.5 s the keyboard was waited for.
        val policy = reporting()
        presence.field(TextFieldKind.TEXT, fresh = true)
        presence.keyboard(policy.onFocusChanged(true, now = 10_000, screenHeight = screen).shown)
        assertFalse(presence.active)
        presence.keyboard(policy.onReading(Reading.Absent, now = 10_050, screenHeight = screen).shown)
        assertFalse(presence.active, "still on its way says nothing")
        presence.keyboard(policy.onReading(Reading.Absent, now = 10_000 + KeyboardPolicy.ARRIVAL_WINDOW_MS, screenHeight = screen).shown)
        assertFalse(presence.active)
    }

    @Test
    fun `the bubble comes with the first sight of the keyboard, not when it has come to rest`() {
        val policy = reporting()
        presence.field(TextFieldKind.TEXT, fresh = true)
        presence.keyboard(policy.onFocusChanged(true, now = 10_000, screenHeight = screen).shown)
        // The field is tapped later: the keyboard slides in.
        presence.keyboard(policy.onReading(Reading.Window(2362, 3245), now = 40_000, screenHeight = screen).shown)
        assertTrue(presence.active)
        presence.keyboard(policy.onReading(up, now = 40_200, screenHeight = screen).shown)
        assertTrue(presence.active)
    }

    @Test
    fun `a keyboard sliding by without a field in focus is nobody's keyboard`() {
        val policy = reporting()
        assertNull(policy.onReading(Reading.Window(2362, 3245), now = 5_000, screenHeight = screen).shown)
    }

    @Test
    fun `a field that starts right after another ended, the keyboard still up, is active at once`() {
        val policy = KeyboardPolicy(defaultTopFraction = 0.6f)
        policy.onFocusChanged(true, now = 0, screenHeight = screen)
        policy.onReading(up, now = 300, screenHeight = screen)
        // Input finished and started again, as a form does from field to field.
        policy.onFocusChanged(false, now = 1_000, screenHeight = screen)
        presence.field(TextFieldKind.TEXT, fresh = true)
        presence.keyboard(policy.onFocusChanged(true, now = 1_020, screenHeight = screen).shown)
        assertTrue(presence.active)
    }

    @Test
    fun `a new field without one in focus is a focus like any other`() {
        assertEquals(1440, policy.onNewField(now = 0, screenHeight = screen).top)
    }
}
