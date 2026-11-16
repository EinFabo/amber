package app.amethyst.core.capture

/**
 * The start tone plays once the microphone is really open ([RecordingCue]) - so the microphone
 * hears it, and the beep would be the first thing in the recording, where a recognition may
 * write it down as a word. The recording's first [ms] are silenced instead: the beep is short
 * (90 ms) but starts a little late, and no one speaks that soon after it - hearing a beep and
 * starting to talk takes longer - so no word is lost.
 *
 * Why not the tone before the recorder starts: opening the microphone can take a moment,
 * through the invisible activity longer, and a tone that comes before it is open invites the
 * first word into a recording that has not begun - the very thing the tone at the open
 * microphone is for.
 *
 * Silenced, not dropped: the recording keeps its length, the pause detection sees quiet. Only
 * after the recorder's own check for a silenced microphone has seen the frame.
 */
class StartToneMute(sampleRate: Int, ms: Int) {

    private val samples = sampleRate.toLong() * ms / 1000

    private var seen = 0L

    /** Silences what of the frame's first [read] samples falls in the recording's first moments. */
    fun apply(frame: ShortArray, read: Int) {
        if (read <= 0 || seen >= samples) return
        val until = minOf(read.toLong(), samples - seen).toInt()
        frame.fill(0, 0, until)
        seen += read
    }

    companion object {
        /** How long the start of a recording is silenced when the start tone plays. */
        const val START_TONE_MS = 250
    }
}
