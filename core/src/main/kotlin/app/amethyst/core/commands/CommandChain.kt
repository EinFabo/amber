package app.amethyst.core.commands

/**
 * Several commands said in one go - "alles markieren und kopieren", "zweimal zurück und dann
 * öffne WhatsApp" - run one after another, each through the executor as if said alone (its gate,
 * its field check). Between two steps a pause so the first one lands - longer after going to
 * another screen or opening an app, so it has settled. The first step that is not done stops the
 * rest: nothing runs on a screen the user did not expect. A tap stops the chain like any command
 * (the pause and every step are cancellable).
 *
 * One command runs exactly as alone: no heading, no pause, its own outcome and outcome line.
 */
object CommandChain {

    /** Between two steps: as between the steps of a repeated command. */
    const val PAUSE_MS = Repetition.PAUSE_MS

    /** After back, home, recent apps, the notifications: the screen's animation is over. */
    const val NAVIGATION_SETTLE_MS = 600L

    /** After an app or a settings page came to the front: it has drawn its first screen. */
    const val OPEN_SETTLE_MS = 1_000L

    /** How long the screen gets after [command] before the next step. */
    fun pauseAfter(command: Command): Long = when (command) {
        is Command.OpenApp, is Command.OpenSettings -> OPEN_SETTLE_MS
        is Command.Navigate -> NAVIGATION_SETTLE_MS
        else -> PAUSE_MS
    }

    /** How a chain went. */
    class Run(
        /** In words for the user: what each step did, or which one was not done and why. */
        val outcome: CommandOutcome,
        val steps: Int,
        /** The step (from 1) that was not done, or null when every one was. */
        val stoppedAt: Int?,
        /** The Diagnostics line that ends the trace: "outcome: failed at step 2/3 (Edit COPY)". */
        val line: String,
    )

    /**
     * Runs [commands] in order with [execute], [pause] between two of them, each step's lines in
     * [trace] under a heading ("step 2/3: Edit COPY").
     */
    suspend fun run(
        commands: List<Command>,
        trace: CommandTrace,
        pause: suspend (Long) -> Unit,
        execute: suspend (Command) -> CommandOutcome,
    ): Run {
        require(commands.isNotEmpty()) { "a chain has at least one command" }
        val n = commands.size
        if (n == 1) {
            val outcome = execute(commands.single())
            return Run(outcome, 1, stoppedAt = if (outcome is CommandOutcome.Done) null else 1, line = "outcome: ${word(outcome)}")
        }
        val done = mutableListOf<String>()
        for ((i, command) in commands.withIndex()) {
            if (i > 0) pause(pauseAfter(commands[i - 1]))
            trace.note("step ${i + 1}/$n: ${VoiceCommands.describe(command)}")
            val outcome = execute(command)
            if (outcome is CommandOutcome.Done) {
                done += outcome.message
                continue
            }
            val step = "step ${i + 1} of $n ${word(outcome)}: ${outcome.message}"
            val message = if (done.isEmpty()) step.replaceFirstChar(Char::uppercase) else "${joined(done)}, then $step"
            val stopped = when (outcome) {
                is CommandOutcome.Refused -> CommandOutcome.Refused(message)
                else -> CommandOutcome.Failed(message)
            }
            return Run(stopped, n, stoppedAt = i + 1, line = "outcome: ${word(outcome)} at step ${i + 1}/$n (${VoiceCommands.describe(command)})")
        }
        return Run(CommandOutcome.Done(joined(done)), n, stoppedAt = null, line = "outcome: done, all $n steps")
    }

    private fun word(outcome: CommandOutcome): String = when (outcome) {
        is CommandOutcome.Done -> "done"
        is CommandOutcome.Refused -> "refused"
        is CommandOutcome.Failed -> "failed"
    }

    /** "Selected all, copied": the first as it is, the others with a small first letter unless they start with a name. */
    private fun joined(messages: List<String>): String = messages.mapIndexed { i, message ->
        val sentenceCase = message.length > 1 && message[0].isUpperCase() && message[1].isLowerCase()
        if (i > 0 && sentenceCase) message.replaceFirstChar(Char::lowercase) else message
    }.joinToString(", ")
}
