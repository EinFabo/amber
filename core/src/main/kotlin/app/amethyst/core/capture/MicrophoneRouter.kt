package app.amethyst.core.capture

import app.amethyst.core.engine.Problem

/**
 * Who asked for a dictation. It decides how the microphone is obtained.
 *
 * The distinction is not cosmetic: a tap on the bubble happens while a foreign app is in
 * front, which Android 11+ treats as a background microphone start unless something gives
 * the process while-in-use access. A press inside Embersay is already foreground and must
 * never open an extra window to prove it.
 */
enum class TriggerSource {
    /** Tap on the floating bubble - Embersay itself is in the background. */
    BUBBLE,

    /** Re-entry from the invisible proxy activity, which is now the foreground anchor. */
    PROXY_ACTIVITY,

    /** Button inside Embersay. The app is visible, so recording is always permitted. */
    IN_APP,
    ;

    /** Only the bubble path may fall back to the proxy activity; the others already are it. */
    val mayUseProxyFallback: Boolean get() = this == BUBBLE
}

enum class MicrophoneRoute {
    /**
     * Read the microphone in this process at once. The recording's foreground service is
     * started beside it, best effort: the recording does not wait for it or depend on it.
     */
    DIRECT,

    /** Nothing gives this process microphone access - hand over to the invisible activity. */
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
 *
 * Until 0.35 a foreground service of type microphone ran all day, started whenever Embersay's
 * screen was opened, so that a bubble tap counted as a foreground start. Google Play allows a
 * foreground service only for as long as the task the user started, so now:
 *
 * - The bubble records directly in the accessibility service's process. Android binds an
 *   accessibility service with BIND_INCLUDE_CAPABILITIES and BIND_FOREGROUND_SERVICE_WHILE_AWAKE
 *   (AOSP AccessibilityServiceConnection), so from Android 11 the process holds while-in-use
 *   microphone access while the screen is on. Before Android 11 there is no while-in-use rule
 *   for the microphone: the same binding keeps the process from counting as idle, which is
 *   what Android 9 and 10 silence. Either way the bubble records at once.
 * - The foreground service runs only while a recording does (it shows the recording and its
 *   Stop button); Android may refuse to start it from the background, and the recording goes
 *   on without it.
 * - The proxy activity is the last fallback: when the direct start delivers only silence
 *   ([startProbeMs], [MicrophoneWatch]), when Android refuses the recorder, or when Android's
 *   speech recognition cannot open the microphone ([proxyAfterRecognizerFailure]). So whatever
 *   recorded before still records.
 */
object MicrophoneRouter {

    /**
     * How long a direct start from the bubble may deliver only silence before the proxy
     * activity takes over. Long enough for Android to report a silenced recording, which comes
     * a moment after the recorder starts; short enough that nobody has begun to speak - the
     * start is only signalled (buzz, tone) once the microphone delivers sound.
     */
    const val PROBE_MS = 300

    /** Where to get the microphone, and why - the why goes to logcat and Diagnostics. */
    data class Decision(val route: MicrophoneRoute, val why: String)

    fun route(
        source: TriggerSource,
        hasPermission: Boolean,
        /** Build.VERSION.SDK_INT. */
        sdk: Int,
        /** Whether Embersay's accessibility service is connected - the process the bubble lives in. */
        accessibilityConnected: Boolean,
    ): Decision = when {
        !hasPermission -> Decision(MicrophoneRoute.NEEDS_PERMISSION, "the microphone permission is missing")

        source == TriggerSource.IN_APP -> Decision(MicrophoneRoute.DIRECT, "started inside Embersay, which is in front")

        source == TriggerSource.PROXY_ACTIVITY -> Decision(MicrophoneRoute.DIRECT, "the proxy activity is in front")

        // The bubble without its service: nothing anchors this process, only the activity can.
        !accessibilityConnected -> Decision(MicrophoneRoute.VIA_PROXY_ACTIVITY, "bubble, but the accessibility service is not connected")

        sdk >= ANDROID_11 -> Decision(
            MicrophoneRoute.DIRECT,
            "bubble, Android API $sdk: the accessibility service's binding carries while-in-use microphone access",
        )

        else -> Decision(
            MicrophoneRoute.DIRECT,
            "bubble, Android API $sdk: no while-in-use rule for the microphone before Android 11",
        )
    }

    /**
     * How long a direct recording's start is watched for silence before the proxy takes over;
     * 0 for none. Only the bubble has the proxy to fall back to: in front - inside Embersay or
     * through the activity itself - a silent microphone is another app holding it, and that is
     * said as such ([MicrophoneWatch]).
     */
    fun startProbeMs(source: TriggerSource): Int = if (source.mayUseProxyFallback) PROBE_MS else 0

    /**
     * Whether Android's speech recognition, which records in its own process on Embersay's
     * behalf, failed in a way the proxy activity can mend: it never got the microphone open
     * ([opened] false) and said so - no permission, or no audio. Android checks the permission
     * against Embersay's state in the background, so it is the same block the direct recorder
     * meets. Never once the microphone was open or a call ended it: then the words heard count.
     */
    fun proxyAfterRecognizerFailure(source: TriggerSource, opened: Boolean, endedByCall: Boolean, problem: Problem?): Boolean =
        source.mayUseProxyFallback && !opened && !endedByCall &&
            (problem == Problem.MicrophonePermissionMissing || problem == Problem.MicrophoneUnreadable)

    private const val ANDROID_11 = 30
}
