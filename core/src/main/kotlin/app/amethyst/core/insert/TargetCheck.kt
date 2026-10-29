package app.amethyst.core.insert

import app.amethyst.core.bubble.TextFieldKind

/**
 * The field a dictation was meant for: the app, its window, and - from Android 13, where the
 * input method sees it - the field's own id. Taken when recording starts.
 */
data class InputTarget(
    val packageName: String,
    /** AccessibilityWindowInfo id: a new screen (another chat, another activity) is a new window. */
    val windowId: Int,
    /** EditorInfo.fieldId, or null where it is not known (below Android 13, or no input started). */
    val fieldId: Int? = null,
    val kind: TextFieldKind = TextFieldKind.TEXT,
)

/**
 * Whether the text may go into the field that has focus now. Recognition and cleanup can take
 * ten seconds and more; meanwhile the user may have moved to another app, another chat in the
 * same app, or the password field below. In any of those the words do not belong there.
 */
object TargetCheck {

    enum class Verdict {
        INSERT,

        /** Another app, another window or another field than at the start. */
        MOVED,

        /** No field has focus now, or it cannot be identified - nothing to check against. */
        NO_FIELD,

        /** A password or PIN field: dictated text never goes there. */
        SECRET,
    }

    fun verdict(start: InputTarget?, now: InputTarget?): Verdict {
        if (now == null) return Verdict.NO_FIELD
        if (now.kind == TextFieldKind.SECRET) return Verdict.SECRET
        // Started without a field (bubble shown everywhere): the field focused at the end is
        // the one the user chose.
        if (start == null) return Verdict.INSERT
        if (now.packageName != start.packageName || now.windowId != start.windowId) return Verdict.MOVED
        if (start.fieldId != null && now.fieldId != null && start.fieldId != now.fieldId) return Verdict.MOVED
        return Verdict.INSERT
    }
}
