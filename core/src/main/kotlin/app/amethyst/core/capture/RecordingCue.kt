package app.amethyst.core.capture

/**
 * When a recording's signals are due - the buzz and the start tone when the microphone is
 * really open (not at the tap: opening it can take a moment, through the invisible activity
 * longer), the stop tone when that recording ends. One of each per recording, whatever the
 * recorder reports in between; a recording whose microphone never opened gets neither.
 *
 * One per listener: the bubble keeps its own for the buzz, the dictation its own for the tones.
 */
class RecordingCue {

    enum class Cue { OPEN, CLOSED }

    private var open = false

    /** The microphone is open (said again with every level): [Cue.OPEN] the first time, then null. */
    @Synchronized
    fun opened(): Cue? {
        if (open) return null
        open = true
        return Cue.OPEN
    }

    /** The recording ended, or the run is over: [Cue.CLOSED] once if the microphone had opened, else null. */
    @Synchronized
    fun ended(): Cue? {
        if (!open) return null
        open = false
        return Cue.CLOSED
    }
}
