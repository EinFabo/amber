package app.amethyst.core

import app.amethyst.core.capture.RecordingCue
import app.amethyst.core.capture.RecordingCue.Cue
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.FeedbackSettings
import app.amethyst.core.settings.SettingsCodec
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecordingCueTest {

    private val cue = RecordingCue()

    @Test
    fun `the microphone opening is signalled once, however often it is said`() {
        assertEquals(Cue.OPEN, cue.opened())
        assertNull(cue.opened())
        assertNull(cue.opened())
    }

    @Test
    fun `the end is signalled once, and only after an open microphone`() {
        assertNull(cue.ended(), "a tap that never opened the microphone gets no stop tone")
        cue.opened()
        assertEquals(Cue.CLOSED, cue.ended())
        assertNull(cue.ended())
    }

    @Test
    fun `settings from before the tones decode with them off, and the switch is kept`() {
        val old = SettingsCodec.encode(AmethystSettings()).replace(Regex(""""feedback":\s*\{[^}]*\},?"""), "")
        assertEquals(FeedbackSettings(), SettingsCodec.decode(old, TEST_PHONE).feedback)
        assertFalse(FeedbackSettings().sounds)
        val on = AmethystSettings().copy(feedback = FeedbackSettings(sounds = true))
        assertEquals(true, SettingsCodec.decode(SettingsCodec.encode(on), TEST_PHONE).feedback.sounds)
    }

    @Test
    fun `the next recording is signalled again`() {
        cue.opened()
        cue.ended()
        assertEquals(Cue.OPEN, cue.opened())
        assertEquals(Cue.CLOSED, cue.ended())
    }
}
