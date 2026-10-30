package app.amethyst.core.bubble

import app.amethyst.core.settings.BubbleSettings

/** Whether the bubble shows right now. */
object BubbleVisibility {

    /**
     * A running dictation keeps its bubble wherever it is; an app on the hidden list never gets
     * it. Otherwise "only with a text field" decides - unless voice commands are on: "open
     * WhatsApp" and "go back" are wanted exactly where no text field is.
     */
    fun wanted(dictating: Boolean, currentApp: String?, settings: BubbleSettings, textFieldFocused: Boolean, commandsOn: Boolean): Boolean {
        if (dictating) return true
        if (currentApp != null && currentApp in settings.hiddenIn) return false
        return !settings.onlyWithTextField || textFieldFocused || commandsOn
    }
}
