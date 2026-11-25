package app.amethyst.core.onboarding

/**
 * Whether the bubble's accessibility service can be trusted: Android's switch says it is on,
 * but only a live connection means the bubble can show and text can be inserted.
 */
enum class ServiceHealth {
    /** The switch is off. */
    OFF,

    /** The switch is on, the connection not yet there - the app has only just started. */
    STARTING,

    /** The switch is on and the service is connected. */
    RUNNING,

    /** The switch is on, yet the service never connected: Android lists it, nothing runs. */
    NOT_RUNNING,
    ;

    companion object {
        /** How long after the app started a service that is on may still be about to connect. */
        const val STARTUP_GRACE_MS = 2_000L

        fun of(switchOn: Boolean, connected: Boolean, msSinceAppStart: Long): ServiceHealth = when {
            !switchOn -> OFF
            connected -> RUNNING
            msSinceAppStart > STARTUP_GRACE_MS -> NOT_RUNNING
            else -> STARTING
        }
    }
}
