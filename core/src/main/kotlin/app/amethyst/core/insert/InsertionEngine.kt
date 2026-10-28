package app.amethyst.core.insert

import app.amethyst.core.text.FieldState
import app.amethyst.core.text.SpliceOptions
import app.amethyst.core.text.TextSplicer

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

    /** Current contents and selection, or null when they cannot be read. */
    fun readField(): FieldState?

    /** Replace the whole field. Returns what the platform claims, which may be a lie. */
    fun setText(text: String): Boolean

    fun setSelection(cursor: Int)

    /** Paste whatever is on the clipboard into the field. */
    fun paste(): Boolean

    /**
     * Insert [text] at the cursor through an input connection bound to this very field.
     * False when there is none - below Android 13, or when the field never started input.
     */
    fun commitText(text: String): Boolean = false

    /** Contents after an attempt, for verification. Null when unreadable. */
    fun readBack(): String?
}

fun interface Clipboard {
    /** Returns false if the clipboard could not be written at all. */
    fun copy(text: String): Boolean
}

data class InsertionOutcome(
    val strategy: InsertionStrategy,
    /** The text is in the field. This is the only thing that counts as inserted. */
    val insertedIntoField: Boolean,
    /** The text is at least on the clipboard, so the user can paste it by hand. */
    val copiedToClipboard: Boolean,
    val targetPackage: String?,
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
     */
    fun insert(
        target: EditableTarget?,
        text: String,
        options: SpliceOptions = SpliceOptions(),
        allowClipboard: Boolean = true,
    ): InsertionOutcome {
        val packageName = target?.packageName ?: UNKNOWN_PACKAGE
        var copied = false

        if (target == null) {
            // Nothing is focused, so there is nothing to write into. The clipboard keeps the
            // dictation from being lost - if the user lets us use it.
            if (allowClipboard) copied = clipboard.copy(text)
            return InsertionOutcome(InsertionStrategy.CLIPBOARD_ONLY, false, copied, null)
        }

        val usesClipboard = setOf(InsertionStrategy.PASTE, InsertionStrategy.CLIPBOARD_ONLY)
        val order = memory.order(packageName)
            .filter { allowClipboard || it !in usesClipboard }

        for (strategy in order) {
            val inserted = when (strategy) {
                InsertionStrategy.COMMIT_TEXT -> tryCommit(target, text, options)

                InsertionStrategy.SET_TEXT -> trySetText(target, text, options)

                InsertionStrategy.PASTE -> {
                    // Pasting needs the text on the clipboard first; that is also why a
                    // failed paste still leaves the dictation recoverable.
                    copied = clipboard.copy(text) || copied
                    copied && tryPaste(target, text)
                }

                InsertionStrategy.CLIPBOARD_ONLY -> {
                    copied = clipboard.copy(text) || copied
                    false
                }
            }

            if (inserted) {
                memory.recordSuccess(packageName, strategy)
                onMemoryChanged(memory)
                return InsertionOutcome(strategy, true, copied, packageName)
            }

            memory.recordFailure(packageName, strategy)
            onMemoryChanged(memory)
        }

        return InsertionOutcome(InsertionStrategy.CLIPBOARD_ONLY, false, copied, packageName)
    }

    /**
     * Not read back afterwards, unlike the others. A commit travels to the app asynchronously,
     * so an immediate read often still shows the old text - and treating that as a failure
     * would hand the same words to SET_TEXT and insert them twice. An input connection is the
     * channel every keyboard relies on; when one is bound to the field, the commit lands.
     */
    private fun tryCommit(target: EditableTarget, text: String, options: SpliceOptions): Boolean {
        if (!target.isEditable) return false
        val fragment = target.readField()
            ?.let { field -> commitFragment(field, text, options) }
            ?: text.trim()
        return fragment.isNotEmpty() && target.commitText(fragment)
    }

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

        // Verify before moving the cursor. A field that accepted the write and kept its old
        // contents would otherwise be handed a caret position that does not exist in it, and
        // the following paste would land somewhere arbitrary.
        if (!verify(target, text)) return false

        target.setSelection(spliced.cursor)
        return true
    }

    private fun tryPaste(target: EditableTarget, text: String): Boolean {
        if (!target.isEditable) return false
        if (!target.paste()) return false
        return verify(target, text)
    }

    /**
     * Compared loosely and case-insensitively on purpose: fields legitimately reformat what
     * they are given (phone numbers, amounts), and the splicer may have capitalised the first
     * letter. A strict comparison would report working insertions as failures.
     */
    private fun verify(target: EditableTarget, text: String): Boolean {
        val expected = text.trim()
        // Compose and many cross-platform fields apply a write on their next frame, so the
        // first read can still show the old text. Giving up on it would hand the same words
        // to the next strategy and insert them twice.
        repeat(VERIFY_ATTEMPTS) { attempt ->
            val after = target.readBack() ?: return false
            if (after.contains(expected, ignoreCase = true)) return true
            if (attempt < VERIFY_ATTEMPTS - 1) pause(VERIFY_INTERVAL_MS)
        }
        return false
    }

    companion object {
        const val UNKNOWN_PACKAGE = "unknown"

        /** Five reads 60 ms apart: about four frames at 60 Hz, well past one recomposition. */
        private const val VERIFY_ATTEMPTS = 5
        private const val VERIFY_INTERVAL_MS = 60L
    }
}
