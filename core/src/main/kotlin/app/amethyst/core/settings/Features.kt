package app.amethyst.core.settings

/**
 * What this build offers, in one place. A feature that is out keeps its code and its stored
 * settings; it is only hidden from the app and never acts.
 */
object Features {

    /**
     * Voice commands from the bubble ("open WhatsApp", "go back"). Out for now: the app is
     * kept simple, and the commands are the base of a later phone remote-control mode. While
     * out, [AmethystSettings.commandsEnabled] is false whatever [CommandSettings.enabled] says,
     * so a user who had them on gets plain dictation - their switch is kept for later.
     */
    const val VOICE_COMMANDS: Boolean = false
}
