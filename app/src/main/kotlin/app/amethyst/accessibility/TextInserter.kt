package app.amethyst.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import app.amethyst.R
import app.amethyst.core.insert.Clipboard
import app.amethyst.core.insert.EditableTarget
import app.amethyst.core.insert.InsertionEngine
import app.amethyst.core.insert.InsertionOutcome
import app.amethyst.core.text.FieldState
import app.amethyst.core.text.SpliceOptions
import android.view.inputmethod.EditorInfo
import app.amethyst.data.StrategyStore

/**
 * Connects the accessibility node tree to the insertion chain in :core.
 *
 * All the decision-making - which method to try, how to verify it, what to remember per app -
 * lives in [InsertionEngine], where it is unit-tested against fields that misbehave. What is
 * left here is the part that genuinely needs Android: finding the focused node, reading and
 * writing it, and the clipboard.
 */
class TextInserter(
    private val service: AccessibilityService,
    strategyStore: StrategyStore,
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val engine = InsertionEngine(
        memory = strategyStore.load(),
        clipboard = Clipboard { text -> copyToClipboard(text) },
        onMemoryChanged = { memory -> strategyStore.save(memory) },
    )

    /** How the last insertion found its target and what it did, for the diagnostics card. */
    @Volatile
    var lastTrace: String = ""
        private set

    fun insert(text: String, options: SpliceOptions, allowClipboard: Boolean): InsertionOutcome {
        val (target, trace) = resolveTarget()
        val outcome = engine.insert(target, text, options, allowClipboard)

        lastTrace = "$trace → ${outcome.strategy.name}" +
            when {
                outcome.uncertain -> " (typed, never showed up in the field)"
                outcome.insertedIntoField -> ""
                else -> " (not in the field)"
            }
        Log.i(TAG, "insertion: $lastTrace")

        // Only nag when the text really did not make it into the field. A successful paste
        // also uses the clipboard, and announcing that every time would be noise.
        if (!outcome.insertedIntoField && outcome.copiedToClipboard) {
            mainHandler.post {
                val message = if (outcome.uncertain) R.string.clipboard_after_lost_commit else R.string.clipboard_fallback
                Toast.makeText(service, message, Toast.LENGTH_LONG).show()
            }
        }

        return outcome
    }

    /**
     * Finds the field the user is typing into.
     *
     * findFocus(FOCUS_INPUT) alone is not enough: Jetpack Compose (1.7) answers it with the
     * node that has *accessibility* focus, which without TalkBack is nothing - so every Compose
     * text field, Amber's own test field included, came back as "no target". When the
     * answer is not an editable node, the windows are searched for the focused editable one,
     * and as a last resort the service's own input connection is used: it is bound to the
     * very editor the keyboard is typing into, whatever the node tree says.
     */
    private fun resolveTarget(): Pair<EditableTarget?, String> {
        val fallbackPackage = service.rootInActiveWindow?.packageName?.toString()

        val focused = runCatching { service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (focused?.isEditable == true) {
            return NodeTarget(service, focused, fallbackPackage) to "focus: ${focused.describe()}"
        }

        // Before searching the tree: the connection needs no IPC per node, and it points at
        // the field the keyboard is typing into. The search walks up to 1500 nodes, each one
        // a cross-process call.
        val connection = InputConnectionTarget.from(service)
        if (connection != null) {
            return connection to "input connection (${connection.packageName})"
        }

        val searched = findFocusedEditable()
        if (searched != null) {
            return NodeTarget(service, searched, fallbackPackage) to
                "searched: ${searched.describe()} (findFocus: ${focused?.describe() ?: "nothing"})"
        }

        return null to "no text field found (findFocus: ${focused?.describe() ?: "nothing"})"
    }

    /**
     * The editable node that has input focus - for the text commands, which change the field
     * the user is writing in. Found as [resolveTarget] finds it, without the input connection.
     */
    fun focusedEditable(): AccessibilityNodeInfo? {
        val focused = runCatching { service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        return if (focused?.isEditable == true) focused else findFocusedEditable()
    }

    /** Whether Enter makes a new line in [node]'s field rather than sending or moving on. */
    fun takesLineBreaks(packageName: String, node: AccessibilityNodeInfo?): Boolean =
        enterMakesNewLine(service, packageName) ?: (node?.isMultiLine == true)

    /** Breadth-first through every window, the active one first, bounded so it stays cheap. */
    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val roots = runCatching {
            service.windows.sortedByDescending { it.isActive || it.isFocused }.mapNotNull { it.root }
        }.getOrDefault(emptyList()).ifEmpty { listOfNotNull(service.rootInActiveWindow) }

        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            var visited = 0
            while (queue.isNotEmpty() && visited < MAX_NODES_SEARCHED) {
                val node = queue.removeFirst()
                visited++
                if (node.isEditable && node.isFocused) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
            }
        }
        return null
    }

    private fun AccessibilityNodeInfo.describe(): String =
        "${className?.toString()?.substringAfterLast('.') ?: "?"} in ${packageName ?: "?"}" +
            if (isEditable) "" else " (not editable)"

    /**
     * The previous clipboard content is deliberately not restored: since Android 10 an app
     * that is not in the foreground cannot read the clipboard, so a "restore" would overwrite
     * it with null. Better to leave the dictation there than to destroy what the user copied.
     */
    fun copyToClipboard(text: String): Boolean {
        val clipboard = service.getSystemService(ClipboardManager::class.java) ?: return false
        return try {
            val clip = ClipData.newPlainText("Amber", text)
            // Dictations are private: kept out of clipboard previews, history and keyboard sync.
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean(SENSITIVE_EXTRA, true)
            }
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            Log.w(TAG, "clipboard write failed", e)
            false
        }
    }

    /** One focused accessibility node, seen as an editable field. */
    private class NodeTarget(
        private val service: AccessibilityService,
        private val node: AccessibilityNodeInfo,
        fallbackPackage: String?,
    ) : EditableTarget {

        override val packageName: String =
            node.packageName?.toString() ?: fallbackPackage ?: InsertionEngine.UNKNOWN_PACKAGE

        override val isEditable: Boolean get() = node.isEditable

        override fun readField(): FieldState? {
            // refresh() failing means the node is stale - the window changed under us - which
            // is the only genuine "cannot read" case.
            if (!node.refresh()) return null

            val existing = currentText()
            val start = node.textSelectionStart
            val end = node.textSelectionEnd
            return if (start in 0..existing.length && end in 0..existing.length) {
                FieldState(existing, start, end)
            } else {
                // No usable selection reported: append at the end rather than overwrite.
                FieldState(existing, existing.length, existing.length)
            }
        }

        override fun setText(text: String): Boolean {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }

        override fun setSelection(cursor: Int) {
            val arguments = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
        }

        override fun paste(): Boolean =
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)

        /**
         * The service's input method (flagInputMethodEditor) is bound to whichever field has
         * started input. It is only used when that field belongs to the same app as the
         * focused node - typing into a connection left over from another window would put
         * the words somewhere the user is not looking.
         */
        override fun commitText(text: String): Boolean =
            commitThroughInputMethod(service, text, expectedPackage = packageName)

        /** What the input method knows about Enter in this field; without it, whether the field wraps lines. */
        override val takesLineBreaks: Boolean get() = enterMakesNewLine(service, packageName) ?: node.isMultiLine

        override fun readBack(): String? {
            if (!node.refresh()) return null
            return currentText()
        }

        /**
         * An empty field reports null text, which must read as "" and not as unreadable -
         * otherwise dictation would never work into an empty field. And a field showing its
         * placeholder reports that placeholder as its text, which must not be spliced onto.
         */
        private fun currentText(): String = when {
            node.isShowingHintText -> ""
            else -> node.text?.toString().orEmpty()
        }
    }

    /**
     * The field as only the input connection sees it: no node to read or write, but the
     * channel a keyboard uses. Only typing works through it, which is the method that matters.
     */
    private class InputConnectionTarget(
        private val service: AccessibilityService,
        override val packageName: String,
    ) : EditableTarget {
        override val isEditable: Boolean = true
        override fun readField(): FieldState? = null
        override fun setText(text: String): Boolean = false
        override fun setSelection(cursor: Int) = Unit
        override fun paste(): Boolean = false
        override fun readBack(): String? = null
        override fun commitText(text: String): Boolean =
            commitThroughInputMethod(service, text, expectedPackage = packageName)

        override val takesLineBreaks: Boolean get() = enterMakesNewLine(service, packageName) == true

        companion object {
            fun from(service: AccessibilityService): InputConnectionTarget? {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
                val inputMethod = service.inputMethod ?: return null
                if (!inputMethod.currentInputStarted || inputMethod.currentInputConnection == null) return null
                val owner = inputMethod.currentInputEditorInfo?.packageName ?: return null
                return InputConnectionTarget(service, owner)
            }
        }
    }

    private companion object {
        const val TAG = "Amber"
        const val MAX_NODES_SEARCHED = 1_500

        /** ClipDescription.EXTRA_IS_SENSITIVE (API 33); honoured by name on earlier versions too. */
        const val SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"

        /**
         * Types [text] through the service's input method (flagInputMethodEditor). With
         * [expectedPackage] set it refuses a connection that belongs to another app - typing
         * into a connection left over from a different window would put the words somewhere
         * the user is not looking.
         */
        /**
         * Whether Enter in the field the input method is bound to makes a new line - multi-line,
         * and either no Enter action at all or one the field asks keyboards to ignore
         * (IME_FLAG_NO_ENTER_ACTION). "Send", "next", "search", "go" and "done" fields say
         * otherwise. Null when the input method knows nothing about this app's field.
         */
        fun enterMakesNewLine(service: AccessibilityService, packageName: String): Boolean? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
            val method = service.inputMethod ?: return null
            if (!method.currentInputStarted) return null
            val editor = method.currentInputEditorInfo ?: return null
            if (editor.packageName != packageName) return null
            if (editor.inputType and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE == 0) return false
            if (editor.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return true
            val action = editor.imeOptions and EditorInfo.IME_MASK_ACTION
            return action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED
        }

        fun commitThroughInputMethod(
            service: AccessibilityService,
            text: String,
            expectedPackage: String?,
        ): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            val inputMethod = service.inputMethod ?: return false
            if (!inputMethod.currentInputStarted) return false
            // An editor that does not say whose it is gets nothing: the words could land anywhere.
            val editorPackage = inputMethod.currentInputEditorInfo?.packageName ?: return false
            if (expectedPackage != null && editorPackage != expectedPackage) return false
            val connection = inputMethod.currentInputConnection ?: return false
            return try {
                // Line breaks arrive here only for a field where Enter makes a new line; for any
                // other, InsertionEngine writes them with SET_TEXT or types one line. The
                // accessibility input connection reports nothing back; whether the words arrived
                // is checked by InsertionEngine reading the field afterwards.
                connection.commitText(text, 1, null)
                true
            } catch (e: RuntimeException) {
                Log.w(TAG, "commitText failed", e)
                false
            }
        }
    }
}
