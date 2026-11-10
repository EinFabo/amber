package app.amethyst.core.settings

import app.amethyst.core.text.ChatCleanup

/**
 * An app's own instruction as one editor holds it while it is open: the app's page in Amber,
 * or the quick menu's "Style here" window. Two editors can be open at once (the page left open
 * in Amber, the window over the app), so an editor saves only what was typed in it - never the
 * text it showed when it opened - and follows the stored instruction while nothing is typed.
 */
data class InstructionDraft(
    val text: String,
    /** Typed here since the last save: only then is there anything of this editor's to save. */
    val edited: Boolean = false,
) {

    /** What would be stored: trimmed, null when nothing is left. */
    val instruction: String? get() = text.trim().ifEmpty { null }

    /** The user typed [typed]: kept to the length the cleanup takes. */
    fun typed(typed: String): InstructionDraft = InstructionDraft(typed.take(ChatCleanup.INSTRUCTION_CHARS), edited = true)

    /**
     * The stored instruction is now [stored] (saved here or by the other editor). Followed
     * while nothing is typed here; text that already stands for it stays as typed, so a save
     * does not strip the space just typed.
     */
    fun stored(stored: String?): InstructionDraft = when {
        edited -> this
        instruction == stored -> this
        else -> InstructionDraft(stored.orEmpty())
    }

    /** Whether there is something of this editor's to save over [stored]. */
    fun needsSave(stored: String?): Boolean = edited && instruction != stored

    /** Saved (or nothing to save): what is typed from now on counts again. */
    fun saved(): InstructionDraft = copy(edited = false)

    companion object {
        fun of(stored: String?) = InstructionDraft(stored.orEmpty())
    }
}
