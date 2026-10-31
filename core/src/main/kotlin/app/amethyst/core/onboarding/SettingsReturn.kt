package app.amethyst.core.onboarding

/** What Amber sends the user into Android's settings to switch on. */
enum class SettingsTrip(
    /** How long after the trip Amber still comes back by itself once it is on. */
    val windowMs: Long,
) {
    /** The accessibility service; it is on when the service connects. */
    ACCESSIBILITY(5 * 60_000L),

    /** The battery exemption; the app looks for it every half second while this lasts. */
    BATTERY(2 * 60_000L),
}

/**
 * Whether Amber brings itself back to the front: it sent the user to switch something on, and
 * once it is on they land in Amber again without pressing Back. Only for the latest trip, only
 * within its window, and only once - a service that reconnects an hour later, or twice in a
 * row, must not pull Amber over whatever the user is doing by then.
 *
 * Times come from a monotonic clock (elapsedRealtime), passed in. Called from the main thread
 * and from the battery watch, hence synchronized.
 */
class SettingsReturn {

    private var trip: SettingsTrip? = null
    private var sentAt = 0L

    /** The user was just sent to switch [trip] on; an earlier trip no longer counts. */
    @Synchronized
    fun sent(trip: SettingsTrip, nowMs: Long) {
        this.trip = trip
        sentAt = nowMs
    }

    /** Whether Amber would still come back once [trip] is on. */
    @Synchronized
    fun pending(trip: SettingsTrip, nowMs: Long): Boolean =
        this.trip == trip && nowMs - sentAt in 0 until trip.windowMs

    /**
     * [trip] is on now: true if Amber should come back - exactly once, while the trip is due.
     * The request is used up either way, so a late answer cannot fire later still.
     */
    @Synchronized
    fun take(trip: SettingsTrip, nowMs: Long): Boolean {
        if (this.trip != trip) return false
        val due = pending(trip, nowMs)
        this.trip = null
        return due
    }

    /** The user came back by themselves: nothing is due any more. */
    @Synchronized
    fun cancel() {
        trip = null
    }
}
