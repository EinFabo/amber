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

    /**
     * Whether a dictation may start for [start], the field in focus at the tap: never for a
     * password or PIN field - its words would go to the speech recognition, a server's too,
     * before [verdict] refused them at the end. That includes a field the tap's own search could
     * not settle in time, which is taken for a secret one. Null - no field, with the bubble
     * shown everywhere - and any other kind may start; where the words go is decided at the end.
     */
    fun mayStart(start: InputTarget?): Boolean = start?.kind != TextFieldKind.SECRET

    /**
     * [appAtStart]: the app in front when the dictation started, for one started without a
     * field (bubble shown everywhere). The field focused at the end is then the one the user
     * chose - in that app. In another one it may be a field that took focus by itself, an
     * overlay's, and the words stay out.
     */
    fun verdict(start: InputTarget?, now: InputTarget?, appAtStart: String? = null): Verdict {
        if (now == null) return Verdict.NO_FIELD
        if (now.kind == TextFieldKind.SECRET) return Verdict.SECRET
        val expected = expectedPackage(start, appAtStart) ?: return Verdict.INSERT
        if (now.packageName != expected) return Verdict.MOVED
        return Verdict.INSERT
    }

    /**
     * The one app a dictation's words may go into: the start field's, else - started without
     * a field - the app that was in front then. Null when neither is known: any app's field,
     * there is nothing to hold it against.
     */
    fun expectedPackage(start: InputTarget?, appAtStart: String?): String? = start?.packageName ?: appAtStart

    /**
     * The last word, on the field that is about to be written. [verdict] is given for the
     * field Android names; the write then looks its field up again, by more ways than the
     * check has (the input connection, a search of the windows), and what it finds may be
     * another one: focus moved in between, or the check could not see what the field is.
     * So what [verdict] asked of the one is asked again of the other: not a password field
     * ([secret]), and [expectedPackage]'s ([expectedPackage] null: any app's). Which field of
     * that app, and in which window, is as free here as there. A field that fails gets
     * nothing, by no method, and nothing goes to the clipboard - a field that took focus by
     * itself could read it there; the words stay with the dictation, for Insert again.
     */
    fun atWrite(expectedPackage: String?, targetPackage: String, secret: Boolean): Verdict = when {
        secret -> Verdict.SECRET
        expectedPackage != null && targetPackage != expectedPackage -> Verdict.MOVED
        else -> Verdict.INSERT
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
