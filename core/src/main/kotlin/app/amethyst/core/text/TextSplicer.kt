package app.amethyst.core.text

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
    /**
     * Capitals fit the seam: the first letter capitalized when the insertion starts a sentence,
     * the recognizer's capital on a common word taken off mid-sentence ([CommonWords]).
     */
    val autoCapitalize: Boolean = true,
    /**
     * Pull punctuation back onto the previous word: "Hallo ." becomes "Hallo." And the words'
     * full stop goes where the sentence goes on behind them: "See you." before "tomorrow".
     */
    val gluePunctuation: Boolean = true,
    /** What the words go into: a search box or an address gets no capital and no full stop. */
    val fieldKind: FieldKind = FieldKind.TEXT,
    /** The dictation's language ("en", "de"; null when unsure), for [CommonWords]. */
    val language: String? = null,
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

    /** Marks after which the sentence goes on: a common word there is lower-case. */
    private val MID_SENTENCE = setOf(',', ';')

    /** What the text behind the words may begin with for their full stop to go: the sentence goes on. */
    private val CONTINUES = setOf(',', ';', ':', '.', '!', '?')

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
        var insert = fitted(before, after, trimmed, options)

        val startsWithPunctuation = trimmed.first() in NO_SPACE_BEFORE
        if (options.gluePunctuation && startsWithPunctuation) {
            before = before.trimEnd(' ', '\t')
        } else if (options.autoSpace && needsLeadingSpace(before)) {
            insert = " $insert"
        }

        val cursor = before.length + insert.length

        // A separator before following text, but the cursor stays right after the words
        // that were just spoken so the next dictation continues in the same place.
        val separator = if (options.autoSpace && needsTrailingSpace(after)) " " else ""

        return SpliceResult(before + insert + separator + after, cursor)
    }

    /**
     * The words [splice] puts into [field], without the spaces of the seam: case and final mark
     * fitted to the text around them. What to look for in the field afterwards - a dropped full
     * stop is not in it.
     */
    fun words(field: FieldState, chunk: String, options: SpliceOptions = SpliceOptions()): String {
        val trimmed = chunk.trim()
        if (trimmed.isEmpty()) return trimmed
        val limit = field.text.length
        val start = minOf(field.selectionStart, field.selectionEnd).coerceIn(0, limit)
        val end = maxOf(field.selectionStart, field.selectionEnd).coerceIn(0, limit)
        return fitted(field.text.substring(0, start), field.text.substring(end), trimmed, options)
    }

    /**
     * [words] (trimmed, not empty) as they go between [before] and [after]. A recognizer hears
     * every dictation as a sentence of its own - a capital in front, a full stop behind - and
     * where it lands in the middle of one, neither fits. A search box or an address takes
     * neither at all ([FieldKind]).
     */
    private fun fitted(before: String, after: String, words: String, options: SpliceOptions): String {
        var fit = words
        val startsWithPunctuation = fit.first() in NO_SPACE_BEFORE
        if (options.autoCapitalize && !startsWithPunctuation) {
            fit = when {
                options.fieldKind != FieldKind.TEXT -> lowerForField(fit, options)
                atSentenceStart(before) -> capitalizeFirstLetter(fit)
                midSentence(before) -> CommonWords.lowerLeading(fit, options.language)
                else -> fit
            }
        }
        val dropStop = options.fieldKind != FieldKind.TEXT || (options.gluePunctuation && continues(after))
        if (dropStop && endsWithFullStop(fit)) fit = fit.dropLast(1)
        return fit
    }

    /**
     * A search box or an address: nothing to capitalize, and the recognizer's capital goes from
     * a word that has no other ("Pizza", "Example.com" - not "NASA", not "I"). In a German
     * search only from a common word: German nouns keep theirs, "Pizza in der Nähe".
     */
    private fun lowerForField(words: String, options: SpliceOptions): String {
        val word = words.takeWhile { it.isLetter() || it == '\'' || it == '’' }
        if (word.isEmpty() || !word.first().isUpperCase() || word.drop(1).any { it.isUpperCase() }) return words
        val address = options.fieldKind == FieldKind.URI || options.fieldKind == FieldKind.EMAIL
        val plain = !isEnglishI(word) && LanguageGuess.baseOf(options.language) != "de"
        if (!address && !plain) return CommonWords.lowerLeading(words, options.language)
        return words.first().lowercaseChar() + words.substring(1)
    }

    private fun isEnglishI(word: String): Boolean =
        word == "I" || word.startsWith("I'") || word.startsWith("I’")

    /** One full stop at the end - not an ellipsis, not a lone mark. */
    private fun endsWithFullStop(words: String): Boolean =
        words.length > 1 && words.last() == '.' && words[words.length - 2] != '.' && !words[words.length - 2].isWhitespace()

    /** The sentence goes on behind the cursor: a lower-case word or a mark on the same line. */
    private fun continues(after: String): Boolean {
        val next = after.trimStart(' ', '\t').firstOrNull() ?: return false
        return (next.isLetter() && next.isLowerCase()) || next in CONTINUES
    }

    /**
     * Words on the same line before the cursor, ending in a letter, a digit, a comma or a
     * semicolon: the middle of a sentence. After a quote or a bracket it may be a sentence of
     * its own, so nothing is lowered there.
     */
    private fun midSentence(before: String): Boolean {
        val anchor = before.trimEnd { it.isWhitespace() && it != '\n' && it != '\r' }.lastOrNull() ?: return false
        return anchor.isLetterOrDigit() || anchor in MID_SENTENCE
    }

    private fun needsLeadingSpace(before: String): Boolean {
        val last = before.lastOrNull() ?: return false
        return !last.isWhitespace() && last !in NO_SPACE_AFTER
    }

    private fun needsTrailingSpace(after: String): Boolean {
        val next = after.firstOrNull() ?: return false
        return !next.isWhitespace() && next !in NO_SPACE_BEFORE
    }

    /** Nothing before, a sentence's end, or a new line: the words start a sentence. */
    private fun atSentenceStart(before: String): Boolean {
        val anchor = before.trimEnd { it.isWhitespace() && it != '\n' && it != '\r' }.lastOrNull() ?: return true
        return anchor in SENTENCE_ENDINGS || anchor == '\n' || anchor == '\r'
    }

    private fun capitalizeFirstLetter(s: String): String {
        val i = s.indexOfFirst { it.isLetter() }
        if (i < 0 || !s[i].isLowerCase()) return s
        return s.substring(0, i) + s[i].uppercaseChar() + s.substring(i + 1)
    }
}
