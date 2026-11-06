package app.amethyst.core.capture

import app.amethyst.core.audio.VadDecision
import app.amethyst.core.audio.VadPolicy

/**
 * The recorder's decision per frame, with the pause in it. While [pause] is paused a frame is
 * dropped - never stored, never sent, never chunked, never shown to [vad] - so the silence
 * detection, the longest recording and the no-speech limit see only the time recorded, and
 * the audio before and after the pause joins without a gap. A stop that comes while paused
 * ends the recording as it stands. After a pause the silence count starts over: whoever goes
 * on needs a moment to begin speaking.
 */
class PausableRecording(private val vad: VadPolicy, val pause: RecordingPause = RecordingPause()) {

    sealed interface Step {
        /** Paused: forget this frame. */
        data object Drop : Step

        /** Store the frame, then act on [decision]. */
        data class Keep(val decision: VadDecision) : Step

        /** Stopped while paused: not this frame, but act on [decision] for what was recorded. */
        data class End(val decision: VadDecision) : Step
    }

    fun onFrame(rms: Double, stopRequested: Boolean): Step {
        if (pause.paused) return if (stopRequested) Step.End(vad.onManualStop()) else Step.Drop
        if (pause.takeResumed()) vad.resumed()
        return Step.Keep(if (stopRequested) vad.onManualStop() else vad.onFrame(rms))
    }
}
