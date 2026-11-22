package app.amethyst.core.capture

/**
 * Whether a call began while a dictation records - ringing, a phone call, a call in an app -
 * from repeated looks at the phone. A call that was already on at the first look is the user's
 * choice and changes nothing; one that begins later ends the recording, also the next call
 * after the first one hung up. Shared by Embersay's own recorder ([MicrophoneWatch]) and
 * Android's speech recognition, which records by itself.
 */
class CallWatch {

    /** What the last look saw; null before the first. */
    private var before: Boolean? = null

    /** After each look: true when [inCall] turned on since the last one - once per call. */
    fun began(inCall: Boolean): Boolean {
        val was = before ?: inCall
        before = inCall
        return inCall && !was
    }

    companion object {
        /** How often a running recording looks: the audio mode is a call into the system. */
        const val CHECK_MS = 250L
    }
}
