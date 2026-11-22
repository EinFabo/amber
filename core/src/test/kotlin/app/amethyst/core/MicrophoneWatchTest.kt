package app.amethyst.core

import app.amethyst.core.audio.DiscardReason
import app.amethyst.core.audio.StopReason
import app.amethyst.core.audio.VadConfig
import app.amethyst.core.audio.VadDecision
import app.amethyst.core.audio.VadPolicy
import app.amethyst.core.capture.CallWatch
import app.amethyst.core.capture.MicrophoneRouter
import app.amethyst.core.capture.MicrophoneWatch
import app.amethyst.core.capture.MicrophoneWatch.Step
import app.amethyst.core.capture.MicrophoneWatch.Why
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertIs

class MicrophoneWatchTest {

    /**
     * A microphone as the recorder sees it: each entry is one read - samples at a loudness
     * (0.0 for exact zeros), nothing (null), or an error code.
     */
    private class FakeReader(private val reads: List<Any?>, private val silencedFrom: Int = Int.MAX_VALUE, private val stopAt: Int = Int.MAX_VALUE) {
        var recordedFrames = 0
            private set

        /** The recorder's loop over [watch] and [vad], as AudioCapture runs it; returns how it ended. */
        fun record(watch: MicrophoneWatch, vad: VadPolicy = VadPolicy(VadConfig(silenceTimeoutMs = Int.MAX_VALUE))): VadDecision {
            reads.forEachIndexed { i, read ->
                val samples = when (read) {
                    null -> 0
                    is Int -> read
                    else -> 320
                }
                val rms = (read as? Double) ?: 0.0
                when (val step = watch.onRead(samples, allZero = rms == 0.0, silenced = i >= silencedFrom, stopRequested = i >= stopAt)) {
                    Step.Again -> return@forEachIndexed
                    Step.Stopped -> return vad.onManualStop()
                    is Step.Lost -> return MicrophoneWatch.ending(step.why, vad.onManualStop())
                    Step.Frame, Step.Open -> Unit
                    is Step.NeverOpened -> error("only a watched start ends as never opened")
                }
                recordedFrames++
                val decision = if (i >= stopAt) vad.onManualStop() else vad.onFrame(rms)
                if (decision != VadDecision.Continue) return decision
            }
            return VadDecision.Continue
        }
    }

    private fun known() = MicrophoneWatch(frameMs = 20, silenceKnown = true)
    private fun unknown() = MicrophoneWatch(frameMs = 20, silenceKnown = false)

    private val quiet = 30.0
    private val speech = 2_000.0

    @Test
    fun `a microphone another app holds from the start is said as that, not as nothing heard`() {
        // A call is running: zeros from the first frame, and Android says the recording is silenced.
        val reader = FakeReader(List(300) { 0.0 }, silencedFrom = 0)
        assertEquals(VadDecision.Discard(DiscardReason.MIC_SILENCED), reader.record(known()))
        assertEquals(49, reader.recordedFrames, "after one second, not after the four of nothing heard")
    }

    @Test
    fun `silenced in the middle, the recording ends and what was said before is sent`() {
        val reader = FakeReader(List(15) { quiet } + List(100) { speech } + List(500) { 0.0 }, silencedFrom = 115)
        assertEquals(VadDecision.Stop(StopReason.MIC_SILENCED), reader.record(known()))
        assertEquals(115 + 49, reader.recordedFrames, "a second into the silence, not until someone taps")
    }

    @Test
    fun `a moment's overlap with another app does not end the dictation`() {
        val watch = known()
        repeat(40) { assertEquals(Step.Frame, watch.onRead(320, allZero = true, silenced = true, stopRequested = false)) }
        assertEquals(Step.Frame, watch.onRead(320, allZero = false, silenced = false, stopRequested = false))
        // The count starts over.
        repeat(49) { assertEquals(Step.Frame, watch.onRead(320, allZero = true, silenced = true, stopRequested = false)) }
        assertIs<Step.Lost>(watch.onRead(320, allZero = true, silenced = true, stopRequested = false))
    }

    @Test
    fun `where Android says whether it is silenced, a silent room is not taken for another app`() {
        // Noise suppression may deliver exact zeros in a quiet room.
        val watch = known()
        repeat(500) { assertEquals(Step.Frame, watch.onRead(320, allZero = true, silenced = false, stopRequested = false)) }
    }

    @Test
    fun `where Android cannot say, a second of exact zeros stands in for it`() {
        val reader = FakeReader(List(15) { quiet } + List(100) { speech } + List(500) { 0.0 })
        assertEquals(VadDecision.Stop(StopReason.MIC_SILENCED), reader.record(unknown()))
        val lost = FakeReader(List(300) { 0.0 }).record(unknown())
        assertEquals(VadDecision.Discard(DiscardReason.MIC_SILENCED), lost)
    }

    @Test
    fun `a quiet room with a live microphone is never silenced`() {
        val watch = unknown()
        repeat(500) { assertEquals(Step.Frame, watch.onRead(320, allZero = false, silenced = false, stopRequested = false)) }
    }

    @Test
    fun `a read that fails mid-recording returns what was captured`() {
        // DEAD_OBJECT (-6) after two seconds of speech.
        val reader = FakeReader(List(15) { quiet } + List(100) { speech } + listOf(-6))
        assertEquals(VadDecision.Stop(StopReason.MIC_LOST), reader.record(known()))
    }

    @Test
    fun `any negative code ends the recording, known or not`() {
        for (code in listOf(-1, -2, -3, -6, -32, -99)) {
            val step = assertIs<Step.Lost>(known().onRead(code, allZero = false, silenced = false, stopRequested = false))
            assertEquals(Why.UNREADABLE, step.why)
        }
    }

    @Test
    fun `a read that fails before anything was said is a microphone that could not be read`() {
        assertEquals(VadDecision.Discard(DiscardReason.MIC_LOST), FakeReader(listOf(-3)).record(known()))
    }

    @Test
    fun `reads that return nothing honour the stop tap`() {
        val reader = FakeReader(List(15) { quiet } + List(100) { speech } + List(40) { null }, stopAt = 120)
        assertEquals(VadDecision.Stop(StopReason.MANUAL), reader.record(known()))
    }

    @Test
    fun `reads that keep returning nothing end the recording with what it has`() {
        val reader = FakeReader(List(15) { quiet } + List(100) { speech } + List(200) { null })
        assertEquals(VadDecision.Stop(StopReason.MIC_LOST), reader.record(known()))
    }

    @Test
    fun `a few empty reads between frames are nothing`() {
        val watch = known()
        repeat(10) {
            repeat(MicrophoneWatch.EMPTY_READS - 1) { assertEquals(Step.Again, watch.onRead(0, allZero = false, silenced = false, stopRequested = false)) }
            assertEquals(Step.Frame, watch.onRead(320, allZero = false, silenced = false, stopRequested = false))
        }
    }

    @Test
    fun `a call that starts during the recording ends it, with what was said kept`() {
        val watch = known()
        repeat(20) { assertEquals(Step.Frame, watch.onRead(320, allZero = false, silenced = false, stopRequested = false, inCall = false)) }
        val step = watch.onRead(320, allZero = false, silenced = false, stopRequested = false, inCall = true)
        assertTrue(step is Step.Lost && step.why == Why.CALL)
        // Said as a call, not as another app taking the microphone; words before it are sent.
        assertEquals(VadDecision.Stop(StopReason.CALL), MicrophoneWatch.ending(Why.CALL, VadDecision.Stop(StopReason.MANUAL)))
        assertEquals(VadDecision.Discard(DiscardReason.CALL), MicrophoneWatch.ending(Why.CALL, VadDecision.Discard(DiscardReason.NO_SPEECH)))
    }

    @Test
    fun `a call that was already on when the dictation started changes nothing`() {
        val watch = known()
        repeat(50) { assertEquals(Step.Frame, watch.onRead(320, allZero = false, silenced = false, stopRequested = false, inCall = true)) }
    }

    @Test
    fun `a second call after the one the dictation started in ends the recording`() {
        val watch = known()
        repeat(10) { assertEquals(Step.Frame, watch.onRead(320, allZero = false, silenced = false, stopRequested = false, inCall = true)) }
        // The first call hung up; the dictation goes on.
        repeat(10) { assertEquals(Step.Frame, watch.onRead(320, allZero = false, silenced = false, stopRequested = false, inCall = false)) }
        val step = watch.onRead(320, allZero = false, silenced = false, stopRequested = false, inCall = true)
        assertTrue(step is Step.Lost && step.why == Why.CALL)
    }

    @Test
    fun `a call is seen when it begins, not before`() {
        val calls = CallWatch()
        assertFalse(calls.began(inCall = true), "already on at the first look: the user's choice")
        assertFalse(calls.began(inCall = true))
        assertFalse(calls.began(inCall = false))
        assertTrue(calls.began(inCall = true))
        assertFalse(calls.began(inCall = true), "said once per call")
        val fresh = CallWatch()
        assertFalse(fresh.began(inCall = false))
        assertTrue(fresh.began(inCall = true))
    }

    private fun watched(known: Boolean = true) =
        MicrophoneWatch(frameMs = 20, silenceKnown = known, probeMs = MicrophoneRouter.PROBE_MS)

    @Test
    fun `a watched start opens at its first sound`() {
        val watch = watched()
        // Warm-up zeros first, then the room's noise.
        repeat(3) { assertEquals(Step.Frame, watch.onRead(320, allZero = true, silenced = false, stopRequested = false)) }
        assertEquals(Step.Open, watch.onRead(320, allZero = false, silenced = false, stopRequested = false))
        // Once: from here it is an ordinary recording.
        repeat(100) { assertEquals(Step.Frame, watch.onRead(320, allZero = false, silenced = false, stopRequested = false)) }
    }

    @Test
    fun `a direct start Android silences goes to the proxy activity`() {
        // Android reports the silencing a moment after the recorder started.
        val watch = watched()
        val frames = MicrophoneRouter.PROBE_MS / 20
        repeat(2) { assertEquals(Step.Frame, watch.onRead(320, allZero = true, silenced = false, stopRequested = false)) }
        repeat(frames - 3) { assertEquals(Step.Frame, watch.onRead(320, allZero = true, silenced = true, stopRequested = false)) }
        assertIs<Step.NeverOpened>(watch.onRead(320, allZero = true, silenced = true, stopRequested = false))
    }

    @Test
    fun `a direct start of only zeros goes to the proxy activity where Android cannot tell`() {
        val watch = watched(known = false)
        val frames = MicrophoneRouter.PROBE_MS / 20
        repeat(frames - 1) { assertEquals(Step.Frame, watch.onRead(320, allZero = true, silenced = false, stopRequested = false)) }
        assertIs<Step.NeverOpened>(watch.onRead(320, allZero = true, silenced = false, stopRequested = false))
    }

    @Test
    fun `a quiet room behind noise suppression is a working microphone where Android tells`() {
        // Exact zeros, but Android says nothing silences the recording: no detour through the proxy.
        val watch = watched()
        val frames = MicrophoneRouter.PROBE_MS / 20
        repeat(frames - 1) { assertEquals(Step.Frame, watch.onRead(320, allZero = true, silenced = false, stopRequested = false)) }
        assertEquals(Step.Open, watch.onRead(320, allZero = true, silenced = false, stopRequested = false))
        repeat(500) { assertEquals(Step.Frame, watch.onRead(320, allZero = true, silenced = false, stopRequested = false)) }
    }

    @Test
    fun `an unwatched start is never held back`() {
        val watch = known()
        repeat(40) { assertEquals(Step.Frame, watch.onRead(320, allZero = true, silenced = true, stopRequested = false)) }
    }

    @Test
    fun `a watched start that fails to read is still a lost microphone, which the proxy may mend`() {
        val watch = watched()
        assertEquals(Why.UNREADABLE, assertIs<Step.Lost>(watch.onRead(-3, allZero = false, silenced = false, stopRequested = false)).why)
    }

    @Test
    fun `once open, a watched recording that Android silences ends as another app holding the microphone`() {
        val watch = watched()
        assertEquals(Step.Open, watch.onRead(320, allZero = false, silenced = false, stopRequested = false))
        repeat(49) { assertEquals(Step.Frame, watch.onRead(320, allZero = true, silenced = true, stopRequested = false)) }
        assertEquals(Why.SILENCED, assertIs<Step.Lost>(watch.onRead(320, allZero = true, silenced = true, stopRequested = false)).why)
    }
}
