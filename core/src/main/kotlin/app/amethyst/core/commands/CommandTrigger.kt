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
}
