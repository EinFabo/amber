package app.amethyst.core.onboarding

/** What "Allow" for the microphone does ([MicrophoneAsk.way]). */
enum class MicrophoneWay {
    /** Android's own question. */
    ASK,

    /** Amber's page in Android's app info: Android will not ask any more. */
    APP_INFO,
}

/**
 * "Allow" for the microphone, where Android may have stopped asking. Android shows its question
 * again after one refusal and then says a reason would help ([rationale] true); after the
 * second refusal, or "Don't ask again", it refuses at once without a word, and [rationale] is
 * false again - as it is before the very first question. A refusal Amber saw before tells the
 * two apart: then only the app info can grant it, and "Allow" goes there instead of doing
 * nothing.
 */
object MicrophoneAsk {

    fun way(refusedBefore: Boolean, rationale: Boolean): MicrophoneWay =
        if (refusedBefore && !rationale) MicrophoneWay.APP_INFO else MicrophoneWay.ASK

    /** Whether a refusal is remembered after Android's answer: until the microphone is granted. */
    fun refused(granted: Boolean): Boolean = !granted
}
