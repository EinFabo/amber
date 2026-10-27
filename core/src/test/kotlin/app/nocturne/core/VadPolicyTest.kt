package app.nocturne.core

import app.nocturne.core.audio.DiscardReason
import app.nocturne.core.audio.StopReason
import app.nocturne.core.audio.VadConfig
import app.nocturne.core.audio.VadDecision
import app.nocturne.core.audio.VadPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VadPolicyTest {

    private val config = VadConfig(
        frameMs = 20,
        minSpeechMs = 100,
        silenceTimeoutMs = 200,
        maxDurationMs = 2_000,
        noSpeechTimeoutMs = 1_000,
        calibrationFrames = 5,
    )

    /** Feeds [frames] frames of loudness [rms], returning the first non-Continue decision. */
    private fun VadPolicy.feed(frames: Int, rms: Double): VadDecision? {
        repeat(frames) {
            val decision = onFrame(rms)
            if (decision !is VadDecision.Continue) return decision
        }
        return null
    }

    @Test
    fun `stops after speech followed by silence`() {
        val vad = VadPolicy(config)
        assertEquals(null, vad.feed(5, 50.0), "must not decide during calibration")
        assertEquals(null, vad.feed(10, 4_000.0), "speech must keep the recording open")
        val decision = vad.feed(20, 10.0)
        assertIs<VadDecision.Stop>(decision)
        assertEquals(StopReason.SILENCE, decision.reason)
        assertTrue(vad.speechDetected)
    }

    @Test
    fun `discards a tap with no speech at all`() {
        val vad = VadPolicy(config)
        val decision = vad.feed(100, 20.0)
        assertIs<VadDecision.Discard>(decision)
        assertEquals(DiscardReason.NO_SPEECH, decision.reason)
    }

    @Test
    fun `discards a burst that is too short to be a dictation`() {
        val vad = VadPolicy(config)
        vad.feed(5, 50.0)
        vad.feed(2, 4_000.0) // 40ms of speech, below minSpeechMs
        val decision = vad.feed(20, 10.0)
        assertIs<VadDecision.Discard>(decision)
        assertEquals(DiscardReason.TOO_SHORT, decision.reason)
    }

    @Test
    fun `stops at the hard duration cap while still being spoken into`() {
        val vad = VadPolicy(config)
        vad.feed(5, 50.0)
        val decision = vad.feed(500, 4_000.0)
        assertIs<VadDecision.Stop>(decision)
        assertEquals(StopReason.MAX_DURATION, decision.reason)
    }

    @Test
    fun `manual stop transcribes only when enough was said`() {
        val vad = VadPolicy(config)
        vad.feed(5, 50.0)
        vad.feed(2, 4_000.0)
        assertIs<VadDecision.Discard>(vad.onManualStop())

        vad.feed(10, 4_000.0)
        val decision = vad.onManualStop()
        assertIs<VadDecision.Stop>(decision)
        assertEquals(StopReason.MANUAL, decision.reason)
    }

    @Test
    fun `threshold adapts to a noisy room but never drops below the floor`() {
        val quiet = VadPolicy(config)
        quiet.feed(5, 1.0)
        assertEquals(config.absoluteFloor, quiet.speechThreshold)

        val noisy = VadPolicy(config)
        noisy.feed(5, 500.0)
        assertEquals(1_500.0, noisy.speechThreshold)
    }

    @Test
    fun `reset clears the state machine`() {
        val vad = VadPolicy(config)
        vad.feed(5, 50.0)
        vad.feed(10, 4_000.0)
        vad.reset()
        assertTrue(!vad.speechDetected)
        assertEquals(0.0, vad.noiseFloor)
    }
}
