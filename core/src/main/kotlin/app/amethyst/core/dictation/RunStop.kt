package app.amethyst.core.dictation

/**
 * The stop a tap asks of the run in progress - and whose stop it is.
 *
 * One flag used to serve every run, cleared by each run when it ended. But a cancelled run can
 * end late: a recognition on the phone cannot be aborted, so after "Cancel" its call still
 * returns seconds later, while the next dictation is already recording. Clearing the flag then
 * took away the tap that had just stopped that next dictation, and it recorded on.
 *
 * So each run gets a token when it begins, and only the run whose token is still the current
 * one clears the stop when it ends ([end]). Asked for from the main thread, read by the
 * recorder's, ended on the run's: every call holds the lock.
 */
class RunStop {

    private var current = 0
    private var asked = false

    /**
     * A run begins and is the current one from here. [stoppedBefore]: a tap already stopped it
     * while its microphone was being opened. Returns its token for [end].
     */
    @Synchronized
    fun begin(stoppedBefore: Boolean = false): Int {
        asked = stoppedBefore
        return ++current
    }

    /** A tap: the current run's recording is to end. */
    @Synchronized
    fun request() {
        asked = true
    }

    /** Whether the current run was asked to stop. */
    val requested: Boolean
        @Synchronized get() = asked

    /**
     * The run with [token] is over. True - and the stop cleared for whatever comes next - only
     * while it is still the current run; a run that another has followed changes nothing.
     */
    @Synchronized
    fun end(token: Int): Boolean {
        if (token != current) return false
        asked = false
        return true
    }
}
