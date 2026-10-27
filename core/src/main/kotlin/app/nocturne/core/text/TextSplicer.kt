package app.nocturne.core.text

/** The editable field as the accessibility layer found it. */
data class FieldState(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int = selectionStart,
) {
    companion object {
        val EMPTY = FieldState("", 0, 0)
    }
}

data class SpliceResult(val text: String, val cursor: Int)

data class SpliceOptions(
    /** Insert a separating space where the seam would otherwise glue two words together. */
    val autoSpace: Boolean = true,
    /** Capitalize the first letter when the insertion starts a sentence. */
    val autoCapitalize: Boolean = true,
    /** Pull punctuation back onto the previous word: "Hallo ." becomes "Hallo." */
    val gluePunctuation: Boolean = true,
)

/**
 * Places dictated text into an existing field at the cursor.
 *
 * Android's ACTION_SET_TEXT replaces a field wholesale, so every insertion has to be
 * reconstructed here: take what is in the field, splice the new words in at the selection,
 * and hand back the full string plus where the cursor should end up. Getting the seam right
 * (spaces, capitals, punctuation) is what makes dictation feel like typing instead of
 * concatenation, so it lives in :core where it can be tested without a device.
 */
object TextSplicer {

    private val SENTENCE_ENDINGS = setOf('.', '!', '?', '…', ':')
    private val NO_SPACE_BEFORE = setOf(',', '.', '!', '?', ';', ':', ')', ']', '}', '%', '…')
    private val NO_SPACE_AFTER = setOf('(', '[', '{', '/', '-', '–')

    fun splice(
        field: FieldState,
        chunk: String,
        options: SpliceOptions = SpliceOptions(),
    ): SpliceResult {
        val limit = field.text.length
        val start = minOf(field.selectionStart, field.selectionEnd).coerceIn(0, limit)
        val end = maxOf(field.selectionStart, field.selectionEnd).coerceIn(0, limit)

        val trimmed = chunk.trim()
        // Nothing to insert: leave the field and any selection exactly as they were.
        if (trimmed.isEmpty()) return SpliceResult(field.text, field.selectionEnd.coerceIn(0, limit))

        var before = field.text.substring(0, start)
        val after = field.text.substring(end)
        var insert = trimmed

        val startsWithPunctuation = insert.first() in NO_SPACE_BEFORE
        if (options.gluePunctuation && startsWithPunctuation) {
            before = before.trimEnd(' ', '\t')
        } else if (options.autoSpace && needsLeadingSpace(before)) {
            insert = " $insert"
        }

        if (options.autoCapitalize && !startsWithPunctuation && atSentenceStart(before)) {
            insert = capitalizeFirstLetter(insert)
        }

        val cursor = before.length + insert.length

        // A separator before following text, but the cursor stays right after the words
        // that were just spoken so the next dictation continues in the same place.
        val separator = if (options.autoSpace && needsTrailingSpace(after)) " " else ""

        return SpliceResult(before + insert + separator + after, cursor)
    }

    private fun needsLeadingSpace(before: String): Boolean {
        val last = before.lastOrNull() ?: return false
        return !last.isWhitespace() && last !in NO_SPACE_AFTER
    }

    private fun needsTrailingSpace(after: String): Boolean {
        val next = after.firstOrNull() ?: return false
        return !next.isWhitespace() && next !in NO_SPACE_BEFORE
    }

    private fun atSentenceStart(before: String): Boolean {
        val anchor = before.trimEnd().lastOrNull() ?: return true
        return anchor in SENTENCE_ENDINGS || anchor == '\n'
    }

    private fun capitalizeFirstLetter(s: String): String {
        val i = s.indexOfFirst { it.isLetter() }
        if (i < 0 || !s[i].isLowerCase()) return s
        return s.substring(0, i) + s[i].uppercaseChar() + s.substring(i + 1)
    }
}
