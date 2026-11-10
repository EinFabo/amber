package app.amethyst.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.annotation.RequiresApi
import app.amethyst.DebugLog
import app.amethyst.R
import app.amethyst.core.bubble.TextFieldKind
import app.amethyst.core.insert.Clipboard
import app.amethyst.core.insert.EditableTarget
import app.amethyst.core.insert.InsertionEngine
import app.amethyst.core.insert.InsertionOutcome
import app.amethyst.core.insert.InputTarget
import app.amethyst.core.insert.SeenField
import app.amethyst.core.insert.TargetRefind
import app.amethyst.core.insert.WindowInFront
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

    /**
     * Into the field that has focus - or, with [into], into that node: a field found again
     * after Android lost track of the focus ([refind]).
     */
    fun insert(
        text: String,
        options: SpliceOptions,
        allowClipboard: Boolean,
        into: AccessibilityNodeInfo? = null,
    ): InsertionOutcome {
        val (target, trace) = if (into != null) {
            // No typing: without focus the input connection, if any, is not this field's.
            NodeTarget(service, into, { null }, mayType = false) to "found again: ${into.describe()}"
        } else {
            resolveTarget()
        }
        val outcome = engine.insert(target, text, options, allowClipboard)

        lastTrace = "$trace → ${outcome.strategy.name}" +
            when {
                outcome.uncertain -> " (typed, never showed up in the field)"
                outcome.insertedIntoField -> ""
                else -> " (not in the field)"
            }
        // The trace names the app and its field: logcat gets it in debug builds only.
        DebugLog.i(TAG) { "insertion: $lastTrace" }

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
        // Only for a node that names no package: the active window's root is a cross-process
        // call that also prefetches nodes.
        val fallbackPackage = { service.rootInActiveWindow?.packageName?.toString() }

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
     * The editable node Android reports as having input focus - the surest answer, when it
     * gives one (Compose fields do not). For the text commands, which look further as
     * [resolveTarget] does: the input connection, then [searchFocusedEditable].
     */
    fun inputFocus(): AccessibilityNodeInfo? =
        runCatching { service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()?.takeIf { it.isEditable }

    /**
     * A focused editable node found by searching the windows: [owner]'s, the app the input
     * connection belongs to - else, without a connection, only in the window in use. In split
     * screen the other half may hold a focused field of its own, which is not the user's.
     */
    fun searchFocusedEditable(owner: String?): AccessibilityNodeInfo? =
        findFocusedEditable(onlyInUse = owner == null, owner = owner)

    /** A field found again by [refind], or why there is none - [note] is for the diagnostics. */
    class Refound(val node: AccessibilityNodeInfo?, val note: String)

    /**
     * Looks for the dictation's field again when Android no longer says which field has focus:
     * [start]'s window must still be the one in front, and [TargetRefind] picks among its
     * editable fields - [startNode], the node that had focus at the start, when it is still
     * there. The search walks the one window, bounded like [findFocusedEditable].
     */
    fun refind(start: InputTarget?, startNode: AccessibilityNodeInfo?): Refound {
        val front = windowInFront()
        if (!TargetRefind.sameWindow(start, front?.first)) {
            return Refound(null, "field not found again: ${TargetRefind.pick(start, front?.first, emptyList()).describe()}")
        }
        val nodes = editableNodes(front!!.second)
        val seen = nodes.map { node ->
            SeenField(
                kind = TextFieldKind.of(node.inputType, node.isPassword),
                isStart = startNode != null && node == startNode,
                focused = node.isFocused,
                shown = node.isVisibleToUser && node.isEnabled,
            )
        }
        val result = TargetRefind.pick(start, front.first, seen)
        val node = (result as? TargetRefind.Result.Found)?.let { nodes[it.index] }
        val found = if (node != null) "field found again" else "field not found again"
        return Refound(node, "$found: ${result.describe()} (${nodes.size} editable in the window)")
    }

    /** Gives [node] input focus, as a tap would: its app starts input again, and typing works. */
    fun focus(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }.getOrDefault(false)

    /**
     * The window the user is in, with its root: the active one - which may be the lock screen
     * or the notification shade, and then the app is not in front - else, with no window
     * active at all, the topmost app window. The list comes top first.
     */
    private fun windowInFront(): Pair<WindowInFront, AccessibilityNodeInfo>? = runCatching {
        val windows = service.windows
        val window = windows.firstOrNull { it.isActive }
            ?: windows.firstOrNull { it.isFocused }
            ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?: return@runCatching null
        val root = window.root ?: return@runCatching null
        val pkg = root.packageName?.toString() ?: return@runCatching null
        WindowInFront(pkg, window.id) to root
    }.getOrNull()

    /** The editable nodes under [root], breadth-first, at most [MAX_FIELDS_SEEN]. */
    private fun editableNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = runCatching {
        val found = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES_SEARCHED && found.size < MAX_FIELDS_SEEN) {
            val node = queue.removeFirst()
            visited++
            if (node.isEditable) found += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        found.toList()
    }.getOrDefault(emptyList())

    private fun TargetRefind.Result.describe(): String = when (this) {
        is TargetRefind.Result.Found -> how.name.lowercase().replace('_', ' ')
        is TargetRefind.Result.NotFound -> why.name.lowercase().replace('_', ' ')
    }

    /** Whether Enter makes a new line in [node]'s field rather than sending or moving on. */
    fun takesLineBreaks(packageName: String, node: AccessibilityNodeInfo?): Boolean =
        enterMakesNewLine(service, packageName) ?: (node?.isMultiLine == true)

    /**
     * Breadth-first through every window, the active one first, bounded so it stays cheap. With
     * [onlyInUse] only the active or focused window; with [owner] only that app's windows.
     */
    private fun findFocusedEditable(onlyInUse: Boolean = false, owner: String? = null): AccessibilityNodeInfo? {
        val roots = runCatching {
            service.windows
                .filter { !onlyInUse || it.isActive || it.isFocused }
                .sortedByDescending { it.isActive || it.isFocused }
                .mapNotNull { it.root }
        }.getOrDefault(emptyList()).ifEmpty { listOfNotNull(service.rootInActiveWindow) }

        for (root in roots) {
            if (owner != null && root.packageName?.toString() != owner) continue
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
        /** Asked only when [node] names no package. */
        fallbackPackage: () -> String?,
        /** False for a node without focus: whatever input connection there is, it is another field's. */
        private val mayType: Boolean = true,
    ) : EditableTarget {

        override val packageName: String =
            node.packageName?.toString() ?: fallbackPackage() ?: InsertionEngine.UNKNOWN_PACKAGE

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
            mayType && commitThroughInputMethod(service, text, expectedPackage = packageName)

        /** What the input method knows about Enter in this field; without it, whether the field wraps lines. */
        override val takesLineBreaks: Boolean
            get() = (if (mayType) enterMakesNewLine(service, packageName) else null) ?: node.isMultiLine

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
                val bound = boundInput(service, expectedPackage = null)?.takeIf { it.connection != null } ?: return null
                return InputConnectionTarget(service, bound.owner)
            }
        }
    }

    /**
     * The field the service's input method is bound to (flagInputMethodEditor, Android 13+):
     * its EditorInfo, the app it belongs to and the connection, if there is one.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    class BoundInput(val editor: EditorInfo, val owner: String, val connection: InputMethod.AccessibilityInputConnection?)

    companion object {
        private const val TAG = "Amber"
        private const val MAX_NODES_SEARCHED = 1_500

        /** Enough to tell "the only field" from "one of several". */
        private const val MAX_FIELDS_SEEN = 16

        /** ClipDescription.EXTRA_IS_SENSITIVE (API 33); honoured by name on earlier versions too. */
        private const val SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"

        /**
         * The input the service's input method is bound to, if input has started - only
         * [expectedPackage]'s, when given: a connection left over from another app's field
         * would reach text the user is not looking at. An editor that does not say whose it
         * is gets nothing, the words could land anywhere. The one place these checks live, for
         * dictation and the text commands alike.
         */
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        fun boundInput(service: AccessibilityService, expectedPackage: String?): BoundInput? {
            val method = service.inputMethod ?: return null
            if (!method.currentInputStarted) return null
            val editor = method.currentInputEditorInfo ?: return null
            val owner = editor.packageName ?: return null
            if (expectedPackage != null && owner != expectedPackage) return null
            return BoundInput(editor, owner, method.currentInputConnection)
        }

        /**
         * Whether Enter in the field the input method is bound to makes a new line - multi-line,
         * and either no Enter action at all or one the field asks keyboards to ignore
         * (IME_FLAG_NO_ENTER_ACTION). "Send", "next", "search", "go" and "done" fields say
         * otherwise. Null when the input method knows nothing about this app's field.
         */
        fun enterMakesNewLine(service: AccessibilityService, packageName: String): Boolean? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
            val editor = boundInput(service, expectedPackage = packageName)?.editor ?: return null
            if (editor.inputType and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE == 0) return false
            if (editor.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return true
            val action = editor.imeOptions and EditorInfo.IME_MASK_ACTION
            return action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED
        }

        /**
         * Types [text] through the service's input method. With [expectedPackage] set it
         * refuses a connection that belongs to another app - typing into a connection left over
         * from a different window would put the words somewhere the user is not looking.
         */
        fun commitThroughInputMethod(
            service: AccessibilityService,
            text: String,
            expectedPackage: String?,
        ): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            val connection = boundInput(service, expectedPackage)?.connection ?: return false
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
