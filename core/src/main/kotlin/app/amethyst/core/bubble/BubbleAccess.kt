package app.amethyst.core.bubble

/**
 * What a screen reader such as TalkBack gets of the bubble: the state it names after the
 * bubble's label, what its double tap does, the entries of its actions menu, and which
 * changes are said aloud. Pure, so every state is a test; the words themselves are string
 * resources in :app, in English and German.
 */
object BubbleAccess {

    /** Where a run stands, as far as the bubble shows it. */
    enum class Phase { READY, LISTENING, WORKING, DONE, FAILED }

    /** The state named after the label: "Amber dictation, Listening". */
    enum class Status { READY, LISTENING, LISTENING_FOR_COMMAND, PROCESSING, DONE, FAILED }

    enum class Action {
        START_DICTATION,
        START_COMMAND,
        STOP_DICTATION,

        /** Ends a command's recording; the command is then carried out. */
        STOP_LISTENING,

        /** Stops a command being carried out, as a tap does. */
        CANCEL_COMMAND,

        /** What a double tap does: a recording that is a command, not a dictation. */
        VOICE_COMMAND,

        /** What dropping the bubble on the hide target does. */
        HIDE_HERE,
    }

    data class Node(
        val status: Status,
        /** Why it failed, short and without quoted words; null otherwise. */
        val reason: String?,
        /** What a tap - TalkBack's double tap - does here; null when it does nothing to name. */
        val click: Action?,
        /** TalkBack's actions menu, the tap's own action first. */
        val actions: List<Action>,
    )

    /**
     * @param command the run is a voice command (a double tap, or a tap with no text field)
     * @param failure the failure's message while [phase] is FAILED
     * @param textFieldFocused a field takes dictation: without one a tap starts a command
     * @param canHide another app than Amber is in front, so it can go on the hidden list
     */
    fun node(
        phase: Phase,
        command: Boolean,
        failure: String?,
        commandsOn: Boolean,
        textFieldFocused: Boolean,
        canHide: Boolean,
    ): Node {
        val status = when (phase) {
            Phase.READY -> Status.READY
            Phase.LISTENING -> if (command) Status.LISTENING_FOR_COMMAND else Status.LISTENING
            Phase.WORKING -> Status.PROCESSING
            Phase.DONE -> Status.DONE
            Phase.FAILED -> Status.FAILED
        }
        // The same split the tap makes (CommandTrigger.modeAtStart, tapInRun).
        val startsCommand = commandsOn && !textFieldFocused
        val click = when (phase) {
            Phase.READY, Phase.DONE, Phase.FAILED -> if (startsCommand) Action.START_COMMAND else Action.START_DICTATION
            Phase.LISTENING -> if (command) Action.STOP_LISTENING else Action.STOP_DICTATION
            // A dictation past its recording is being written: a tap leaves it alone.
            Phase.WORKING -> if (command) Action.CANCEL_COMMAND else null
        }
        val voiceCommand = commandsOn && when (phase) {
            Phase.READY, Phase.DONE, Phase.FAILED -> !startsCommand
            // The double tap's second half: this recording becomes a command.
            Phase.LISTENING -> !command
            Phase.WORKING -> false
        }
        val actions = listOfNotNull(
            click,
            Action.VOICE_COMMAND.takeIf { voiceCommand },
            // As the drag to the hide target: only at rest, never during a run.
            Action.HIDE_HERE.takeIf { canHide && phase == Phase.READY },
        )
        val reason = if (phase == Phase.FAILED) failure?.let(::shortReason) else null
        return Node(status, reason, click, actions)
    }

    /**
     * Whether the change from [from] to [to] is said aloud: the start, the result and a
     * failure. Nothing while the text is being written, and never the way back to rest - the
     * bubble would talk after every dictation.
     */
    fun announces(from: Phase, to: Phase): Boolean =
        from != to && (to == Phase.LISTENING || to == Phase.DONE || to == Phase.FAILED)

    /**
     * The first plain part of a failure message: up to the first sentence end, dash, colon,
     * trace or quote. Messages may quote the dictated or heard words, and the bubble sits over
     * whatever app is open - so nothing from a quote on is ever kept. Null when nothing is left.
     */
    fun shortReason(message: String): String? {
        var end = message.length
        QUOTES.forEach { quote -> message.indexOf(quote).takeIf { it >= 0 }?.let { end = minOf(end, it) } }
        CUTS.forEach { cut -> message.indexOf(cut).takeIf { it >= 0 }?.let { end = minOf(end, it) } }
        // A question or an exclamation keeps its mark; a full stop is dropped like the rest.
        QUESTION_ENDS.forEach { mark -> message.indexOf(mark).takeIf { it >= 0 }?.let { end = minOf(end, it + 1) } }
        val reason = message.substring(0, end).trim().trimEnd('.', ',', ';', ':', ' ')
        if (reason.isEmpty()) return null
        if (reason.length <= MAX_REASON) return reason
        val space = reason.lastIndexOf(' ', MAX_REASON - 1)
        return reason.substring(0, if (space > 0) space else MAX_REASON - 1).trimEnd('.', ',', ';', ':', ' ') + "…"
    }

    /** Long enough for "The field now is a password field", short enough to say in passing. */
    const val MAX_REASON = 80

    private val QUOTES = listOf('"', '“', '„', '‚', '‘', '«', '»')
    private val CUTS = listOf(". ", " - ", " – ", " — ", ": ", " [", "\n")
    private val QUESTION_ENDS = listOf("? ", "! ")
}
