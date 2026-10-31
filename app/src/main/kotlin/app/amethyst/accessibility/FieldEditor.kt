package app.amethyst.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import app.amethyst.core.bubble.TextFieldKind
import app.amethyst.core.commands.CommandOutcome
import app.amethyst.core.commands.CommandTrace
import app.amethyst.core.commands.LineBreakPlan
import app.amethyst.core.commands.TextEdit
import app.amethyst.core.commands.TextEdits
import app.amethyst.core.text.FieldState
import kotlinx.coroutines.delay

/**
 * The text commands: changes to the field the user is writing in, worked out by [TextEdits]
 * and written the ways a dictation is - the service's input connection (Android 13+), the
 * field's own node actions. Only an editable field that is not a password field, and only the
 * one the command was started in (checked before, [app.amethyst.core.commands.EditTarget]).
 * Its text is read for the command that needs it and goes nowhere - not to logcat, not into a
 * message, not into the [CommandTrace], which gets how the field was reached and what changed
 * as counts only.
 */
class FieldEditor(private val service: AccessibilityService, private val inserter: TextInserter) {

    suspend fun apply(edit: TextEdit, times: Int, trace: CommandTrace): CommandOutcome {
        val field = find()
        if (field == null) {
            trace.note("field: none found - no focused node, no input connection")
            return CommandOutcome.Failed("No text field has the cursor - tap into one first.")
        }
        trace.note("field: ${field.reached}")
        if (field.secret) {
            trace.note("field: a password field - refused")
            return CommandOutcome.Refused("Not in a password field.")
        }
        return when (edit) {
            TextEdit.DELETE_WORD -> deleteWords(field, times, trace)
            TextEdit.SELECT_ALL -> selectAll(field, trace)
            TextEdit.COPY -> clipboard(field, AccessibilityNodeInfo.ACTION_COPY, android.R.id.copy, "Copied", selection = true, trace)
            TextEdit.CUT -> clipboard(field, AccessibilityNodeInfo.ACTION_CUT, android.R.id.cut, "Cut", selection = true, trace)
            TextEdit.PASTE -> clipboard(field, AccessibilityNodeInfo.ACTION_PASTE, android.R.id.paste, "Pasted", selection = false, trace)
            TextEdit.NEW_LINE -> newLine(field, trace)
        }
    }

    /**
     * The field with the cursor: its node, and from Android 13 the input connection bound to it.
     * [reached] says how, for Diagnostics - never what the field holds.
     */
    private class Field(
        val node: AccessibilityNodeInfo?,
        val keys: Keys?,
        val secret: Boolean,
        val takesLineBreaks: Boolean,
        val reached: String,
    )

    /**
     * The field with the cursor, looked for in a dictation's order: the node Android reports as
     * having input focus, with the input connection only if it belongs to the same app; else
     * (Android 13+) the input connection - bound to the very field the keyboard types into -
     * with a searched node only from that app; else a searched node only in the window in use.
     * Never a field in another window: in split screen the other half may have one focused too.
     */
    private fun find(): Field? {
        val focused = inserter.inputFocus()
        val focusedPackage = focused?.packageName?.toString()
        val bound = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> null
            // A focused node that does not say whose it is: no connection to match it with.
            focused != null && focusedPackage == null -> null
            else -> ConnectionKeys.bound(service, expectedPackage = focusedPackage)
        }
        val node = focused ?: inserter.searchFocusedEditable(owner = bound?.packageName)
        if (node == null && bound == null) return null
        val packageName = node?.packageName?.toString() ?: bound?.packageName ?: return null
        val secret = (node != null && TextFieldKind.of(node.inputType, node.isPassword) == TextFieldKind.SECRET) ||
            bound?.kind == TextFieldKind.SECRET
        val reached = listOfNotNull(
            node?.let { (if (it == focused) "the input-focus node" else "a searched node") + " (${it.className?.toString()?.substringAfterLast('.') ?: "unnamed"})" },
            bound?.let { "the input connection" },
        ).joinToString(" and ") + " in $packageName"
        return Field(node, bound?.keys, secret, inserter.takesLineBreaks(packageName, node), reached)
    }

    private suspend fun deleteWords(field: Field, count: Int, trace: CommandTrace): CommandOutcome {
        val did = if (count == 1) "Deleted the last word" else "Deleted $count words"
        val keys = field.keys
        val before = keys?.surrounding()
        if (keys != null && before != null) {
            val chars = TextEdits.charsBeforeCursor(before.state, count)
            if (chars == 0) {
                trace.note("delete via the input connection: nothing before the cursor")
                return CommandOutcome.Failed(NOTHING_BEFORE)
            }
            val cursor = before.beforeCursor().length
            val words = wordCount(before.state.text.substring(cursor - chars, cursor))
            keys.deleteBefore(chars)
            val changed = verified(keys) { after -> after.beforeCursor() != before.beforeCursor() }
            trace.note(
                "delete via the input connection: ${words(words)} ($chars characters) - " +
                    if (changed) "the field took it" else "the field did not take it",
            )
            return if (changed) CommandOutcome.Done(did) else CommandOutcome.Failed(NOT_TAKEN)
        }
        val node = field.node
        if (node == null) {
            trace.note("delete: no input connection and no node to write to")
            return CommandOutcome.Failed(NOT_EDITABLE)
        }
        val state = read(node)
        if (state == null) {
            trace.note("delete via the node: the field cannot be read")
            return CommandOutcome.Failed(UNREADABLE)
        }
        val result = TextEdits.deleteWords(state, count)
        if (result == state) {
            trace.note("delete via the node: nothing before the cursor")
            return CommandOutcome.Failed(NOTHING_BEFORE)
        }
        val start = minOf(state.selectionStart, state.selectionEnd).coerceIn(0, state.text.length)
        val chars = state.text.length - result.text.length
        trace.note("delete via the node: ${words(wordCount(state.text.substring(start - chars, start)))} ($chars characters)")
        return write(node, result, did, trace)
    }

    /** How many words [text] has - a count for Diagnostics, the words themselves go nowhere. */
    private fun wordCount(text: String): Int = text.split(WHITESPACE).count { it.isNotEmpty() }

    private fun words(n: Int) = if (n == 1) "1 word" else "$n words"

    /**
     * Where Enter would send or move on, a line break is not typed: it might do just that. A
     * selection stays - the break goes behind it, not over it ([TextEdits.lineBreakPlan]) - and
     * "New line" is said only once the break is there.
     */
    private suspend fun newLine(field: Field, trace: CommandTrace): CommandOutcome {
        if (!field.takesLineBreaks) {
            trace.note("new line: Enter sends or moves on in this field - refused")
            return CommandOutcome.Refused("Enter sends or moves on in this field - no new line here.")
        }
        val keys = field.keys
        val before = keys?.surrounding()
        if (keys != null && before != null) {
            val plan = TextEdits.lineBreakPlan(before.state, before.offset)
            when (plan) {
                LineBreakPlan.AtCursor -> {
                    trace.note("new line via the input connection: at the cursor")
                    keys.type("\n")
                }
                is LineBreakPlan.After -> {
                    trace.note("new line via the input connection: behind the selection")
                    keys.select(plan.position, plan.position)
                    keys.type("\n")
                }
                // The connection cannot tell where the selection ends in the field; the node can.
                LineBreakPlan.Unknown -> {
                    trace.note("new line: the input connection does not say where the selection ends - the node's way")
                    return newLineByNode(field, trace)
                        ?: CommandOutcome.Failed("This field does not say where the selection ends - tap where the new line goes.")
                }
            }
            if (verified(keys) { after -> TextEdits.lineBreakLanded(before.state, after.state) }) {
                trace.note("new line: the break arrived")
                return CommandOutcome.Done("New line")
            }
            trace.note("new line: the break did not arrive where it belongs")
            // Typed but not taken: the selection as it was, not collapsed by the move.
            if (plan is LineBreakPlan.After) {
                keys.select(before.offset + before.state.selectionStart, before.offset + before.state.selectionEnd)
            }
            return CommandOutcome.Failed(NOT_TAKEN)
        }
        return newLineByNode(field, trace) ?: CommandOutcome.Failed(NOT_EDITABLE).also {
            trace.note("new line: no input connection and no node to write to")
        }
    }

    /** The node's way: the whole text with the break in it. Null without a node. */
    private fun newLineByNode(field: Field, trace: CommandTrace): CommandOutcome? {
        val node = field.node ?: return null
        val state = read(node)
        if (state == null) {
            trace.note("new line via the node: the field cannot be read")
            return CommandOutcome.Failed(UNREADABLE)
        }
        trace.note("new line via the node: the text set again with the break in it")
        return write(node, TextEdits.newLine(state), "New line", trace)
    }

    private fun selectAll(field: Field, trace: CommandTrace): CommandOutcome {
        val node = field.node
        if (node != null && node.offers(AccessibilityNodeInfo.ACTION_SET_SELECTION)) {
            val length = read(node)?.text?.length
            if (length == null) {
                trace.note("select all via the node: the field cannot be read")
                return CommandOutcome.Failed(UNREADABLE)
            }
            val taken = select(node, 0, length)
            trace.note("select all via the node: $length characters - ${if (taken) "taken" else "refused"}")
            return if (taken) CommandOutcome.Done("Selected all") else CommandOutcome.Failed(NOT_TAKEN)
        }
        val keys = field.keys
        if (keys == null) {
            trace.note("select all: the node offers no selection and there is no input connection")
            return CommandOutcome.Failed(NOT_OFFERED)
        }
        keys.contextMenu(android.R.id.selectAll)
        trace.note("select all via the input connection's menu action (it reports nothing back)")
        return CommandOutcome.Done("Selected all")
    }

    /**
     * Copy, cut and paste: the node's own action where the field offers it - it says whether it
     * worked, and Compose fields know these only as node actions - else the input connection's.
     */
    private fun clipboard(field: Field, nodeAction: Int, menuItem: Int, done: String, selection: Boolean, trace: CommandTrace): CommandOutcome {
        val node = field.node
        val what = done.lowercase()
        if (selection) {
            val state = node?.let(::read) ?: field.keys?.surrounding()?.state
            when {
                state == null -> trace.note("selection: not readable - tried anyway")
                state.selectionStart == state.selectionEnd -> {
                    trace.note("selection: nothing selected")
                    return CommandOutcome.Failed("Nothing is selected - say “select all” first.")
                }
                else -> trace.note("selection: ${kotlin.math.abs(state.selectionEnd - state.selectionStart)} characters")
            }
        }
        if (node != null && node.offers(nodeAction)) {
            val taken = node.performAction(nodeAction)
            trace.note("$what via the node's action: ${if (taken) "taken" else "refused"}")
            return if (taken) CommandOutcome.Done(done) else CommandOutcome.Failed(NOT_TAKEN)
        }
        val keys = field.keys
        if (keys == null) {
            trace.note("$what: the node does not offer it and there is no input connection")
            return CommandOutcome.Failed(NOT_OFFERED)
        }
        keys.contextMenu(menuItem)
        trace.note("$what via the input connection's menu action (it reports nothing back)")
        return CommandOutcome.Done(done)
    }

    /**
     * An input connection reports nothing back and its changes travel asynchronously, so the
     * field is read again until [arrived] says the change is there. Unreadable afterwards:
     * done, as far as anyone can tell.
     */
    private suspend fun verified(keys: Keys, arrived: (Surrounding) -> Boolean): Boolean {
        repeat(VERIFY_ATTEMPTS) { attempt ->
            val after = keys.surrounding() ?: return true
            if (arrived(after)) return true
            if (attempt < VERIFY_ATTEMPTS - 1) delay(VERIFY_INTERVAL_MS)
        }
        return false
    }

    /** The node's text and selection; a field showing its placeholder is empty. */
    private fun read(node: AccessibilityNodeInfo): FieldState? {
        if (!node.refresh()) return null
        val text = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        return if (start in 0..text.length && end in 0..text.length) FieldState(text, start, end) else FieldState(text, text.length)
    }

    private fun write(node: AccessibilityNodeInfo, result: FieldState, done: String, trace: CommandTrace): CommandOutcome {
        val text = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, result.text) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text)) {
            trace.note("set text on the node: refused")
            return CommandOutcome.Failed(NOT_TAKEN)
        }
        select(node, result.selectionStart, result.selectionEnd)
        val landed = read(node)?.text == result.text
        trace.note("set text on the node: taken, read back ${if (landed) "as intended" else "different from what was set"}")
        return if (landed) CommandOutcome.Done(done) else CommandOutcome.Failed(NOT_TAKEN)
    }

    private fun select(node: AccessibilityNodeInfo, start: Int, end: Int): Boolean {
        val range = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, range)
    }

    private fun AccessibilityNodeInfo.offers(action: Int): Boolean = actionList.any { it.id == action }

    /** Some text around the cursor, [offset] where it starts in the field. */
    private class Surrounding(val state: FieldState, val offset: Int) {
        fun beforeCursor(): String = state.text.substring(0, minOf(state.selectionStart, state.selectionEnd).coerceIn(0, state.text.length))
    }

    /** What a keyboard can do in the field: the service's input connection, Android 13+. */
    private interface Keys {
        fun surrounding(): Surrounding?
        fun deleteBefore(chars: Int)
        fun select(start: Int, end: Int)
        fun type(text: String)
        fun contextMenu(id: Int)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private class ConnectionKeys(private val connection: InputMethod.AccessibilityInputConnection) : Keys {

        override fun surrounding(): Surrounding? = runCatching {
            connection.getSurroundingText(TEXT_AROUND, 0, 0)?.let { around ->
                Surrounding(FieldState(around.text.toString(), around.selectionStart, around.selectionEnd), around.offset)
            }
        }.getOrNull()

        override fun deleteBefore(chars: Int) = connection.deleteSurroundingText(chars, 0)

        override fun select(start: Int, end: Int) = connection.setSelection(start, end)

        override fun type(text: String) = connection.commitText(text, 1, null)

        override fun contextMenu(id: Int) = connection.performContextMenuAction(id)

        /** The connection, its field's kind and owner - [expectedPackage]'s only, if one is given. */
        class Bound(val keys: Keys, val kind: TextFieldKind, val packageName: String)

        companion object {
            fun bound(service: AccessibilityService, expectedPackage: String?): Bound? {
                val input = TextInserter.boundInput(service, expectedPackage) ?: return null
                val kind = TextFieldKind.of(input.editor.inputType)
                if (kind == TextFieldKind.NONE) return null
                val connection = input.connection ?: return null
                return Bound(ConnectionKeys(connection), kind, input.owner)
            }
        }
    }

    private companion object {
        /** Enough for twenty long words before the cursor. */
        const val TEXT_AROUND = 1_000
        const val VERIFY_ATTEMPTS = 6
        const val VERIFY_INTERVAL_MS = 50L

        val WHITESPACE = Regex("\\s+")

        const val NOTHING_BEFORE = "Nothing to delete before the cursor."
        const val NOT_TAKEN = "The field did not take the change."
        const val NOT_EDITABLE = "This field cannot be edited by voice."
        const val NOT_OFFERED = "This field does not offer that."
        const val UNREADABLE = "The field cannot be read just now."
    }
}
