package app.amethyst.core.bubble

import app.amethyst.core.settings.BubbleSettings

/** Whether the bubble shows right now. */
object BubbleVisibility {

    /**
     * A running dictation keeps its bubble wherever it is; the lock screen and an app on the
     * hidden list never get it otherwise. Then "only with a text field" decides - unless voice
     * commands are on and the user wants the bubble for them everywhere: "open WhatsApp" and
     * "go back" are wanted exactly where no text field is.
     */
    fun wanted(
        dictating: Boolean,
        currentApp: String?,
        settings: BubbleSettings,
        textFieldFocused: Boolean,
        commandsOn: Boolean,
        locked: Boolean = false,
    ): Boolean {
        if (dictating) return true
        if (locked) return false
        if (currentApp != null && currentApp in settings.hiddenIn) return false
        return !settings.onlyWithTextField || textFieldFocused || (commandsOn && settings.everywhereForCommands)
    }
}
