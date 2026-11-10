package app.amethyst.core

import app.amethyst.core.audio.DiscardReason
import app.amethyst.core.audio.StopReason
import app.amethyst.core.audio.VadConfig
import app.amethyst.core.audio.VadDecision
import app.amethyst.core.audio.VadPolicy
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
    fun `the cap sends a short word said long before it, as a tap would`() {
        // Real settings: a silence stop wants 350 ms of speech, a tap 120. One short word, then
        // the recording held open until the cap: sent, not discarded.
        val vad = VadPolicy(VadConfig(silenceTimeoutMs = Int.MAX_VALUE, maxDurationMs = 3_000, calibrationFrames = 5))
        vad.feed(5, 50.0)
        assertEquals(null, vad.feed(10, 4_000.0), "200 ms of speech")
        val decision = vad.feed(500, 50.0)
        assertIs<VadDecision.Stop>(decision)
        assertEquals(StopReason.MAX_DURATION, decision.reason)
    }

    @Test
    fun `the cap sends a five minute dictation`() {
        val vad = VadPolicy(VadConfig(silenceTimeoutMs = Int.MAX_VALUE, calibrationFrames = 5))
        assertEquals(300_000, VadConfig().maxDurationMs)
        vad.feed(5, 50.0)
        // Speech with pauses, as long as the cap allows: 4 s spoken, 1 s quiet.
        var decision: VadDecision? = null
        var frames = 5
        while (decision == null && frames < 20_000) {
            decision = vad.feed(200, 4_000.0) ?: vad.feed(50, 50.0)
            frames += 250
        }
        assertIs<VadDecision.Stop>(decision)
        assertEquals(StopReason.MAX_DURATION, decision.reason)
        assertTrue(frames * 20 in 300_000..300_000 + 5_000, "stopped after ${frames * 20} ms")
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

    @Test
    fun `speaking from the very first frame does not raise the threshold to the voice`() {
        // Push to talk: the user already speaks when recording starts. The floor comes from the
        // quietest calibration frame (a gap between syllables), not from the speech around it.
        val vad = VadPolicy(config)
        listOf(3_000.0, 3_200.0, 80.0, 2_900.0, 3_100.0).forEach { vad.onFrame(it) }
        assertEquals(240.0, vad.speechThreshold)
        assertTrue(vad.speechDetected, "the words said during calibration count")
    }

    @Test
    fun `a short word said entirely during calibration survives a manual stop`() {
        val vad = VadPolicy(config.copy(minManualSpeechMs = 60))
        // "Ja": three loud frames inside calibration, then the user lets go.
        listOf(2_500.0, 2_600.0, 2_400.0, 60.0, 50.0).forEach { vad.onFrame(it) }
        vad.feed(3, 50.0)
        val decision = vad.onManualStop()
        assertIs<VadDecision.Stop>(decision)
        assertEquals(StopReason.MANUAL, decision.reason)
    }

    @Test
    fun `a measured floor never exceeds the ceiling`() {
        val vad = VadPolicy(config)
        repeat(5) { vad.onFrame(5_000.0) }
        assertEquals(config.maxNoiseFloor * config.speechFactor, vad.speechThreshold)
    }

    @Test
    fun `a manual stop accepts less speech than a silence stop`() {
        val manual = VadPolicy(config.copy(minSpeechMs = 300, minManualSpeechMs = 100))
        manual.feed(5, 50.0)
        manual.feed(6, 4_000.0) // 120 ms of speech
        assertIs<VadDecision.Stop>(manual.onManualStop())

        val silence = VadPolicy(config.copy(minSpeechMs = 300, minManualSpeechMs = 100))
        silence.feed(5, 50.0)
        silence.feed(6, 4_000.0)
        val decision = silence.feed(20, 10.0)
        assertIs<VadDecision.Discard>(decision)
        assertEquals(DiscardReason.TOO_SHORT, decision.reason)
    }
}
