package app.amethyst.core.dictation

/**
 * A dictation cancelled while the bubble works on it, and what may begin while it ends.
 *
 * Once the words are there, the insertion runs without a point where a cancel could stop it:
 * it finds the field, types, checks its work in short sleeps, and on a slow field that takes
 * seconds - just when the bubble says "Still working. Hold the bubble to cancel." A cancel then
 * ends nothing: the words still land. Keeping the recording for "Try again" there would type
 * them a second time.
 *
 * And a cancelled run ends late. A recognition on the phone cannot be aborted, so the run goes
 * on until it returns; an insertion goes on until it is done. Its last steps - the words kept,
 * the bubble's "done" - would land on whatever began meanwhile.
 */
object CancelledRun {

    /**
     * Whether a cancel keeps the run's recording for "Try again": only once the recording has
     * ended and the bubble is [working] on it, and only before the words began to go in
     * ([delivering]). [keepRecording] false is "Start over", which asks for a new recording.
     */
    fun keepsRecording(keepRecording: Boolean, working: Boolean, delivering: Boolean): Boolean =
        keepRecording && working && !delivering

    /**
     * Whether a new run may begin now - a [retry] ("Try again") or a new dictation - while the
     * run before has not [previousEnded]. "Try again" waits until it has ended: its outcome
     * decides whether the kept recording is still wanted, and its last steps must not land on
     * the retry. A new dictation waits only while the run before is [previousDelivering] - a
     * cancelled recognition on the phone may still unwind while the next dictation records
     * ([RunStop]); an insertion still going ends in seconds, and the bubble's "done" with it.
     */
    fun mayBegin(retry: Boolean, previousEnded: Boolean, previousDelivering: Boolean): Boolean = when {
        previousEnded -> true
        retry -> false
        else -> !previousDelivering
    }
}
