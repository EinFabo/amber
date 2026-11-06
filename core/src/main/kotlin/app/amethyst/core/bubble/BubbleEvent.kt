package app.amethyst.core.bubble

/**
 * Something the bubble did that the introduction's practice step watches for. The service and
 * the screens run in one process; the app publishes these on one stream, and nothing but the
 * practice step listens.
 */
sealed interface BubbleEvent {

    /** A dictation went into a field (the bubble's Done). */
    data object DictationInserted : BubbleEvent

    /** A quick menu action ran. */
    data class QuickActionRun(val action: QuickAction) : BubbleEvent

    /** The bubble was let go and docked on [side]. */
    data class Docked(val side: BubbleSide) : BubbleEvent
}
