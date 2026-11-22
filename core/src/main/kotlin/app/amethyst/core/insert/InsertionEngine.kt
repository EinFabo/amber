package app.amethyst.core.insert

import app.amethyst.core.text.FieldState
import app.amethyst.core.text.SpliceOptions
import app.amethyst.core.text.TextSplicer
import app.amethyst.core.text.TranscriptNormalizer

/**
 * The focused editable field of some app, as the platform layer can see it.
 *
 * Android's accessibility node is behind this interface so the insertion chain can be tested
 * against fields that misbehave the way real ones do - reporting a successful write and
 * then ignoring it.
 */
interface EditableTarget {
    /** Owner of the field; the key under which the working strategy is remembered. */
    val packageName: String

    /** False when the focused node is not an editable field (a button, a WebView canvas). */
    val isEditable: Boolean

    /**
     * Current contents and selection, or null when they cannot be read. Of a field reached
     * only through its input connection, the part around the cursor ([WindowedTarget]).
     */
    fun readField(): FieldState?

    /** Replace the whole field. Returns what the platform claims, which may be a lie. */
    fun setText(text: String): Boolean

    fun setSelection(cursor: Int)

    /**
     * The field's text and selection right after a write, to see where the cursor ended up.
     * Null where that cannot be told: a window onto the field ([WindowedTarget]) is not the
     * field, and a position in it is none in the field.
     */
    fun readCursor(): FieldState? = readField()

    /** Paste whatever is on the clipboard into the field. */
    fun paste(): Boolean

    /**
     * Insert [text] at the cursor through an input connection bound to this very field.
     * False when there is none - below Android 13, or when the field never started input.
     */
    fun commitText(text: String): Boolean = false

    /**
     * The field takes line breaks the way the user could type them: Enter makes a new line
     * there (multi-line, no "send", "next" or "search" action). Anywhere else - a title whose
     * Enter jumps to the body, a search box, a messenger set to "Enter sends" - a dictation
     * goes in as one line, as it would from the keyboard.
     */
    val takesLineBreaks: Boolean get() = false

    /**
     * A password or PIN field, by everything that says so about the very field that is about
     * to be written ([app.amethyst.core.bubble.SecretField]). Asked once, right before the
     * write; such a field gets nothing ([TargetCheck.atWrite]).
     */
    val isSecret: Boolean get() = false

    /** Contents after an attempt, for verification. Null when unreadable. */
    fun readBack(): String?
}

fun interface Clipboard {
    /** Returns false if the clipboard could not be written at all. */
    fun copy(text: String): Boolean

    /**
     * Takes [text] - copied a moment ago only to paste it - off the clipboard again, now that it
     * is in the field, while the clip is still the app's own ([OwnClip.clearAfterPaste]). True
     * when it was cleared.
     */
    fun forget(text: String): Boolean = false
}

data class InsertionOutcome(
    val strategy: InsertionStrategy,
    /** The text is in the field. This is the only thing that counts as inserted. */
    val insertedIntoField: Boolean,
    /** The text is at least on the clipboard, so the user can paste it by hand. */
    val copiedToClipboard: Boolean,
    val targetPackage: String?,
    /**
     * Typed through the input connection but never seen in the field. It may still arrive -
     * so nothing else was tried, which would risk the words twice - or it may be lost.
     */
    val uncertain: Boolean = false,
    /**
     * The characters the insertion put directly before the cursor - seam included - when it is
     * known, for "Undo last" ([UndoLast]). Null when the field could not be read or the splice
     * also changed text before it.
     */
    val fragment: String? = null,
    /**
     * Why nothing was written, or even tried: the field found for writing is not one the
     * words may go into ([TargetCheck.atWrite]). Nothing went to the clipboard either - the
     * words are still the caller's to keep.
     */
    val refused: TargetCheck.Verdict? = null,
    /**
     * The field had the cursor somewhere other than right behind the words after taking them
     * in, and it was put there ([InsertionEngine]) - for the diagnostics.
     */
    val cursorPutBack: Boolean = false,
)

/**
 * Puts dictated text into a field, trying the methods in the order that worked last time.
 *
 * The important part is not the chain, it is the verification: `performAction` returning true
 * is not evidence that anything happened. Many WebViews, games and cross-platform text fields
 * accept ACTION_SET_TEXT, report success, and leave the field untouched. So every attempt is
 * followed by reading the field back, and only the read decides whether to move on.
 */
class InsertionEngine(
    private val memory: StrategyMemory,
    private val clipboard: Clipboard,
    /** Called whenever the remembered per-app strategy changed, so it can be persisted. */
    private val onMemoryChanged: (StrategyMemory) -> Unit = {},
    /** Waits between read-backs; injectable so tests do not sleep. */
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {

    /**
     * [allowClipboard] false keeps every attempt off the clipboard: no paste, no copy as a
     * fallback. Users with a clipboard history would otherwise collect every dictation there.
     * [expectedPackage]: the app the words are for ([TargetCheck.expectedPackage]); a [target]
     * of another app is refused, as a password field always is ([TargetCheck.atWrite]).
     */
    fun insert(
        target: EditableTarget?,
        text: String,
        options: SpliceOptions = SpliceOptions(),
        allowClipboard: Boolean = true,
        expectedPackage: String? = null,
    ): InsertionOutcome {
        val packageName = target?.packageName ?: UNKNOWN_PACKAGE
        var copied = false
        committed = null
        setFragment = null
        cursorPutBack = false

        if (target == null) {
            // Nothing is focused, so there is nothing to write into. The clipboard keeps the
            // dictation from being lost - if the user lets us use it.
            if (allowClipboard) copied = clipboard.copy(text)
            return InsertionOutcome(InsertionStrategy.CLIPBOARD_ONLY, false, copied, null)
        }

        // The last look, at the field itself: whoever let the words through looked a moment
        // ago, and maybe at another field.
        val verdict = TargetCheck.atWrite(expectedPackage, packageName, secret = target.isSecret)
        if (verdict != TargetCheck.Verdict.INSERT) {
            return InsertionOutcome(InsertionStrategy.CLIPBOARD_ONLY, false, false, packageName, refused = verdict)
        }

        val usesClipboard = setOf(InsertionStrategy.PASTE, InsertionStrategy.CLIPBOARD_ONLY)
        // Typing comes first whenever an input connection is there: it is how every keyboard
        // writes, so apps keep it. SET_TEXT can show the words and still lose them - Google
        // Keep's note body displayed a SET_TEXT write and dropped it on closing (Pixel 9a,
        // 2026-10-29). What worked last time only orders the fallbacks.
        val order = (listOf(InsertionStrategy.COMMIT_TEXT) + memory.order(packageName).filter { it != InsertionStrategy.COMMIT_TEXT })
            .filter { allowClipboard || it !in usesClipboard }
        val words = if ('\n' in text && !target.takesLineBreaks) TranscriptNormalizer.oneLine(text) else text

        for (strategy in order) {
            val inserted = when (strategy) {
                InsertionStrategy.COMMIT_TEXT -> when (tryCommit(target, words, options)) {
                    Commit.LANDED -> true
                    Commit.NOT_POSSIBLE -> false
                    Commit.LOST -> {
                        memory.recordFailure(packageName, strategy)
                        onMemoryChanged(memory)
                        if (allowClipboard) copied = clipboard.copy(words) || copied
                        val shown = if (copied) InsertionStrategy.CLIPBOARD_ONLY else strategy
                        return InsertionOutcome(shown, false, copied, packageName, uncertain = true)
                    }
                }

                InsertionStrategy.SET_TEXT -> trySetText(target, words, options)

                InsertionStrategy.PASTE -> {
                    // Pasting needs the text on the clipboard first; that is also why a
                    // failed paste still leaves the dictation recoverable.
                    copied = clipboard.copy(words) || copied
                    copied && tryPaste(target, words)
                }

                InsertionStrategy.CLIPBOARD_ONLY -> {
                    copied = clipboard.copy(words) || copied
                    false
                }
            }

            if (inserted) {
                memory.recordSuccess(packageName, strategy)
                onMemoryChanged(memory)
                // The clip was only the way in: left there, every dictation pasted would stay
                // on the clipboard - for the next paste anywhere, and for any app allowed to
                // read it. A clip that is the fallback ("copied, paste it yourself") never
                // gets here: then nothing went into the field.
                if (copied && clipboard.forget(words)) copied = false
                val fragment = when (strategy) {
                    InsertionStrategy.COMMIT_TEXT -> committed
                    InsertionStrategy.SET_TEXT -> setFragment
                    InsertionStrategy.PASTE -> words
                    InsertionStrategy.CLIPBOARD_ONLY -> null
                }
                return InsertionOutcome(strategy, true, copied, packageName, fragment = fragment, cursorPutBack = cursorPutBack)
            }

            memory.recordFailure(packageName, strategy)
            onMemoryChanged(memory)
        }

        return InsertionOutcome(InsertionStrategy.CLIPBOARD_ONLY, false, copied, packageName)
    }

    private enum class Commit { LANDED, NOT_POSSIBLE, LOST }

    /** What the last commit typed: the fragment, for [InsertionOutcome.fragment]. */
    private var committed: String? = null

    /** What the last SET_TEXT added before the cursor, if it only added. */
    private var setFragment: String? = null

    /** The last insertion found the cursor elsewhere and put it behind the words. */
    private var cursorPutBack = false

    /**
     * A commit travels to the app asynchronously and the accessibility input connection reports
     * nothing back. So the field is read until the words show up - patiently, since an
     * immediate read often still shows the old text. When they never do, the connection was
     * stale (an app that rebuilt its field mid-dictation: WhatsApp, now and then) and the text
     * is LOST: reported, kept on the clipboard if allowed - but not typed again another way,
     * because a commit arriving late would then put the words in twice. A long text gets longer
     * to show up, and is compared loosely ([Landed]).
     */
    private fun tryCommit(target: EditableTarget, text: String, options: SpliceOptions): Commit {
        if (!target.isEditable) return Commit.NOT_POSSIBLE
        val field = target.readField()
        val fragment = field?.let { commitFragment(it, text, options) } ?: text.trim()
        if (fragment.isEmpty() || !target.commitText(fragment)) return Commit.NOT_POSSIBLE
        committed = fragment
        // A field that cannot be read cannot be checked, and got the words without a seam: a
        // connection that hands out no text is all there is of it.
        if (field == null) return Commit.LANDED
        val attempts = Landed.attempts(COMMIT_VERIFY_ATTEMPTS, text.length)
        repeat(attempts) { attempt ->
            val after = target.readBack() ?: return Commit.LANDED
            if (Landed.inField(field, after, text)) {
                cursorPutBack = cursorBehindWords(target, tail = textAfterSelection(field))
                return Commit.LANDED
            }
            if (attempt < attempts - 1) pause(VERIFY_INTERVAL_MS)
        }
        return Commit.LOST
    }

    /**
     * Typing asks the app to put the cursor behind the words (commitText with position 1), and
     * the next dictation goes on from there. An app that leaves it elsewhere - in WhatsApp it
     * stood before a final "?" (Pixel 9a, 2026-11-16) - gets it put there: the text that stood
     * behind the selection, [tail], is all that may follow it. Counted from the field as it now
     * reads, so a field that wrote the words a little differently still gets it right. Left
     * alone where the end of the field is no longer [tail]: then the field changed in another
     * way, and nobody can say where "behind the words" is. True when it was moved.
     */
    private fun cursorBehindWords(target: EditableTarget, tail: String): Boolean {
        val now = target.readCursor() ?: return false
        if (!now.text.endsWith(tail)) return false
        val behind = now.text.length - tail.length
        if (now.selectionStart == behind && now.selectionEnd == behind) return false
        target.setSelection(behind)
        return true
    }

    private fun textAfterSelection(field: FieldState): String =
        field.text.substring(maxOf(field.selectionStart, field.selectionEnd).coerceIn(0, field.text.length))

    /**
     * What to type so the field ends up as [TextSplicer] would have spliced it: the seam
     * (leading space, capital) included, the untouched text around it excluded. Where the
     * splice changed text before the cursor - pulling punctuation back - typing cannot
     * express that, so the plain words are used instead.
     */
    private fun commitFragment(field: FieldState, text: String, options: SpliceOptions): String {
        val limit = field.text.length
        val start = minOf(field.selectionStart, field.selectionEnd).coerceIn(0, limit)
        val end = maxOf(field.selectionStart, field.selectionEnd).coerceIn(0, limit)
        val before = field.text.substring(0, start)
        val after = field.text.substring(end)

        val spliced = TextSplicer.splice(field, text, options).text
        if (!spliced.startsWith(before) || !spliced.endsWith(after)) return text.trim()
        return spliced.substring(before.length, spliced.length - after.length)
    }

    private fun trySetText(target: EditableTarget, text: String, options: SpliceOptions): Boolean {
        if (!target.isEditable) return false
        val field = target.readField() ?: return false

        val spliced = TextSplicer.splice(field, text, options)
        if (!target.setText(spliced.text)) return false
        setFragment = UndoLast.added(field, spliced.text, spliced.cursor)

        // Verify before moving the cursor. A field that accepted the write and kept its old
        // contents would otherwise be handed a caret position that does not exist in it, and
        // the following paste would land somewhere arbitrary.
        if (!verify(target, field, text)) return false

        // Where the splice put the cursor, counted from the end: a field that reformats what
        // it is given (a line break written as two characters) would otherwise have it land
        // inside the words - one before a final "?".
        val tail = spliced.text.substring(spliced.cursor.coerceIn(0, spliced.text.length))
        val now = target.readCursor()
        val cursor = now?.takeIf { it.text.endsWith(tail) }?.let { it.text.length - tail.length } ?: spliced.cursor
        target.setSelection(cursor)
        return true
    }

    private fun tryPaste(target: EditableTarget, text: String): Boolean {
        if (!target.isEditable) return false
        val before = target.readField()
        if (!target.paste()) return false
        return verify(target, before, text)
    }

    /**
     * Compared loosely ([Landed]) on purpose: fields legitimately reformat what they are given
     * (phone numbers, amounts, line breaks), and the splicer may have capitalised the first
     * letter. A strict comparison would report working insertions as failures - and hand the
     * words to the next method, which puts them in twice. [before] is the field before
     * the write, null when unknown.
     */
    private fun verify(target: EditableTarget, before: FieldState?, text: String): Boolean {
        // Compose and many cross-platform fields apply a write on their next frame, so the
        // first read can still show the old text. Giving up on it would hand the same words
        // to the next strategy and insert them twice.
        val attempts = Landed.attempts(VERIFY_ATTEMPTS, text.length)
        repeat(attempts) { attempt ->
            val after = target.readBack() ?: return false
            if (Landed.inField(before, after, text)) return true
            if (attempt < attempts - 1) pause(VERIFY_INTERVAL_MS)
        }
        return false
    }

    companion object {
        const val UNKNOWN_PACKAGE = "unknown"

        /**
         * Five reads 60 ms apart: about four frames at 60 Hz, well past one recomposition. A
         * long text gets more, up to about 3 s ([Landed.attempts]).
         */
        private const val VERIFY_ATTEMPTS = 5
        private const val VERIFY_INTERVAL_MS = 60L

        /**
         * About 0.7 s: an input connection's round trip through the app, with room to spare.
         * A long text gets more, up to about 3 s ([Landed.attempts]).
         */
        private const val COMMIT_VERIFY_ATTEMPTS = 12
    }
}
