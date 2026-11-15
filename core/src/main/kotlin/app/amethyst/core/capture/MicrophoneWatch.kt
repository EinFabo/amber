package app.amethyst.core.capture

import app.amethyst.core.audio.DiscardReason
import app.amethyst.core.audio.StopReason
import app.amethyst.core.audio.VadDecision

/**
 * What the recorder makes of each read from the microphone, besides the audio in it.
 *
 * Three things go wrong without an error the recorder would notice:
 *
 * - **Another app holds the microphone** - a call, another recorder. From Android 10 the read
 *   then succeeds and delivers zeros: at the start that looked like "nothing heard" and the
 *   dictation was thrown away; in the middle, silence was recorded until someone tapped.
 *   Android says so itself where it can ([silenced], the recording configuration's "client
 *   silenced"); where nobody can ask ([silenceKnown] false: Android 9), exact digital zeros
 *   stand in for it - a live microphone always has some noise. Either for [SILENCED_AFTER_MS]
 *   ends the recording: a moment's overlap with another app is not the end of a dictation.
 * - **A read that returns nothing** used to be asked again at once, forever, without a look
 *   at the stop tap. Now a tap ends the recording, and [EMPTY_READS] in a row end it too.
 * - **A read that fails** mid-recording used to drop everything recorded. Now it ends the
 *   recording, and what was captured is recognized.
 *
 * Whatever ends it here, what was said before is kept and sent like a tap's ([ending]).
 */
class MicrophoneWatch(
    private val frameMs: Int,
    /** Whether Android tells when another app silences this recording (from Android 10). */
    private val silenceKnown: Boolean,
) {

    sealed interface Step {
        /** Samples came: go on with them. */
        data object Frame : Step

        /** Nothing came and nothing ended: read again. */
        data object Again : Step

        /** The stop tap, seen while no samples came. */
        data object Stopped : Step

        /** The microphone is gone, for [why]: the recording ends with what it has. */
        data class Lost(val why: Why, val technical: String) : Step
    }

    enum class Why {
        /** Another app took the microphone: Android delivers silence instead of the voice. */
        SILENCED,

        /** The read failed or stopped delivering. */
        UNREADABLE,
    }

    private var silentMs = 0
    private var emptyReads = 0

    /**
     * After every read: [read] is its result (samples, zero, or a negative error code),
     * [allZero] whether the samples were exact zeros, [silenced] what Android says right now.
     */
    fun onRead(read: Int, allZero: Boolean, silenced: Boolean, stopRequested: Boolean): Step {
        if (read < 0) return Step.Lost(Why.UNREADABLE, "the read failed with $read")
        if (read == 0) {
            if (stopRequested) return Step.Stopped
            emptyReads++
            return if (emptyReads >= EMPTY_READS) Step.Lost(Why.UNREADABLE, "$emptyReads reads in a row returned nothing") else Step.Again
        }
        emptyReads = 0
        val silent = if (silenceKnown) silenced else allZero
        silentMs = if (silent) silentMs + frameMs else 0
        if (silentMs >= SILENCED_AFTER_MS) {
            val how = if (silenceKnown) "Android silenced the recording" else "only zeros came"
            return Step.Lost(Why.SILENCED, "$how for $silentMs ms - another app holds the microphone")
        }
        return Step.Frame
    }

    companion object {
        /** How long the microphone must stay silenced before the recording ends for it. */
        const val SILENCED_AFTER_MS = 1_000

        /** How many reads in a row may return nothing before the microphone counts as gone. */
        const val EMPTY_READS = 50

        /**
         * How a recording ends that lost its microphone for [why]: [manualStop] is what a tap
         * at this moment would do ([app.amethyst.core.audio.VadPolicy.onManualStop]). Words
         * worth sending are sent, the reason kept; without them the user is told why nothing
         * came of it - not "nothing heard".
         */
        fun ending(why: Why, manualStop: VadDecision): VadDecision = when (manualStop) {
            is VadDecision.Stop -> VadDecision.Stop(if (why == Why.SILENCED) StopReason.MIC_SILENCED else StopReason.MIC_LOST)
            else -> VadDecision.Discard(if (why == Why.SILENCED) DiscardReason.MIC_SILENCED else DiscardReason.MIC_LOST)
        }
    }
}
