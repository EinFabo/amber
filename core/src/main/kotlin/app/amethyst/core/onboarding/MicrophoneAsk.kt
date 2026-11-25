package app.amethyst.core.onboarding

/** What "Allow" for the microphone does ([MicrophoneAsk.way]). */
enum class MicrophoneWay {
    /** Android's own question. */
    ASK,

    /** Amber's page in Android's app info: Android will not ask any more. */
    APP_INFO,
}

/**
 * "Allow" for the microphone, where Android may have stopped asking. The app always tries
 * Android's question first. A refusal or a dismissal with the question on screen (tapping
 * outside it, "Don't allow") is no reason to stop: Android asks again, and after its own
 * "Don't ask again" it answers at once without a dialog. That answer with no question shown
 * ([blocked]) is the one that sends "Allow" to the app info, where the microphone can still
 * be granted.
 */
object MicrophoneAsk {

    /** Android's question needs longer than this to be read and answered; a quicker answer had no dialog. */
    const val DIALOG_MS = 300L

    fun way(blocked: Boolean, rationale: Boolean): MicrophoneWay =
        if (blocked && !rationale) MicrophoneWay.APP_INFO else MicrophoneWay.ASK

    /**
     * Whether Android will not ask any more, after its answer: the microphone was refused, with
     * no reason to give ([rationale] false) and in less than [DIALOG_MS] ([elapsedMs], null when
     * the time of the request is unknown: no claim) since the request - no dialog was shown.
     * Remembered until the microphone is granted.
     */
    fun blocked(granted: Boolean, rationale: Boolean, elapsedMs: Long?): Boolean =
        !granted && !rationale && elapsedMs != null && elapsedMs < DIALOG_MS
}
