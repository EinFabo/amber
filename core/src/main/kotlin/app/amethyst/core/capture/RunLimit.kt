package app.amethyst.core.capture

/**
 * How long one run may take before it is cancelled: the watchdog for a recognizer that never
 * calls back and a server that takes the upload and goes quiet. While the recording runs
 * nobody knows how long it will get, so the limit covers the longest one allowed
 * ([whileRecordingMs]) - with the defaults about fourteen minutes. Once the recording has
 * ended its length is known, and the limit is cut down to what that recording can still need
 * ([recordingEnded]): a ten second dictation that hangs is given up after minutes less.
 *
 * Time spent paused counts toward nothing. Read by the watchdog, shortened from the recorder's
 * thread and the main one.
 */
class RunLimit(
    whileRecordingMs: Long,
    private val pause: RecordingPause,
    private val now: () -> Long,
) {
    private val startedAt = now()

    /** The whole run's limit as it stands, in ms from its start, paused time not counted. */
    @Volatile
    var limitMs: Long = whileRecordingMs
        private set

    /** How long the run has taken so far, paused time not counted. */
    fun elapsedMs(): Long = limitMs - remainingMs()

    /** How much longer the run may take; zero or less when its time is up. */
    fun remainingMs(): Long = pause.remainingMs(limitMs, startedAt, now())

    /**
     * The recording has ended: from now on the run may take [afterMs] more - never longer
     * than it could before, and a second call never gives back what the first took.
     */
    @Synchronized
    fun recordingEnded(afterMs: Long) {
        limitMs = minOf(limitMs, elapsedMs() + afterMs.coerceAtLeast(0))
    }

    companion object {
        /** How often the watchdog looks whether its limit got shorter meanwhile. */
        const val LOOK_AGAIN_MS = 1_000L
    }
}
