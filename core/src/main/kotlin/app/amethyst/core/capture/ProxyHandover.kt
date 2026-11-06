package app.amethyst.core.capture

/**
 * A dictation handed to the invisible microphone activity (Android 14+ with another app in
 * front): the run waits until the activity is up and calls back in. Between the launch and
 * that call the user may release, cancel or start over from the bubble - and only a run that
 * still waits may record when the activity arrives. A cancelled handover records nothing; a
 * start over takes the activity already on its way rather than launching a second one.
 *
 * Called from the main thread and the activity's: every call holds the lock.
 */
class ProxyHandover(
    /** After this long without arriving, an activity launched counts as lost. */
    private val lostAfterMs: Long,
) {

    /** What the activity that arrived is to do: record, with a release that came meanwhile. */
    data class Arrival(val stopped: Boolean)

    private var launchedAt: Long? = null
    private var waiting = false
    private var stopped = false

    /** Whether a run waits for the activity. */
    val pending: Boolean
        @Synchronized get() = waiting

    /**
     * A run is to record through the activity. True when one must be launched; false when one
     * launched before is still on its way - it now carries this run.
     */
    @Synchronized
    fun begin(now: Long): Boolean {
        waiting = true
        stopped = false
        val onItsWay = launchedAt?.let { now - it < lostAfterMs } == true
        if (!onItsWay) launchedAt = now
        return !onItsWay
    }

    /** A release before the activity arrived: the run it starts stops at once. */
    @Synchronized
    fun stop() {
        if (waiting) stopped = true
    }

    /** The run was cancelled: the activity, when it arrives, starts nothing. */
    @Synchronized
    fun cancel() {
        waiting = false
        stopped = false
    }

    /** The wait gave up on the activity: whatever arrives later starts nothing, and the next run launches anew. */
    @Synchronized
    fun lost() {
        cancel()
        launchedAt = null
    }

    /** The activity is up. Null when no run waits for it - it records nothing then. */
    @Synchronized
    fun arrive(): Arrival? {
        launchedAt = null
        if (!waiting) return null
        val arrival = Arrival(stopped)
        cancel()
        return arrival
    }
}
