package app.amethyst.core.dictation

import app.amethyst.core.commands.CommandTrigger
import app.amethyst.core.commands.RunMode

/**
 * What a tap on the bubble does, from where the run stands.
 *
 * A run exists from the first tap, not from the first sample: while the microphone is still
 * being opened - on Android 14+ through the invisible activity, which takes a moment to come
 * up - nothing works on it yet, and a second tap then is the stop of the dictation the first
 * one began. Taken for a new start it did nothing at all, and the recording that began a
 * moment later ran on with no tap left to end it.
 */
object BubbleTap {

    enum class Action {
        /** No run: this tap begins one. */
        START,

        /** Ends the recording - one that has not begun yet ends as soon as it does. */
        STOP_RECORDING,

        /** A command past its recording: stopped at once ([CommandTrigger.tapInRun]). */
        CANCEL_COMMAND,

        /** The second tap of the double tap that just stopped a command: starts nothing. */
        NOTHING,

        /**
         * A dictation past its recording is being written: a tap changes nothing - its words
         * are not thrown away - but buzzes and says how to cancel (hold the bubble).
         */
        STILL_WORKING,
    }

    /** What the tap came to, for the bubble's buzz. */
    enum class Result {
        /** A recording began: a quick second tap may make it a command. */
        STARTED,

        /** It stopped or ended something, or did nothing on purpose. */
        HANDLED,

        /** Nothing to do now: refused with a buzz and the hint ([Action.STILL_WORKING]). */
        STILL_WORKING,
    }

    /**
     * [working]: the run's work is under way (its job is active); [starting] and [listening]:
     * what the bubble shows - starting also without any work yet, while the microphone opens;
     * [secondTapOfStop]: see [CommandTrigger.isSecondTapOfStop].
     */
    fun onTap(working: Boolean, starting: Boolean, listening: Boolean, mode: RunMode, secondTapOfStop: Boolean): Action {
        if (working && !starting && !listening && mode == RunMode.DICTATION) return Action.STILL_WORKING
        if (working || starting) {
            return when (CommandTrigger.tapInRun(mode, recording = starting || listening)) {
                CommandTrigger.TapInRun.STOP_RECORDING -> Action.STOP_RECORDING
                CommandTrigger.TapInRun.CANCEL -> Action.CANCEL_COMMAND
            }
        }
        return if (secondTapOfStop) Action.NOTHING else Action.START
    }
}
