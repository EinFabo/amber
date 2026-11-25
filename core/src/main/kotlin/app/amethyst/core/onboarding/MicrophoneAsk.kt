package app.amethyst.core.onboarding

/** What "Allow" for the microphone does ([MicrophoneAsk.way]). */
enum class MicrophoneWay {
    /** Android's own question. */
    ASK,

    /** Amber's page in Android's app info: Android will not ask any more. */
    APP_INFO,
}

/** What Android's answer to its microphone question means for this tap and the next "Allow" ([MicrophoneAsk.answer]). */
enum class MicrophoneAnswer(
    /** Remembered until the microphone is granted: the next "Allow" goes to the app info. */
    val blocked: Boolean,
    /** The app info opens in this same tap, so the tap that got no question is not a dead one. */
    val opensAppInfo: Boolean,
) {
    /** Granted, or refused or dismissed with Android still asking: the next "Allow" asks again. */
    ASKS_AGAIN(blocked = false, opensAppInfo = false),

    /**
     * Refused on screen, and Android stopped asking with it ("Don't ask again", or Android 11's
     * second refusal): the user just said no, so nothing opens now - the next "Allow" goes to
     * the app info.
     */
    STOPPED_ASKING(blocked = true, opensAppInfo = false),

    /** Refused without a question on screen: Android asks no more, and the app info opens at once. */
    NO_QUESTION(blocked = true, opensAppInfo = true),
}

/**
 * "Allow" for the microphone, where Android may have stopped asking. The app always tries
 * Android's question first. A refusal or a dismissal with the question on screen (tapping
 * outside it, "Don't allow") is no reason to stop: Android asks again. Two signs say it will
 * not: Android had a reason to give before the question and has none after a refusal - it just
 * stopped asking ([MicrophoneAnswer.STOPPED_ASKING]); or it answered so quickly that no
 * question was shown ([MicrophoneAnswer.NO_QUESTION]). The first does not depend on time, so a
 * slow phone whose answer takes longer than [DIALOG_MS] still gets to the app info, where the
 * microphone can still be granted.
 */
object MicrophoneAsk {

    /** Android's question needs longer than this to be read and answered; a quicker answer had no dialog. */
    const val DIALOG_MS = 300L

    fun way(blocked: Boolean, rationale: Boolean): MicrophoneWay =
        if (blocked && !rationale) MicrophoneWay.APP_INFO else MicrophoneWay.ASK

    /**
     * Android's answer read: [granted], whether Android had a reason to give before the question
     * ([rationaleBefore], null when it is not known: no claim) and after it ([rationaleAfter]),
     * and [elapsedMs] since the request (null when the time of the request is unknown: no claim).
     * A refusal with a reason still to give is never a block: Android asks again.
     */
    fun answer(granted: Boolean, rationaleBefore: Boolean?, rationaleAfter: Boolean, elapsedMs: Long?): MicrophoneAnswer = when {
        granted || rationaleAfter -> MicrophoneAnswer.ASKS_AGAIN
        rationaleBefore == true -> MicrophoneAnswer.STOPPED_ASKING
        elapsedMs != null && elapsedMs < DIALOG_MS -> MicrophoneAnswer.NO_QUESTION
        else -> MicrophoneAnswer.ASKS_AGAIN
    }
}
