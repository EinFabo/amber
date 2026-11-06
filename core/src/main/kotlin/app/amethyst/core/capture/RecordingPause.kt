package app.amethyst.core.capture

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * A recording paused from the bubble's quick menu: the microphone stays open, so going on is
 * instant and needs no new start from the background, but nothing heard meanwhile is kept
 * ([PausableRecording]). Keeps the paused time, which no limit of the run counts. One pause
 * lasts at most [maxPauseMs]: a forgotten one must not hold the microphone, the foreground
 * service and the privacy indicator forever.
 *
 * Paused and resumed from the main thread, read by the recorder's thread: every call holds
 * the lock.
 */
class RecordingPause(private val maxPauseMs: Long = MAX_PAUSE_MS) {

    private var since: Long? = null
    private var before = 0L
    private var resumedSinceAsked = false
    private var pauses = 0
    private var ranOut = false

    val paused: Boolean
        @Synchronized get() = since != null

    /** False when it already was. */
    @Synchronized
    fun pause(now: Long): Boolean {
        if (since != null) return false
        since = now
        pauses++
        return true
    }

    /** False when it was not paused. */
    @Synchronized
    fun resume(now: Long): Boolean {
        val from = since ?: return false
        before += (now - from).coerceAtLeast(0)
        since = null
        resumedSinceAsked = true
        return true
    }

    /** All the time spent paused up to [now], a pause still going included. */
    @Synchronized
    fun pausedMs(now: Long): Long = before + (since?.let { (now - it).coerceAtLeast(0) } ?: 0L)

    /** How much longer a run limited to [limitMs] that began at [startedAt] may take at [now]. */
    fun remainingMs(limitMs: Long, startedAt: Long, now: Long): Long = limitMs - (now - startedAt - pausedMs(now))

    /**
     * Whether the pause going on at [now] has lasted [maxPauseMs]: the recording ends then as
     * if tapped. Once true, the run trace says so ([summary]).
     */
    @Synchronized
    fun ranOut(now: Long): Boolean {
        val from = since ?: return false
        if (now - from >= maxPauseMs) ranOut = true
        return ranOut
    }

    /** Whether it was resumed since the last call - once per resume. */
    @Synchronized
    fun takeResumed(): Boolean = resumedSinceAsked.also { resumedSinceAsked = false }

    /** "paused 4.2 s" for the run trace, and why it ended if the pause ran out; null when it never was. */
    fun summary(now: Long): String? {
        val (count, limited) = synchronized(this) { pauses to ranOut }
        if (count == 0) return null
        val line = String.format(Locale.ROOT, "paused %.1f s", pausedMs(now) / 1000.0)
        return if (limited) "$line, ended: paused longer than ${maxPauseMs / 60_000} min" else line
    }

    companion object {
        /** The longest one pause may last before the recording ends by itself. */
        const val MAX_PAUSE_MS = 5 * 60_000L
    }
}

/**
 * [block] with a time limit of [limitMs] that does not count the time [pause] was paused: the
 * run's whole limit, so a paused dictation is not cancelled for taking too long. Null when the
 * limit ran out; [block] is cancelled then.
 */
suspend fun <T : Any> withPausableTimeout(
    limitMs: Long,
    pause: RecordingPause,
    now: () -> Long,
    block: suspend CoroutineScope.() -> T,
): T? = coroutineScope {
    val startedAt = now()
    val work = async { block() }
    while (true) {
        val left = pause.remainingMs(limitMs, startedAt, now())
        if (left <= 0) {
            work.cancel()
            return@coroutineScope null
        }
        // Wakes when the limit would run out without a pause, then looks again.
        withTimeoutOrNull(left) { work.await() }?.let { return@coroutineScope it }
    }
    @Suppress("UNREACHABLE_CODE")
    null
}
