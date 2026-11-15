package app.amethyst.core.text

import app.amethyst.core.engine.Problem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A cleanup that cannot run until the user puts something right: its key refused, no credit
 * left, a model the server does not know. The dictation goes in as heard all the same; this
 * says so - one short toast per cause and session, and a row on top of Settings ([open]) until
 * a cleanup succeeds again. A timeout, a refusal of the content or a cut-off answer stay silent:
 * the next dictation may well be cleaned up.
 *
 * Told by the dictation (its run's thread), read by the Settings page: every call holds the lock.
 */
class CleanupTrouble {

    enum class Cause { KEY_REFUSED, NO_CREDIT, UNKNOWN_MODEL }

    /** What stands in the way, and of whom: [name] is the service's for a key or credit, the model's otherwise. */
    data class Open(val cause: Cause, val name: String)

    private val said = mutableSetOf<Cause>()
    private val _open = MutableStateFlow<Open?>(null)

    /** The cleanup trouble the Settings page points at, or null. */
    val open: StateFlow<Open?> = _open.asStateFlow()

    /**
     * A cleanup by [service] with [model] failed for [problem]. Returns what to say in a toast
     * now - only the first time this session for its cause - or null: nothing for the user to
     * put right, or said already.
     */
    @Synchronized
    fun failed(problem: Problem, service: String, model: String): Open? {
        val cause = causeOf(problem) ?: return null
        val open = Open(cause, if (cause == Cause.UNKNOWN_MODEL) model else service)
        _open.value = open
        return open.takeIf { said.add(cause) }
    }

    /** A cleanup succeeded: nothing stands in the way any more. */
    @Synchronized
    fun succeeded() {
        _open.value = null
    }

    companion object {
        /**
         * What a cleanup's failure needs from the user, or null when it needs nothing: a refused
         * key (401, 403), no credit (402, or the server saying so), a model it does not know.
         */
        fun causeOf(problem: Problem): Cause? = when (problem) {
            Problem.KeyRejected -> Cause.KEY_REFUSED
            is Problem.NoCredit -> Cause.NO_CREDIT
            is Problem.CleanupModelUnknown, is Problem.ModelNotOnServer -> Cause.UNKNOWN_MODEL
            is Problem.CleanupFailed -> when (problem.code) {
                401, 403 -> Cause.KEY_REFUSED
                402 -> Cause.NO_CREDIT
                else -> null
            }
            else -> null
        }
    }
}
