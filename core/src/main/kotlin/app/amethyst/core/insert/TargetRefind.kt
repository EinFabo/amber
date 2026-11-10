package app.amethyst.core.insert

import app.amethyst.core.bubble.TextFieldKind

/** The app window the user sees: the active one, else the topmost app window. */
data class WindowInFront(val packageName: String, val windowId: Int)

/** An editable field seen in [WindowInFront], as the platform layer found it. */
data class SeenField(
    val kind: TextFieldKind,
    /** The very node that had input focus when the dictation started - still there. */
    val isStart: Boolean = false,
    /** It says it has focus, although Android's focus lookup did not return it (Compose). */
    val focused: Boolean = false,
    /** Visible on screen and enabled: a field the user could tap. */
    val shown: Boolean = true,
)

/**
 * When [TargetCheck] says NO_FIELD although the app and its window are still the ones the
 * dictation started in, the field is looked for again rather than the words given up: Android
 * forgets which field has focus more often than the field goes away - the screen went off
 * during a long recording, the app rebuilt its field (WhatsApp), a Compose app ended input
 * when the keyboard closed.
 *
 * Only in the start window, never a password field, and only where there is no doubt: the
 * start field itself, else the one field that says it is focused, else the only field there.
 * Two plausible fields and the words stay out - the wrong one could be a message to someone.
 */
object TargetRefind {

    sealed interface Result {
        data class Found(val index: Int, val how: How) : Result

        data class NotFound(val why: Why) : Result
    }

    enum class How {
        /** The node that had focus at the start. */
        START_FIELD,

        /** The one field in the window that still says it is focused. */
        FOCUSED,

        /** The only field in the window that takes text. */
        ONLY_FIELD,
    }

    enum class Why {
        /** The dictation started without a field: there is no field the user meant. */
        NO_START,

        /** Another app or window is in front now, or the start window is not known. */
        NOT_IN_FRONT,

        /** The field to take would be a password field. */
        SECRET,

        /** No field there that takes text. */
        NO_FIELD,

        /** More than one field could be meant. */
        SEVERAL,
    }

    /** Whether looking for fields in [front] is worth it at all: it is the start window. */
    fun sameWindow(start: InputTarget?, front: WindowInFront?): Boolean =
        start != null && front != null && start.windowId >= 0 &&
            start.packageName == front.packageName && start.windowId == front.windowId

    /** Which of [fields] - all seen in [front] - the words go into, if any. */
    fun pick(start: InputTarget?, front: WindowInFront?, fields: List<SeenField>): Result {
        if (start == null) return Result.NotFound(Why.NO_START)
        if (start.kind == TextFieldKind.SECRET) return Result.NotFound(Why.SECRET)
        if (!sameWindow(start, front)) return Result.NotFound(Why.NOT_IN_FRONT)

        val shown = fields.withIndex().filter { it.value.shown }

        // The start field, or the field that has focus, turned into a password field: the
        // user is typing a password now, and nothing else in the window is the target.
        if (shown.any { (it.value.isStart || it.value.focused) && it.value.kind == TextFieldKind.SECRET }) {
            return Result.NotFound(Why.SECRET)
        }

        shown.firstOrNull { it.value.isStart }?.let { startField ->
            return if (takesWords(startField.value.kind)) {
                Result.Found(startField.index, How.START_FIELD)
            } else {
                Result.NotFound(Why.NO_FIELD)
            }
        }

        val focused = shown.filter { it.value.focused }
        when {
            focused.size > 1 -> return Result.NotFound(Why.SEVERAL)
            focused.size == 1 -> return if (takesWords(focused[0].value.kind)) {
                Result.Found(focused[0].index, How.FOCUSED)
            } else {
                Result.NotFound(Why.NO_FIELD)
            }
        }

        val candidates = shown.filter { takesWords(it.value.kind) }
        return when (candidates.size) {
            0 -> Result.NotFound(Why.NO_FIELD)
            1 -> Result.Found(candidates[0].index, How.ONLY_FIELD)
            else -> Result.NotFound(Why.SEVERAL)
        }
    }

    /**
     * Prose fields, and editable nodes that name no input type (Compose's fields do not).
     * Not a number, phone or date field: a dictation does not belong there.
     */
    private fun takesWords(kind: TextFieldKind): Boolean =
        kind == TextFieldKind.TEXT || kind == TextFieldKind.NONE
}
