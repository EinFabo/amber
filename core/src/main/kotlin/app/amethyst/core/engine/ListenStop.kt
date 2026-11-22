package app.amethyst.core.engine

/**
 * The stop of a recognizer that records by itself ([SelfCapturingSttEngine]), from the tap to
 * its answer. Android's recognizer has no time limit of its own: asked to stop, it may answer
 * with neither a result nor an error, and the dictation would hang until the run's watchdog.
 * So after [ANSWER_WITHIN_MS] without an answer the listening is settled with what was heard
 * so far ([heard]), or as nothing heard.
 *
 * It also keeps a stop that came before the recognizer listened - it would drop it - to be
 * passed on the moment it does ([listening]). One per listening; every call holds the lock,
 * since the tap, the recognizer's callbacks and a cancellation come from different threads.
 */
class ListenStop {

    private var isListening = false
    private var asked = false
    private var settled = false
    private var partial: String? = null

    /** The recognizer listens now. True when a stop was asked for before: it is due at once. */
    @Synchronized
    fun listening(): Boolean {
        isListening = true
        return asked && !settled
    }

    /**
     * A stop was asked for. True when it is to be passed on to the recognizer now and its
     * answer awaited; false when it is kept until the recognizer listens, was passed on
     * before - a second stop makes some recognizers fail - or comes after the answer.
     */
    @Synchronized
    fun stop(): Boolean {
        val first = !asked
        asked = true
        return first && isListening && !settled
    }

    /** An interim text; the latest one stands for the whole of what was heard so far. */
    @Synchronized
    fun partial(text: String?) {
        if (!text.isNullOrBlank()) partial = text
    }

    /**
     * Whoever comes first - the result, the error, the wait running out, a cancellation -
     * settles the listening: true once, false for everyone after.
     */
    @Synchronized
    fun settle(): Boolean {
        if (settled) return false
        settled = true
        return true
    }

    /** What stands in for a result that never came: the last interim text, null when none came. */
    val heard: String?
        @Synchronized get() = partial?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * What stands in for an empty result or an error once a stop was asked for: [heard], or
     * null when no stop was asked for (the recognizer ended by itself - its answer stands).
     * Stopped - by a tap or a call, whose audio mode can silence the recognizer - it may answer
     * "no match" or nothing for words the bubble already showed; those words are what was said.
     */
    val heardOnStop: String?
        @Synchronized get() = if (asked) heard else null

    companion object {
        /** How long a recognizer asked to stop gets for its answer. */
        const val ANSWER_WITHIN_MS = 5_000L
    }
}
