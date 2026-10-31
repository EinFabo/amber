package app.amethyst.core.commands

/** What one step of a repeated command did. */
sealed interface Step {
    data object Done : Step

    /** It ran, but nothing changed: the end of the list, the volume at its limit. */
    data object Unchanged : Step

    data class Failed(val message: String) : Step
}

/** How a repeated command went: [done] of [asked] steps, and the step that ended it early. */
data class RepeatRun(val asked: Int, val done: Int, val stop: Step?) {

    /**
     * In words for the user. [did] says what one step does ("Scrolled down"), [limit] what a step
     * that changed nothing means ("nothing more to scroll").
     */
    fun outcome(did: String, limit: String): CommandOutcome = when (stop) {
        // A run ends early only on a step that was not done; null means every step ran.
        null, Step.Done -> CommandOutcome.Done(if (asked == 1) did else "$did $asked times")
        Step.Unchanged ->
            if (done == 0) CommandOutcome.Failed(limit.replaceFirstChar(Char::uppercase))
            else CommandOutcome.Done("$did ${times(done)} - $limit")
        is Step.Failed ->
            if (done == 0) CommandOutcome.Failed(stop.message)
            else CommandOutcome.Failed("$did $done of $asked times, then: ${stop.message}")
    }

    private fun times(n: Int) = if (n == 1) "once" else "$n times"
}

/**
 * Runs a command several times - "scroll 10 mal runter", "zweimal zurück" - with a short pause
 * between the steps, so each one lands before the next, and stops at the first step that fails
 * or changes nothing.
 */
object Repetition {

    /** Between two steps: long enough for a list to move and a screen to change. */
    const val PAUSE_MS = 250L

    /** No run is longer, whatever is asked - the cap of "to the end" ([Repeats.MAX_TO_END]). */
    const val MAX_TO_END = Repeats.MAX_TO_END

    suspend fun run(times: Int, pause: suspend () -> Unit, step: suspend () -> Step): RepeatRun {
        val asked = times.coerceIn(1, MAX_TO_END)
        var done = 0
        for (i in 0 until asked) {
            if (i > 0) pause()
            when (val result = step()) {
                Step.Done -> done++
                else -> return RepeatRun(asked, done, result)
            }
        }
        return RepeatRun(asked, done, null)
    }
}
