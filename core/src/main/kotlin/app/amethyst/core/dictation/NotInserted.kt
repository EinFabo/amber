package app.amethyst.core.dictation

import app.amethyst.core.insert.TargetCheck

/**
 * A dictation's words were recognized and did not go into the field: why, and which way out
 * the toast names. The words stay kept either way (Insert again, Copy last).
 *
 * "Insert again" helps where the field was gone or the focus had moved: back in the field, it
 * goes in. Where the field itself refused the text - it accepted none of the ways in, or the
 * words were typed and never showed - Insert again would fail the same way in the same field;
 * there the way out is Copy last, and pasting by hand.
 */
object NotInserted {

    enum class Why {
        /** The field is a password field: dictated text never goes there. */
        PASSWORD,

        /** No field had focus any more when the text was ready. */
        NO_FIELD,

        /** The focus moved to another app's field meanwhile. */
        SWITCHED,

        /** The screen was off or locked when the text was ready. */
        SCREEN_OFF,

        /** The field took none of the ways in. */
        REFUSED,

        /** Typed through the input connection, never seen in the field - it may still arrive. */
        UNCERTAIN,
    }

    enum class Hint {
        /** Tap into the field, hold the bubble, Insert again. */
        INSERT_AGAIN,

        /** Hold the bubble, Copy last - then paste it by hand. */
        COPY,

        /** The text is on the clipboard already: paste it by hand. */
        ON_CLIPBOARD,

        /** Nothing to do: a password field gets no dictation. */
        NONE,
    }

    /** The way out for words that did not go in for [why]; [onClipboard]: the clipboard fallback took them. */
    fun hint(why: Why, onClipboard: Boolean): Hint = when {
        why == Why.PASSWORD -> Hint.NONE
        onClipboard -> Hint.ON_CLIPBOARD
        why == Why.REFUSED || why == Why.UNCERTAIN -> Hint.COPY
        else -> Hint.INSERT_AGAIN
    }

    /** Why the check before writing ([TargetCheck.verdict]) kept the words out; [verdict] is never INSERT. */
    fun beforeWrite(verdict: TargetCheck.Verdict): Why = when (verdict) {
        TargetCheck.Verdict.SECRET -> Why.PASSWORD
        TargetCheck.Verdict.NO_FIELD -> Why.NO_FIELD
        TargetCheck.Verdict.MOVED, TargetCheck.Verdict.INSERT -> Why.SWITCHED
    }

    /**
     * Why a write that was tried put nothing in the field: [refused] by the field check at the
     * write ([TargetCheck.atWrite]) - the focus had moved - or, written, [uncertain] or refused
     * by the field itself.
     */
    fun afterWrite(refused: TargetCheck.Verdict?, uncertain: Boolean): Why = when {
        refused != null -> beforeWrite(refused)
        uncertain -> Why.UNCERTAIN
        else -> Why.REFUSED
    }
}
