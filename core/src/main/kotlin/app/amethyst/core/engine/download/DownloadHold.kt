package app.amethyst.core.engine.download

import app.amethyst.core.engine.FileProblem

/** The phone's network as a download sees it; none at all is `null`. */
data class NetworkFacts(
    /** Android reached the internet through it - not a Wi-Fi that only shows a login page. */
    val validated: Boolean,
    val metered: Boolean,
)

/** What a download in hand does next ([DownloadHold.step]). */
enum class HoldStep {
    START,

    /** No network it may use, and mobile data was not allowed: Wi-Fi will start it. */
    WAIT_FOR_WIFI,

    /** Mobile data is allowed, but there is no working network at all. */
    WAIT_FOR_NETWORK,

    /** The last try failed; the next one has its time ([DownloadHold.notBeforeMs]). */
    WAIT_FOR_TURN,
}

/**
 * A model download kept in hand until the model is on the phone: when it may start, and when
 * to try again after a failure - after 1, 5, 15 and 60 minutes, then every hour, sooner when
 * the connection that broke comes back. Only on a network Android has validated, and on a
 * metered one only if the user allowed it for this download. It decides; the app asks it when
 * the network changes or a wait is over - no loop polls.
 *
 * Not thread-safe: the app keeps it to one thread.
 */
class DownloadHold(private val nowMs: () -> Long) {

    /** The user allowed mobile data for this download. */
    var mobileData = false
        private set

    /** Failed tries in a row since the last one that got anywhere. */
    var failures = 0
        private set

    /** No try before this time ([nowMs]'s clock). */
    var notBeforeMs = 0L
        private set

    private var brokenConnection = false

    /** A download taken in hand, or started again by the user: no waits left over. */
    fun take(mobileData: Boolean) {
        this.mobileData = mobileData
        failures = 0
        notBeforeMs = 0
        brokenConnection = false
    }

    /**
     * A try failed for [problem]. True if another one will follow by itself; false where no
     * waiting helps (a full phone, a file that is not the pinned one, an address that is
     * gone) and only the user can try again. [progressKept]: bytes arrived and stay for the
     * next try, which starts the schedule over - a long download in many pieces is not slowed
     * down. A download that [resumes] nothing never repeats itself over mobile data: the
     * megabytes the user agreed to would be spent again each time.
     */
    fun failed(problem: FileProblem, progressKept: Boolean, resumes: Boolean): Boolean {
        if (!worthAnotherTry(problem)) return false
        if (progressKept) failures = 0
        failures++
        if (!resumes) mobileData = false
        brokenConnection = problem == FileProblem.NoConnection || problem == FileProblem.Interrupted
        notBeforeMs = nowMs() + waitMs(failures)
        return true
    }

    /** A working network after none: a try that broke with the connection need not wait out its turn. */
    fun networkBack() {
        if (brokenConnection) notBeforeMs = minOf(notBeforeMs, nowMs())
    }

    fun step(network: NetworkFacts?): HoldStep {
        val usable = network != null && network.validated && (!network.metered || mobileData)
        return when {
            !usable -> if (mobileData) HoldStep.WAIT_FOR_NETWORK else HoldStep.WAIT_FOR_WIFI
            nowMs() < notBeforeMs -> HoldStep.WAIT_FOR_TURN
            else -> HoldStep.START
        }
    }

    companion object {
        private val WAITS_MIN = listOf(1L, 5L, 15L, 60L)

        /** The wait after the [failures]th failed try in a row. */
        fun waitMs(failures: Int): Long = WAITS_MIN[(failures - 1).coerceIn(0, WAITS_MIN.lastIndex)] * 60_000

        /** Whether trying again by itself can fix [problem]. */
        fun worthAnotherTry(problem: FileProblem): Boolean = when (problem) {
            FileProblem.NoConnection, FileProblem.Interrupted, FileProblem.EmptyAnswer, is FileProblem.Incomplete -> true
            is FileProblem.ServerAnswered -> problem.code >= 500 || problem.code == 408 || problem.code == 425 || problem.code == 429
            else -> false
        }
    }
}
