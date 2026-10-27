package app.nocturne.core.insert

import app.nocturne.core.text.FieldState
import app.nocturne.core.text.SpliceOptions
import app.nocturne.core.text.TextSplicer

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
) {

    fun insert(
        target: EditableTarget?,
        text: String,
        options: SpliceOptions = SpliceOptions(),
    ): InsertionOutcome {
        val packageName = target?.packageName ?: UNKNOWN_PACKAGE
        var copied = false

        if (target == null) {
            // Nothing is focused, so there is nothing to write into. The clipboard keeps the
            // dictation from being lost.
            copied = clipboard.copy(text)
            return InsertionOutcome(InsertionStrategy.CLIPBOARD_ONLY, false, copied, null)
        }

        for (strategy in memory.order(packageName)) {
            val inserted = when (strategy) {
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
        val after = target.readBack() ?: return false
        return after.contains(text.trim(), ignoreCase = true)
    }

    companion object {
        const val UNKNOWN_PACKAGE = "unknown"
    }
}
