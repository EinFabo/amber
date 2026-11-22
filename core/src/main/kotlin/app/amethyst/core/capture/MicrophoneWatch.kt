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
 * - **A phone call starts** while it records. Android does not silence an accessibility
 *   service's recording for a call (Pixel 9a, Android 17, 2026-11-22: a dictation went on
 *   through a call and wrote down the user's side of it). So a call that begins - ringing,
 *   a phone call, a call in an app ([inCall]) - ends the recording; one that was already on
 *   when the dictation started is the user's choice and changes nothing, the next one after
 *   it hung up ends it again ([CallWatch]).
 *
 * Whatever ends it here, what was said before is kept and sent like a tap's ([ending]).
 *
 * A direct start from the bubble is watched for its first [probeMs] as well
 * ([MicrophoneRouter.startProbeMs]): recording in the accessibility service's process relies on
 * Android granting it the microphone, and where it does not, the recorder starts and delivers
 * silence. The microphone counts as open only once a frame carries sound ([Step.Open]); a start
 * that stays silent the whole time is no microphone at all ([Step.NeverOpened]), and the proxy
 * activity takes over. Nothing is lost by it: what came was silence.
 */
class MicrophoneWatch(
    private val frameMs: Int,
    /** Whether Android tells when another app silences this recording (from Android 10). */
    private val silenceKnown: Boolean,
    /** How long the start is watched for a microphone that never delivers sound; 0 for no watch. */
    private val probeMs: Int = 0,
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

        /**
         * Samples came and the microphone is open now - the first sound of a watched start
         * ([probeMs]): go on with them, and tell the user it records. Once per recording.
         */
        data object Open : Step

        /** A watched start delivered only silence: this way to the microphone does not work. */
        data class NeverOpened(val technical: String) : Step
    }

    enum class Why {
        /** Another app took the microphone: Android delivers silence instead of the voice. */
        SILENCED,

        /** The read failed or stopped delivering. */
        UNREADABLE,

        /** A call began after the recording started: what follows is the call, not a dictation. */
        CALL,
    }

    private var silentMs = 0
    private var emptyReads = 0

    /** Whether the start is still watched: no sound yet, within [probeMs]. */
    private var probing = probeMs > 0
    private var probedMs = 0

    private val calls = CallWatch()

    /**
     * After every read: [read] is its result (samples, zero, or a negative error code),
     * [allZero] whether the samples were exact zeros, [silenced] what Android says right now,
     * [inCall] whether the phone is ringing or in a call (also one in an app) right now.
     */
    fun onRead(read: Int, allZero: Boolean, silenced: Boolean, stopRequested: Boolean, inCall: Boolean = false): Step {
        if (calls.began(inCall)) return Step.Lost(Why.CALL, "a call started during the recording")
        if (read < 0) return Step.Lost(Why.UNREADABLE, "the read failed with $read")
        if (read == 0) {
            if (stopRequested) return Step.Stopped
            emptyReads++
            return if (emptyReads >= EMPTY_READS) Step.Lost(Why.UNREADABLE, "$emptyReads reads in a row returned nothing") else Step.Again
        }
        emptyReads = 0
        if (probing) return probe(allZero, silenced)
        val silent = if (silenceKnown) silenced else allZero
        silentMs = if (silent) silentMs + frameMs else 0
        if (silentMs >= SILENCED_AFTER_MS) {
            val how = if (silenceKnown) "Android silenced the recording" else "only zeros came"
            return Step.Lost(Why.SILENCED, "$how for $silentMs ms - another app holds the microphone")
        }
        return Step.Frame
    }

    /**
     * The start of a watched recording. Sound - samples that are not all zero, from a recording
     * Android does not silence - opens it at once. At the end of [probeMs] without sound, the
     * silence decides as it would later: where Android tells, its word counts (a quiet room
     * behind noise suppression gives exact zeros too, and is a working microphone); where it
     * cannot, zeros are no microphone. Android's word is read at the end, not the first frame:
     * it reports a silenced recording a moment after the recorder starts.
     */
    private fun probe(allZero: Boolean, silenced: Boolean): Step {
        if (!allZero && !silenced) {
            probing = false
            return Step.Open
        }
        probedMs += frameMs
        if (probedMs < probeMs) return Step.Frame
        probing = false
        val silent = if (silenceKnown) silenced else allZero
        if (!silent) return Step.Open
        val how = if (silenceKnown) "Android silenced the recording" else "only zeros came"
        return Step.NeverOpened("$how from its start for $probedMs ms")
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
        fun ending(why: Why, manualStop: VadDecision): VadDecision {
            // A call is said as a call: "another app took the microphone" left the user guessing.
            return when (manualStop) {
                is VadDecision.Stop -> VadDecision.Stop(
                    when (why) {
                        Why.SILENCED -> StopReason.MIC_SILENCED
                        Why.UNREADABLE -> StopReason.MIC_LOST
                        Why.CALL -> StopReason.CALL
                    },
                )
                else -> VadDecision.Discard(
                    when (why) {
                        Why.SILENCED -> DiscardReason.MIC_SILENCED
                        Why.UNREADABLE -> DiscardReason.MIC_LOST
                        Why.CALL -> DiscardReason.CALL
                    },
                )
            }
        }
    }
}
