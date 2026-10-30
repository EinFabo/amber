package app.amethyst.core.commands

import app.amethyst.core.text.TranscriptNormalizer
import java.text.Normalizer

/** Which way the content moves for a scroll, or the finger for a swipe. */
enum class Direction { UP, DOWN, LEFT, RIGHT }

/** What Android does by itself on request - `AccessibilityService.performGlobalAction`. */
enum class GlobalAction { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, LOCK_SCREEN, SCREENSHOT }

/** One action a spoken command asks for. Nothing here reads or taps what is on the screen. */
sealed interface Command {
    data class Navigate(val action: GlobalAction) : Command

    /** [name] as heard, folded; resolved against the installed apps by [AppMatch]. */
    data class OpenApp(val name: String) : Command

    /** The content moves so that what lies in [direction] comes into view ("scroll down"). */
    data class Scroll(val direction: Direction) : Command

    /** A finger stroke towards [direction] across the middle of the screen ("swipe left"). */
    data class Swipe(val direction: Direction) : Command
}

sealed interface Understanding {
    data class Understood(val command: Command) : Understanding
    data class NotUnderstood(val heard: String) : Understanding
}

/** Turns what was heard into a command. Rules today; a model could answer the same question. */
fun interface CommandParser {
    fun parse(heard: String): Understanding
}

/** How a command ended, in words for the user. */
sealed interface CommandOutcome {
    val message: String

    data class Done(override val message: String) : CommandOutcome

    /** Not done on purpose: switched off, phone locked. */
    data class Refused(override val message: String) : CommandOutcome

    /** Tried and could not: no such app, nothing to scroll. */
    data class Failed(override val message: String) : CommandOutcome
}

/**
 * Fixed phrases, German and English at once - like Voice Access, not free language: a static,
 * human-written rule maps each phrase to one action. Text is folded first (case, accents, ß,
 * punctuation, fillers, "bitte", a leading "Amber"), so "Öffne WhatsApp!" and "offne whatsapp"
 * are the same command.
 */
object VoiceCommands : CommandParser {

    /** What the settings page shows as things to say. */
    val EXAMPLES = listOf(
        "zurück · go back",
        "Startbildschirm · go home",
        "letzte Apps · recent apps",
        "Benachrichtigungen · notifications",
        "öffne WhatsApp · open Maps",
        "scroll runter · scroll up",
        "wisch nach links · swipe right",
        "Screenshot · lock the screen",
    )

    private val MARKS = Regex("\\p{Mn}+")
    private val APOSTROPHES = Regex("['’`´]")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** Lowercase, accents and ß folded, apostrophes dropped, every other mark a space. */
    fun normalize(text: String): String {
        val folded = MARKS.replace(Normalizer.normalize(text.lowercase().replace("ß", "ss"), Normalizer.Form.NFD), "")
        return NOT_WORD.replace(APOSTROPHES.replace(folded, ""), " ").trim()
    }

    private val FILLERS = (TranscriptNormalizer.DEFAULT_FILLERS + TranscriptNormalizer.ENGLISH_ONLY_FILLERS).map(::normalize).toSet()
    private val POLITE = setOf("bitte", "please", "mal", "doch", "jetzt", "now")
    private val CALLS = listOf("hey amber", "hallo amber", "ok amber", "okay amber", "amber")
    private val ASKS = listOf("kannst du", "konntest du", "wurdest du", "can you", "could you", "would you", "will you")

    /** Words a phrase may start or end with around its noun: "zeig die …", "… öffnen". */
    private val LEAD_VERBS = listOf(
        "geh zu", "gehe zu", "go to", "geh", "gehe", "go", "zeig", "zeige", "show", "offne", "oeffne", "open",
        "mach", "mache", "take", "nimm",
    )
    private val TRAIL_VERBS = setOf("offnen", "oeffnen", "zeigen", "anzeigen", "machen", "aufnehmen", "gehen", "an", "auf")
    private val ARTICLES = setOf("der", "die", "das", "den", "dem", "ein", "eine", "einen", "the", "a", "an", "zum", "zur", "to", "mir", "me", "my", "mein", "meine")

    private val NOUNS: Map<String, GlobalAction> = buildMap {
        fun put(action: GlobalAction, vararg phrases: String) = phrases.forEach { put(it, action) }
        put(GlobalAction.BACK, "zuruck", "zuruckgehen", "back")
        put(GlobalAction.HOME, "startbildschirm", "startseite", "homescreen", "home screen", "home")
        put(
            GlobalAction.RECENTS,
            "letzte apps", "letzten apps", "zuletzt verwendet", "zuletzt verwendete apps", "ubersicht", "app ubersicht",
            "recents", "recent apps", "overview", "app switcher",
        )
        put(GlobalAction.NOTIFICATIONS, "benachrichtigungen", "mitteilungen", "notifications")
        put(GlobalAction.QUICK_SETTINGS, "schnelleinstellungen", "quick settings")
        put(
            GlobalAction.LOCK_SCREEN,
            "sperren", "bildschirm sperren", "handy sperren", "telefon sperren", "sperrbildschirm",
            "lock", "lock screen", "lock phone", "screen lock",
        )
        put(GlobalAction.SCREENSHOT, "screenshot", "bildschirmfoto")
    }

    private val SCROLL_VERBS = setOf(
        "scroll", "scrolle", "scrollen", "skroll", "skrolle", "skrollen", "blattere", "blattern", "blatter",
    )
    private val SWIPE_VERBS = setOf("wisch", "wische", "wischen", "swipe", "swipen")
    private val DIRECTIONS = mapOf(
        "unten" to Direction.DOWN, "runter" to Direction.DOWN, "abwarts" to Direction.DOWN, "down" to Direction.DOWN,
        "oben" to Direction.UP, "hoch" to Direction.UP, "rauf" to Direction.UP, "aufwarts" to Direction.UP, "up" to Direction.UP,
        "links" to Direction.LEFT, "left" to Direction.LEFT,
        "rechts" to Direction.RIGHT, "right" to Direction.RIGHT,
    )
    private val MOTION_EXTRAS = setOf("nach", "ein", "bisschen", "stuck", "etwas", "weiter", "a", "bit", "little", "further", "more")

    private val APP_LEADS = listOf("offne", "oeffne", "starte", "open", "launch", "start", "run", "geh zu", "gehe zu", "go to", "wechsel zu", "switch to")
    private val APP_TRAILS = setOf("offnen", "oeffnen", "starten")

    override fun parse(heard: String): Understanding {
        val words = tidy(normalize(heard))
        val command = words.takeIf { it.isNotEmpty() }?.let { global(it) ?: motion(it) ?: openApp(it) }
        return command?.let { Understanding.Understood(it) } ?: Understanding.NotUnderstood(heard)
    }

    /** Without fillers, politeness, a call of Amber's name or a leading "can you". */
    private fun tidy(normalized: String): List<String> {
        var words = normalized.split(' ').filter { it.isNotEmpty() && it !in FILLERS && it !in POLITE }
        CALLS.firstOrNull { startsWith(words, it) }?.let { words = words.drop(it.split(' ').size) }
        ASKS.firstOrNull { startsWith(words, it) }?.let { words = words.drop(it.split(' ').size) }
        return words
    }

    private fun startsWith(words: List<String>, phrase: String): Boolean {
        val parts = phrase.split(' ')
        return words.size >= parts.size && words.subList(0, parts.size) == parts
    }

    private fun global(words: List<String>): Command? {
        var rest = words
        LEAD_VERBS.firstOrNull { startsWith(rest, it) }?.let { rest = rest.drop(it.split(' ').size) }
        if (rest.size > 1 && rest.last() in TRAIL_VERBS) rest = rest.dropLast(1)
        val noun = rest.filterNot { it in ARTICLES }.joinToString(" ")
        return NOUNS[noun]?.let { Command.Navigate(it) }
    }

    private fun motion(words: List<String>): Command? {
        val scroll = words.any { it in SCROLL_VERBS }
        val swipe = words.any { it in SWIPE_VERBS }
        if (scroll == swipe) return null
        val directions = words.mapNotNull { DIRECTIONS[it] }.distinct()
        if (directions.size != 1) return null
        val others = words.filterNot { it in SCROLL_VERBS || it in SWIPE_VERBS || it in DIRECTIONS || it in MOTION_EXTRAS }
        if (others.isNotEmpty()) return null
        return if (scroll) Command.Scroll(directions.single()) else Command.Swipe(directions.single())
    }

    private fun openApp(words: List<String>): Command? {
        val name = when {
            (words.first() == "mach" || words.first() == "mache") && words.last() == "auf" -> words.drop(1).dropLast(1)
            words.size > 1 && words.last() in APP_TRAILS -> words.dropLast(1)
            else -> APP_LEADS.firstOrNull { startsWith(words, it) }?.let { words.drop(it.split(' ').size) } ?: return null
        }
        var trimmed = name.dropWhile { it in ARTICLES }
        if (trimmed.firstOrNull() == "app") trimmed = trimmed.drop(1)
        if (trimmed.size > 1 && trimmed.last() == "app") trimmed = trimmed.dropLast(1)
        return trimmed.takeIf { it.isNotEmpty() }?.let { Command.OpenApp(it.joinToString(" ")) }
    }
}
