package app.amethyst.core.commands

import app.amethyst.core.capture.TriggerSource

/** What a recording is for: text into a field, or a command for the phone. */
enum class RunMode { DICTATION, COMMAND }

/**
 * Which bubble touches mean a command. A double tap turns the recording the first tap started
 * into one, so a plain dictation never waits to see whether a second tap follows; without a
 * text field even a single tap is one - text could not go anywhere there.
 */
object CommandTrigger {

    /** Between the first tap's lift and the second touch, as Android's own double tap. */
    const val DOUBLE_TAP_MS = 300L

    /** A command is a few words; a longer recording is cut off here. */
    const val COMMAND_MAX_MS = 8_000

    fun modeAtStart(source: TriggerSource, commandsOn: Boolean, textFieldFocused: Boolean): RunMode =
        if (source == TriggerSource.BUBBLE && commandsOn && !textFieldFocused) RunMode.COMMAND else RunMode.DICTATION

    /**
     * Whether a touch at [downAt] is the second tap of a double tap. Only after a tap that
     * started a recording: a tap that stopped one, followed quickly by another, is two taps.
     */
    fun isDoubleTap(previousTapUpAt: Long?, previousTapStarted: Boolean, downAt: Long, commandsOn: Boolean): Boolean =
        commandsOn && previousTapStarted && previousTapUpAt != null && downAt - previousTapUpAt in 0..DOUBLE_TAP_MS

    /** What a tap on the bubble does to the run in progress. */
    enum class TapInRun {
        /** Ends the recording; the words are still recognized and used. Past it, nothing more. */
        STOP_RECORDING,

        /** Stops the command at once, between two of its steps - nothing more is done. */
        CANCEL,
    }

    /**
     * A command past its recording is stopped by a tap: a misheard count - "zwanzigmal lauter",
     * "ganz nach unten" on a screen that keeps moving - must have a way out. A dictation past
     * its recording is being written; a tap does not throw its words away.
     */
    fun tapInRun(mode: RunMode, recording: Boolean): TapInRun =
        if (mode == RunMode.COMMAND && !recording) TapInRun.CANCEL else TapInRun.STOP_RECORDING

    /**
     * Whether a tap lifted at [upAt] is the second half of a double tap whose first tap
     * stopped a command at [stoppedAt] - it starts nothing. Its lift comes at most a double-tap
     * gap plus one press (shorter than a hold, 300 ms) after the first.
     */
    fun isSecondTapOfStop(stoppedAt: Long?, upAt: Long): Boolean =
        stoppedAt != null && upAt - stoppedAt in 0..STOP_DOUBLE_TAP_MS

    private const val STOP_DOUBLE_TAP_MS = 2 * DOUBLE_TAP_MS
}
