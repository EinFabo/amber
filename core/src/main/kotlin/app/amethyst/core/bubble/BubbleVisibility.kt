package app.amethyst.core.bubble

import app.amethyst.core.settings.BubbleSettings

/** Whether the bubble shows right now. */
object BubbleVisibility {

    /**
     * The pulled-down notification shade or quick settings ([shadeOpen], [SystemShade]) never
     * get it: the bubble is an overlay above every window, the shade included, and lay on the
     * notifications' text (Pixel 9a, 2026-11-22). Not even while dictating - the recording's
     * own notification is right there with its Stop, and the bubble is back with the shade
     * gone. Otherwise a running dictation keeps its bubble wherever it is; the lock screen and
     * an app on the hidden list never get it. Then "only with a text field" decides - unless
     * voice commands are on and the user wants the bubble for them everywhere: "open WhatsApp"
     * and "go back" are wanted exactly where no text field is.
     */
    fun wanted(
        dictating: Boolean,
        currentApp: String?,
        settings: BubbleSettings,
        textFieldFocused: Boolean,
        commandsOn: Boolean,
        locked: Boolean = false,
        shadeOpen: Boolean = false,
    ): Boolean {
        if (shadeOpen) return false
        if (dictating) return true
        if (locked) return false
        if (currentApp != null && currentApp in settings.hiddenIn) return false
        return !settings.onlyWithTextField || textFieldFocused || (commandsOn && settings.everywhereForCommands)
    }
}
