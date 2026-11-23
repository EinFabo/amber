package app.amethyst.core.text

import app.amethyst.core.engine.Problem
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppCleanupMode
import app.amethyst.core.settings.CleanupChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A cleanup that cannot run until the user puts something right: its key refused, no credit
 * left, a model the server does not know. The dictation goes in as heard all the same; this
 * says so - one short toast per cause and session, and a row on top of Settings ([open]) until
 * a cleanup succeeds again, the user changes what it is about - the cleanup off, another
 * service or model ([settingsChanged]) - or saves that service's key again ([keySaved]). A
 * timeout, a refusal of the content or a cut-off answer stay silent: the next dictation may
 * well be cleaned up.
 *
 * Told by the dictation (its run's thread), read by the Settings page: every call holds the lock.
 */
class CleanupTrouble {

    enum class Cause { KEY_REFUSED, NO_CREDIT, UNKNOWN_MODEL }

    /**
     * What stands in the way, and of whom: [name] is the service's for a key or credit, the
     * model's otherwise; [choice] is the cleanup that failed.
     */
    data class Open(val cause: Cause, val name: String, val choice: CleanupChoice)

    private val said = mutableSetOf<Cause>()
    private val _open = MutableStateFlow<Open?>(null)

    /** The cleanup trouble the Settings page points at, or null. */
    val open: StateFlow<Open?> = _open.asStateFlow()

    /**
     * The cleanup [choice] of [service] failed for [problem]. Returns what to say in a toast
     * now - only the first time this session for its cause - or null: nothing for the user to
     * put right, or said already.
     */
    @Synchronized
    fun failed(problem: Problem, service: String, choice: CleanupChoice): Open? {
        val cause = causeOf(problem) ?: return null
        val open = Open(cause, if (cause == Cause.UNKNOWN_MODEL) choice.model else service, choice)
        _open.value = open
        return open.takeIf { said.add(cause) }
    }

    /** A cleanup succeeded: nothing stands in the way any more. */
    @Synchronized
    fun succeeded() {
        _open.value = null
    }

    /**
     * The settings are now [settings]: the trouble goes when no cleanup uses the failed one any
     * more - the cleanup is off, or another service or model took its place - and may be said
     * again should the new one fail too.
     */
    @Synchronized
    fun settingsChanged(settings: AmethystSettings) {
        val open = _open.value ?: return
        if (!stillApplies(open, settings)) forget()
    }

    /** A key was saved for the services [engineIds]: a trouble with one of them may be put right. */
    @Synchronized
    fun keySaved(engineIds: Collection<String>) {
        val open = _open.value ?: return
        if (open.choice.engineId in engineIds) forget()
    }

    private fun forget() {
        _open.value = null
        said.clear()
    }

    companion object {
        /**
         * Whether [open] is still about a cleanup in [settings]: the one for every app, or an
         * app's own model. Settings shows the row only then.
         */
        fun stillApplies(open: Open, settings: AmethystSettings): Boolean =
            settings.engines.any { it.id == open.choice.engineId } && (
                settings.cleanup == open.choice ||
                    settings.profiles.any { it.appCleanup.mode == AppCleanupMode.MODEL && it.appCleanup.choice == open.choice }
                )

        /**
         * What a cleanup's failure needs from the user, or null when it needs nothing: a refused
         * key (401, 403), no credit (402, or the server saying so), a model it does not know.
         */
        fun causeOf(problem: Problem): Cause? = when (problem) {
            is Problem.KeyRejected -> Cause.KEY_REFUSED
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
