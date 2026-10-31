package app.amethyst.core.commands

import app.amethyst.core.bubble.TextFieldKind
import app.amethyst.core.insert.InputTarget
import app.amethyst.core.insert.TargetCheck

/**
 * Whether a text command may change the field that has the cursor now. The rule a dictation's
 * text follows ([TargetCheck]): recognition takes seconds, meanwhile the user may have moved to
 * another chat, so only the field the command was started in - and never a password field.
 * Stricter in one point: a command started where no field had the cursor has no field to
 * change, where a dictation would take the field focused at the end.
 */
object EditTarget {

    /** Null when the edit may go ahead; else, in words for the user, why nothing was changed. */
    fun refusal(start: InputTarget?, now: InputTarget?): String? = when {
        now == null -> if (start == null) "No text field has the cursor - tap into one first." else "No text field has the cursor any more - nothing was changed."
        now.kind == TextFieldKind.SECRET -> "Not in a password field."
        start == null -> "Text commands work in the field you start them in - tap into it, then double-tap the bubble."
        else -> when (TargetCheck.verdict(start, now)) {
            TargetCheck.Verdict.INSERT -> null
            TargetCheck.Verdict.SECRET -> "Not in a password field."
            TargetCheck.Verdict.NO_FIELD -> "No text field has the cursor any more - nothing was changed."
            TargetCheck.Verdict.MOVED -> "You switched to another app, chat or field meanwhile - nothing was changed."
        }
    }

    /** What the check found, in a few words for Diagnostics - the same order as [refusal]. */
    fun verdict(start: InputTarget?, now: InputTarget?): String = when {
        now == null -> if (start == null) "no field, neither at the start nor now" else "no field has the cursor now"
        now.kind == TextFieldKind.SECRET -> "password field"
        start == null -> "no field had the cursor at the start"
        else -> when (TargetCheck.verdict(start, now)) {
            TargetCheck.Verdict.INSERT -> "same field"
            TargetCheck.Verdict.SECRET -> "password field"
            TargetCheck.Verdict.NO_FIELD -> "no field has the cursor now"
            TargetCheck.Verdict.MOVED -> "moved to another app, window or field"
        }
    }
}
