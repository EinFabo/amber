package app.amethyst.core.commands

/** Which way the content moves for a scroll, or the finger for a swipe. */
enum class Direction { UP, DOWN, LEFT, RIGHT }

/** What Android does by itself on request - `AccessibilityService.performGlobalAction`. */
enum class GlobalAction {
    BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS,

    /** The notification shade closed again (Back below Android 12). */
    CLOSE_NOTIFICATIONS,
    LOCK_SCREEN, SCREENSHOT, SPLIT_SCREEN, POWER_MENU,
}

/** A media button, as a headset sends it to whatever is playing. */
enum class MediaKey { PLAY, PAUSE, NEXT, PREVIOUS }

/** The media volume - music, videos - with the system's own volume panel. */
enum class VolumeChange { LOUDER, QUIETER, MUTE, UNMUTE }

/** A page of Android's settings; opened, never switched from here. */
enum class SettingsPage { WIFI, BLUETOOTH, SOUND, DISPLAY, BATTERY }

/** A change to the field being written in. Deleting a whole field is not one of them. */
enum class TextEdit { DELETE_WORD, SELECT_ALL, COPY, CUT, PASTE, NEW_LINE }

/**
 * One action a spoken command asks for. Nothing here reads or taps what is on the screen; the
 * text edits read only the field being written in. [times] is how often a repeatable command
 * runs, at most [Repeats.MAX_TIMES].
 */
sealed interface Command {
    /** [times] only ever counts for [GlobalAction.BACK]. */
    data class Navigate(val action: GlobalAction, val times: Int = 1) : Command

    /** [name] as heard, folded; resolved against the installed apps by [AppMatch]. */
    data class OpenApp(val name: String) : Command

    /** The content moves so that what lies in [direction] comes into view ("scroll down"). */
    data class Scroll(val direction: Direction, val times: Int = 1) : Command

    /** Scrolls until nothing moves any more, at most [Repeats.MAX_TO_END] steps. */
    data class ScrollToEnd(val direction: Direction) : Command

    /** A screenful: the page action where a list offers one, else a scroll. */
    data class Page(val direction: Direction, val times: Int = 1) : Command

    /** A finger stroke towards [direction] across the middle of the screen ("swipe left"). */
    data class Swipe(val direction: Direction, val times: Int = 1) : Command

    data class Media(val key: MediaKey) : Command

    /** [times] only ever counts for louder and quieter. */
    data class Volume(val change: VolumeChange, val times: Int = 1) : Command

    /** [on] null: the other way round from now. */
    data class Torch(val on: Boolean?) : Command

    data class OpenSettings(val page: SettingsPage) : Command

    /** [times] only ever counts for [TextEdit.DELETE_WORD]: that many words. */
    data class Edit(val edit: TextEdit, val times: Int = 1) : Command
}

sealed interface Understanding {
    data class Understood(val command: Command) : Understanding
    data class NotUnderstood(val heard: String) : Understanding
}

/**
 * Turns what was heard into a command. Rules today; a model could answer the same question.
 * [appLabels] are the installed apps' names, asked for only when a bare name might be one.
 */
fun interface CommandParser {
    fun parse(heard: String, appLabels: () -> List<String>): Understanding
}

/** How a command ended, in words for the user. */
sealed interface CommandOutcome {
    val message: String

    data class Done(override val message: String) : CommandOutcome

    /** Not done on purpose: switched off, phone locked, a password field. */
    data class Refused(override val message: String) : CommandOutcome

    /** Tried and could not: no such app, nothing to scroll. */
    data class Failed(override val message: String) : CommandOutcome
}

/** The settings page's list of things to say, under one [title] per kind. */
data class CommandGroup(val title: String, val examples: List<String>)
