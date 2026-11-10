package app.amethyst.core.insert

import app.amethyst.core.bubble.TextFieldKind

/** The app window the user sees: the active one, else the topmost app window. */
data class WindowInFront(val packageName: String, val windowId: Int)

/** An editable field seen in [WindowInFront], as the platform layer found it. */
data class SeenField(
    val kind: TextFieldKind,
    /** The very node that had input focus when the dictation started - still there. */
    val isStart: Boolean = false,
    /** Visible on screen and enabled: a field the user could tap. */
    val shown: Boolean = true,
)

/**
 * When [TargetCheck] says NO_FIELD although the app and its window are still the ones the
 * dictation started in, the start field is looked for again rather than the words given up:
 * Android forgets which field has focus more often than the field goes away - the screen went
 * off during a long recording, the app ended input when the keyboard closed.
 *
 * Only the start field's own node counts, found again as it was, on screen and not a password
 * field. Never another field in the same window, not even the only one: in an app that shows
 * every chat in one window (Telegram), the only message box there may be the next chat's, and
 * the words would go to someone else. When in doubt the words stay out and the user is told
 * to use Insert again.
 */
object TargetRefind {

    sealed interface Result {
        data class Found(val index: Int, val how: How) : Result

        data class NotFound(val why: Why) : Result
    }

    enum class How {
        /** The node that had focus at the start. */
        START_FIELD,
    }

    enum class Why {
        /** The dictation started without a field, or its node was not known (Compose). */
        NO_START,

        /** Another app or window is in front now, or the start window is not known. */
        NOT_IN_FRONT,

        /** The start field is a password field now. */
        SECRET,

        /** The start field is gone, or no longer takes words. */
        NO_FIELD,

        /** The start field is still there, but not on screen (another tab, scrolled away). */
        NOT_SHOWN,
    }

    /** Whether looking for fields in [front] is worth it at all: it is the start window. */
    fun sameWindow(start: InputTarget?, front: WindowInFront?): Boolean =
        start != null && front != null && start.windowId >= 0 &&
            start.packageName == front.packageName && start.windowId == front.windowId

    /**
     * Which of [fields] - all seen in [front] - the words go into, if any. [hasStartNode]
     * false: the start field's node was never known, so it cannot be found again.
     */
    fun pick(start: InputTarget?, front: WindowInFront?, fields: List<SeenField>, hasStartNode: Boolean = true): Result {
        if (start == null || !hasStartNode) return Result.NotFound(Why.NO_START)
        if (start.kind == TextFieldKind.SECRET) return Result.NotFound(Why.SECRET)
        if (!sameWindow(start, front)) return Result.NotFound(Why.NOT_IN_FRONT)

        val index = fields.indexOfFirst { it.isStart }
        if (index < 0) return Result.NotFound(Why.NO_FIELD)
        val field = fields[index]
        return when {
            field.kind == TextFieldKind.SECRET -> Result.NotFound(Why.SECRET)
            !field.shown -> Result.NotFound(Why.NOT_SHOWN)
            !takesWords(field.kind) -> Result.NotFound(Why.NO_FIELD)
            else -> Result.Found(index, How.START_FIELD)
        }
    }

    /** Prose fields, and editable nodes that name no input type. Not a number, phone or date field. */
    private fun takesWords(kind: TextFieldKind): Boolean =
        kind == TextFieldKind.TEXT || kind == TextFieldKind.NONE
}
