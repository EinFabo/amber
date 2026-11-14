package app.amethyst.core.dictation

import app.amethyst.core.commands.RunMode

/**
 * The power button during a dictation. Whoever turns the screen off is done speaking: left
 * running, the recording went on in the pocket until the longest recording - up to five
 * minutes of whatever was said near the phone, sent to the server.
 *
 * So the recording ends with the screen, as a tap would end it: what was said so far is
 * recognized. It is then not put anywhere - the field is behind the lock screen, and the lock
 * screen's own fields are not the user's - but kept, for "Insert again" once the phone is back.
 */
object ScreenOff {

    enum class Action {
        /** Nothing is being recorded: a text on its way is dealt with when it is ready ([mayInsert]). */
        NOTHING,

        /** A dictation is being recorded: it ends here, and what was said is recognized. */
        STOP_RECORDING,

        /** Nothing worth keeping: a recording that has not begun, or a voice command - not for a dark screen. */
        CANCEL,
    }

    /**
     * What the screen going off does to the run. [recording]: it is starting or listening;
     * [begun]: its recording has begun - false while the microphone is still being opened.
     */
    fun onScreenOff(recording: Boolean, begun: Boolean, mode: RunMode): Action = when {
        !recording -> Action.NOTHING
        !begun || mode == RunMode.COMMAND -> Action.CANCEL
        else -> Action.STOP_RECORDING
    }

    /**
     * Whether a text that is ready now may go into a field: only while the screen is on and
     * the phone unlocked. Otherwise it is kept, never typed behind or onto the lock screen.
     */
    fun mayInsert(screenOn: Boolean, locked: Boolean): Boolean = screenOn && !locked
}
