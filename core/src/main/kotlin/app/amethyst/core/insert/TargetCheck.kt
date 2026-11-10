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
 * ten seconds and more; meanwhile the user may have moved to another app or into a password
 * field: there the words do not belong. Within the same app the field with the cursor now gets
 * them, whichever window it is in: leaving a chat and coming back makes a new window (WhatsApp
 * builds a new Conversation activity), and another chat looks the same to Android - window and
 * field ids cannot tell them apart, so they only refused the chat the words were meant for.
 * Where they went wrong, Undo last takes them out again; nothing is ever sent.
 */
object TargetCheck {

    enum class Verdict {
        INSERT,

        /** Another app than at the start ([sameField]: another window or field too). */
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
        if (now.packageName != start.packageName) return Verdict.MOVED
        return Verdict.INSERT
    }

    /**
     * The same, but the field itself must be the one from the start: same window, same field id
     * where both are known. For a voice command that changes a field ("delete that", "paste"),
     * which must never act on another chat's draft - unlike a dictation, it has no Undo last.
     */
    fun sameField(start: InputTarget?, now: InputTarget?): Verdict {
        val verdict = verdict(start, now)
        if (verdict != Verdict.INSERT || start == null || now == null) return verdict
        if (now.windowId != start.windowId) return Verdict.MOVED
        if (start.fieldId != null && now.fieldId != null && start.fieldId != now.fieldId) return Verdict.MOVED
        return Verdict.INSERT
    }
}
