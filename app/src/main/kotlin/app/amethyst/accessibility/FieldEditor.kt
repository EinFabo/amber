package app.amethyst.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import app.amethyst.core.bubble.TextFieldKind
import app.amethyst.core.commands.CommandOutcome
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
 * message.
 */
class FieldEditor(private val service: AccessibilityService, private val inserter: TextInserter) {

    suspend fun apply(edit: TextEdit, times: Int): CommandOutcome {
        val field = find() ?: return CommandOutcome.Failed("No text field has the cursor - tap into one first.")
        if (field.secret) return CommandOutcome.Refused("Not in a password field.")
        return when (edit) {
            TextEdit.DELETE_WORD -> deleteWords(field, times)
            TextEdit.SELECT_ALL -> selectAll(field)
            TextEdit.COPY -> clipboard(field, AccessibilityNodeInfo.ACTION_COPY, android.R.id.copy, "Copied", selection = true)
            TextEdit.CUT -> clipboard(field, AccessibilityNodeInfo.ACTION_CUT, android.R.id.cut, "Cut", selection = true)
            TextEdit.PASTE -> clipboard(field, AccessibilityNodeInfo.ACTION_PASTE, android.R.id.paste, "Pasted", selection = false)
            TextEdit.NEW_LINE -> newLine(field)
        }
    }

    /** The field with the cursor: its node, and from Android 13 the input connection bound to it. */
    private class Field(val node: AccessibilityNodeInfo?, val keys: Keys?, val secret: Boolean, val takesLineBreaks: Boolean)

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
        return Field(node, bound?.keys, secret, inserter.takesLineBreaks(packageName, node))
    }

    private suspend fun deleteWords(field: Field, count: Int): CommandOutcome {
        val did = if (count == 1) "Deleted the last word" else "Deleted $count words"
        val keys = field.keys
        val before = keys?.surrounding()
        if (keys != null && before != null) {
            val chars = TextEdits.charsBeforeCursor(before.state, count)
            if (chars == 0) return CommandOutcome.Failed(NOTHING_BEFORE)
            keys.deleteBefore(chars)
            val changed = verified(keys) { after -> after.beforeCursor() != before.beforeCursor() }
            return if (changed) CommandOutcome.Done(did) else CommandOutcome.Failed(NOT_TAKEN)
        }
        val node = field.node ?: return CommandOutcome.Failed(NOT_EDITABLE)
        val state = read(node) ?: return CommandOutcome.Failed(UNREADABLE)
        val result = TextEdits.deleteWords(state, count)
        if (result == state) return CommandOutcome.Failed(NOTHING_BEFORE)
        return write(node, result, did)
    }

    /**
     * Where Enter would send or move on, a line break is not typed: it might do just that. A
     * selection stays - the break goes behind it, not over it ([TextEdits.lineBreakPlan]) - and
     * "New line" is said only once the break is there.
     */
    private suspend fun newLine(field: Field): CommandOutcome {
        if (!field.takesLineBreaks) return CommandOutcome.Refused("Enter sends or moves on in this field - no new line here.")
        val keys = field.keys
        val before = keys?.surrounding()
        if (keys != null && before != null) {
            val plan = TextEdits.lineBreakPlan(before.state, before.offset)
            when (plan) {
                LineBreakPlan.AtCursor -> keys.type("\n")
                is LineBreakPlan.After -> {
                    keys.select(plan.position, plan.position)
                    keys.type("\n")
                }
                // The connection cannot tell where the selection ends in the field; the node can.
                LineBreakPlan.Unknown -> return newLineByNode(field)
                    ?: CommandOutcome.Failed("This field does not say where the selection ends - tap where the new line goes.")
            }
            if (verified(keys) { after -> TextEdits.lineBreakLanded(before.state, after.state) }) return CommandOutcome.Done("New line")
            // Typed but not taken: the selection as it was, not collapsed by the move.
            if (plan is LineBreakPlan.After) {
                keys.select(before.offset + before.state.selectionStart, before.offset + before.state.selectionEnd)
            }
            return CommandOutcome.Failed(NOT_TAKEN)
        }
        return newLineByNode(field) ?: CommandOutcome.Failed(NOT_EDITABLE)
    }

    /** The node's way: the whole text with the break in it. Null without a node. */
    private fun newLineByNode(field: Field): CommandOutcome? {
        val node = field.node ?: return null
        val state = read(node) ?: return CommandOutcome.Failed(UNREADABLE)
        return write(node, TextEdits.newLine(state), "New line")
    }

    private fun selectAll(field: Field): CommandOutcome {
        val node = field.node
        if (node != null && node.offers(AccessibilityNodeInfo.ACTION_SET_SELECTION)) {
            val length = read(node)?.text?.length ?: return CommandOutcome.Failed(UNREADABLE)
            return if (select(node, 0, length)) CommandOutcome.Done("Selected all") else CommandOutcome.Failed(NOT_TAKEN)
        }
        val keys = field.keys ?: return CommandOutcome.Failed(NOT_OFFERED)
        keys.contextMenu(android.R.id.selectAll)
        return CommandOutcome.Done("Selected all")
    }

    /**
     * Copy, cut and paste: the node's own action where the field offers it - it says whether it
     * worked, and Compose fields know these only as node actions - else the input connection's.
     */
    private fun clipboard(field: Field, nodeAction: Int, menuItem: Int, done: String, selection: Boolean): CommandOutcome {
        val node = field.node
        if (selection) {
            val state = node?.let(::read) ?: field.keys?.surrounding()?.state
            if (state != null && state.selectionStart == state.selectionEnd) {
                return CommandOutcome.Failed("Nothing is selected - say “select all” first.")
            }
        }
        if (node != null && node.offers(nodeAction)) {
            return if (node.performAction(nodeAction)) CommandOutcome.Done(done) else CommandOutcome.Failed(NOT_TAKEN)
        }
        val keys = field.keys ?: return CommandOutcome.Failed(NOT_OFFERED)
        keys.contextMenu(menuItem)
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

    private fun write(node: AccessibilityNodeInfo, result: FieldState, done: String): CommandOutcome {
        val text = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, result.text) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text)) return CommandOutcome.Failed(NOT_TAKEN)
        select(node, result.selectionStart, result.selectionEnd)
        return if (read(node)?.text == result.text) CommandOutcome.Done(done) else CommandOutcome.Failed(NOT_TAKEN)
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

        const val NOTHING_BEFORE = "Nothing to delete before the cursor."
        const val NOT_TAKEN = "The field did not take the change."
        const val NOT_EDITABLE = "This field cannot be edited by voice."
        const val NOT_OFFERED = "This field does not offer that."
        const val UNREADABLE = "The field cannot be read just now."
    }
}
