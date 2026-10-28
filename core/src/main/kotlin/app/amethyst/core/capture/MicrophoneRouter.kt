package app.amethyst.core.capture

/**
 * Who asked for a dictation. It decides how the microphone is obtained.
 *
 * The distinction is not cosmetic: a tap on the bubble happens while a foreign app is in
 * front, which Android 14+ treats as a background microphone start. A press inside Amethyst
 * is already foreground and must never open an extra window to prove it.
 */
enum class TriggerSource {
    /** Tap on the floating bubble - Amethyst itself is in the background. */
    BUBBLE,

    /** Re-entry from the invisible proxy activity, which is now the foreground anchor. */
    PROXY_ACTIVITY,

    /** Button inside Amethyst. The app is visible, so recording is always permitted. */
    IN_APP,
    ;

    /** Only the bubble path may fall back to the proxy activity; the others already are it. */
    val mayUseProxyFallback: Boolean get() = this == BUBBLE
}

enum class MicrophoneRoute {
    /** Nothing to arrange - start reading the microphone. */
    RECORD_NOW,

    /** Visible right now, so start the service first; it keeps later bubble taps direct. */
    START_SERVICE_THEN_RECORD,

    /** Background, no foreground anchor - hand over to the invisible activity. */
    VIA_PROXY_ACTIVITY,

    /** Cannot record at all yet. */
    NEEDS_PERMISSION,
}

/**
 * Picks how to get at the microphone.
 *
 * Getting this wrong fails in one of two silent ways: a stray window flashing up in the
 * middle of someone's chat, or a recording that never starts because Android refused a
 * background microphone start. Neither is visible until it happens on a real device, which is
 * exactly why the rule lives here as a pure function instead of inside the controller.
 */
object MicrophoneRouter {

    fun route(
        source: TriggerSource,
        hasPermission: Boolean,
        foregroundServiceRunning: Boolean,
    ): MicrophoneRoute = when {
        !hasPermission -> MicrophoneRoute.NEEDS_PERMISSION

        // The service grants the whole process while-in-use access, so with it up every
        // trigger can record directly.
        foregroundServiceRunning -> MicrophoneRoute.RECORD_NOW

        source == TriggerSource.BUBBLE -> MicrophoneRoute.VIA_PROXY_ACTIVITY

        // Both remaining sources are foreground: the proxy activity is itself the anchor,
        // and an in-app press has Amethyst on screen. Starting the service here is legal
        // and saves the next bubble tap from the detour.
        else -> MicrophoneRoute.START_SERVICE_THEN_RECORD
    }
}
