package app.amethyst.core.commands

import app.amethyst.core.text.TranscriptNormalizer
import java.text.Normalizer

/**
 * Fixed phrases, German and English at once - like Voice Access, not free language: a static,
 * human-written rule maps each phrase to one action. Text is folded first (case, accents, ß,
 * punctuation, fillers, "bitte", a leading "Amber"), so "Öffne WhatsApp!" and "offne whatsapp"
 * are the same command. A count ("zehnmal runter") is taken out before the rules, compound
 * words ("runterscrollen") are split, and every rule wants all the words it is given: a
 * sentence with one word it does not know is not a command.
 *
 * Nothing that sends, pays, confirms, deletes items or empties a field has a phrase here.
 */
object VoiceCommands : CommandParser {

    /** What the settings page shows as things to say, by kind; each line German · English. */
    val GROUPS = listOf(
        CommandGroup(
            "Navigate",
            listOf(
                "zurück · go back twice",
                "Startbildschirm · go home",
                "letzte Apps · recent apps",
                "Benachrichtigungen · close notifications",
                "Screenshot · lock the screen",
            ),
        ),
        CommandGroup("Apps", listOf("öffne WhatsApp · open Maps", "WhatsApp · Spotify")),
        CommandGroup(
            "Scroll & swipe",
            listOf(
                "runter · scroll up",
                "zehnmal runter · scroll down 3 times",
                "ganz nach unten · scroll to the top",
                "nächste Seite · page up",
                "wisch nach links · swipe right",
            ),
        ),
        CommandGroup(
            "Media",
            listOf("lauter · volume down", "dreimal leiser · louder twice", "Pause · play", "nächstes Lied · previous track", "Ton aus · unmute"),
        ),
        CommandGroup(
            "System",
            listOf("Taschenlampe an · flashlight off", "WLAN-Einstellungen · Bluetooth settings", "Akku · display settings", "geteilter Bildschirm · power menu"),
        ),
        CommandGroup(
            "Text",
            listOf("lösche das letzte Wort · delete 3 words", "alles markieren · copy", "ausschneiden · paste", "neue Zeile · new line"),
        ),
    )

    private val MARKS = Regex("\\p{Mn}+")
    private val APOSTROPHES = Regex("['’`´]")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** Lowercase, accents and ß folded, apostrophes dropped, every other mark a space. */
    fun normalize(text: String): String {
        val folded = MARKS.replace(Normalizer.normalize(text.lowercase().replace("ß", "ss"), Normalizer.Form.NFD), "")
        return NOT_WORD.replace(APOSTROPHES.replace(folded, ""), " ").trim()
    }

    /** Without a list of apps, so a bare name is never a command. */
    fun parse(heard: String): Understanding = parse(heard) { emptyList() }

    override fun parse(heard: String, appLabels: () -> List<String>): Understanding =
        command(read(heard), appLabels)?.let { Understanding.Understood(it) } ?: Understanding.NotUnderstood(heard)

    /** What was heard as the rules see it: folded, the count taken out, the words kept. */
    private class Reading(val normalized: String, val times: Int?, val words: List<String>) {
        /** Every folded word, the count and the fillers still in. */
        val all: List<String> get() = normalized.split(' ').filter { it.isNotEmpty() }
    }

    private fun read(heard: String): Reading {
        val normalized = normalize(heard)
        val (times, counted) = Repeats.extract(normalized.split(' ').filter { it.isNotEmpty() })
        return Reading(normalized, times, tidy(counted).flatMap(::unglue))
    }

    private fun command(reading: Reading, appLabels: () -> List<String>): Command? {
        val (times, words) = reading.times to reading.words
        if (words.isEmpty() || (times != null && times < 1)) return null
        // A bare name with a count ("zweimal WhatsApp") is not an app being opened.
        return (rule(words) ?: if (times == null) bareApp(words, appLabels) else null)?.repeated(times)
    }

    // ---- Explaining, for Diagnostics ----

    /**
     * What the rules made of [heard], line by line, for Diagnostics: the folded text, the count,
     * the words left without fillers, politeness and Amber's name, then the command - or, when
     * there is none, the most specific reason the rules can tell. The same reading as [parse].
     */
    fun explain(heard: String, appLabels: () -> List<String> = { emptyList() }): List<String> {
        val reading = read(heard)
        val command = command(reading, appLabels)
        return buildList {
            add("normalized: \"${reading.normalized}\"")
            reading.times?.let { times ->
                add(if (times > Repeats.MAX_TIMES) "count: $times, capped at ${Repeats.MAX_TIMES}" else "count: $times")
            }
            add("words: " + reading.words.joinToString(" ").ifEmpty { "(none)" })
            add(
                when {
                    command == null -> "not understood: ${reason(reading, appLabels)}"
                    rule(reading.words) == null -> "understood: ${describe(command)} (a bare name: an installed app)"
                    else -> "understood: ${describe(command)}"
                },
            )
        }
    }

    /** A command with its parameters, for Diagnostics: `Scroll DOWN ×10`, `OpenApp "whatsapp"`. */
    fun describe(command: Command): String = when (command) {
        is Command.Navigate -> "Navigate ${command.action}${times(command.times)}"
        is Command.OpenApp -> "OpenApp \"${command.name}\""
        is Command.Scroll -> "Scroll ${command.direction}${times(command.times)}"
        is Command.ScrollToEnd -> "ScrollToEnd ${command.direction}"
        is Command.Page -> "Page ${command.direction}${times(command.times)}"
        is Command.Swipe -> "Swipe ${command.direction}${times(command.times)}"
        is Command.Media -> "Media ${command.key}"
        is Command.Volume -> "Volume ${command.change}${times(command.times)}"
        is Command.Torch -> "Torch " + when (command.on) {
            true -> "on"
            false -> "off"
            null -> "toggle"
        }
        is Command.OpenSettings -> "OpenSettings ${command.page}"
        is Command.Edit -> "Edit ${command.edit}${times(command.times)}"
    }

    private fun times(n: Int) = if (n > 1) " ×$n" else ""

    /** Words that are "one" as well as an article: never taken for a forgotten count. */
    private val ARTICLE_NUMBERS = setOf("ein", "eine", "einen")

    /** Shown at most of the words a phrase had that the rules do not know there. */
    private const val MAX_NAMED_WORDS = 5

    /** Shown at most of the apps a bare name could be. */
    private const val MAX_NAMED_APPS = 5

    /** Why [reading] is no command: the most specific thing the rules can say, else "no rule matches". */
    private fun reason(reading: Reading, appLabels: () -> List<String>): String {
        val (times, words) = reading.times to reading.words
        if (words.isEmpty()) {
            return when {
                times != null -> "only a count was heard, no command"
                reading.all.isEmpty() -> "nothing left once case and punctuation are gone"
                else -> "only filler words were heard (fillers, “bitte”, Amber's name)"
            }
        }
        if (times != null && times < 1) return "a count of $times does nothing"
        countReason(words)?.let { return it }
        if (times != null) {
            rule(words)?.let {
                return "${describe(it)} does not repeat - a count works with back, scroll, page, swipe, louder, quieter and deleting words"
            }
            if (bareApp(words, appLabels) != null) return "a bare app name with a count is not opened - say “öffne …” without the count"
        }
        return motionReason(words) ?: openReason(words) ?: bareReason(words, appLabels) ?: "no rule matches"
    }

    /** A count that cannot be read ("dreißigmal"), or a number said without "mal" ("zehn runter"). */
    private fun countReason(words: List<String>): String? {
        words.firstOrNull(Repeats::unreadable)?.let {
            return "a count the rules cannot read: “$it” - counts go from one to twenty in words, any number in digits"
        }
        for ((i, word) in words.withIndex()) {
            if (word in ARTICLE_NUMBERS || Repeats.number(word) == null) continue
            val rest = words.take(i) + words.drop(i + 1)
            if (rest.isNotEmpty() && rule(rest) != null) return "the number “$word” has no “mal” or “times” after it"
        }
        return null
    }

    /** Every word a scroll, swipe or page phrase takes. Lazy: the word lists stand further down. */
    private val MOTION_WORDS by lazy {
        SCROLL_VERBS + SWIPE_VERBS + DIRECTIONS.keys + MOTION_EXTRAS + ENDS.keys + END_WORDS + PAGES + PAGE_WAYS.keys + PAGE_EXTRAS
    }

    /** A scroll, swipe or page phrase without one clear direction, or with words it does not take. */
    private fun motionReason(words: List<String>): String? {
        val pages = words.count { it in PAGES }
        val scroll = words.any { it in SCROLL_VERBS }
        val swipe = words.any { it in SWIPE_VERBS }
        val toEnd = words.any { it in ENDS || it == "ganz" } || ("all" in words && "way" in words)
        val directions = words.mapNotNull { DIRECTIONS[it] ?: ENDS[it] ?: PAGE_WAYS[it].takeIf { pages > 0 } }.distinct()
        if (!scroll && !swipe && pages == 0 && !toEnd && directions.isEmpty()) return null
        val unknown = words.filterNot { it in MOTION_WORDS }.distinct()
        val kind = when {
            swipe -> "a swipe word"
            pages > 0 -> "a page word"
            toEnd -> "a scroll to the end"
            else -> "a scroll word"
        }
        return when {
            scroll && swipe -> "a scroll word and a swipe word at once - say one of them"
            directions.size > 1 -> "more than one direction (${directions.joinToString { it.name.lowercase() }}) - say one"
            pages > 1 -> "more than one page word"
            unknown.isNotEmpty() -> (if (directions.isNotEmpty()) "a direction" else kind) +
                ", but with words the rules don't know there: " + unknown.take(MAX_NAMED_WORDS).joinToString { "“$it”" }
            directions.isEmpty() -> "$kind without a direction - say which way"
            else -> null
        }
    }

    /** "Öffne" with nothing after it, or only "die App". */
    private fun openReason(words: List<String>): String? {
        val lead = APP_LEADS.firstOrNull { startsWith(words, it) }
        val (verb, name) = when {
            lead != null -> lead to words.drop(lead.split(' ').size)
            words.last() in APP_TRAILS -> words.last() to words.dropLast(1)
            (words.first() == "mach" || words.first() == "mache") && words.last() == "auf" -> "mach … auf" to words.drop(1).dropLast(1)
            else -> return null
        }
        return if (appName(name) == null) "“$verb” without an app name" else null
    }

    /** A short phrase that might have been an app's name, and why it is none. */
    private fun bareReason(words: List<String>, appLabels: () -> List<String>): String? {
        val rest = BARE_LEADS.firstOrNull { startsWith(words, it) && words.size > it.split(' ').size }
            ?.let { words.drop(it.split(' ').size) } ?: words
        if (rest.size > MAX_NAME_WORDS) return null
        val name = appName(rest) ?: return null
        val labels = appLabels()
        if (labels.isEmpty()) return "no rule matches “$name”, and there was no app list to look it up as an app's name"
        return when (val match = AppMatch.find(name, labels, strict = true) { it }) {
            is AppMatch.Ambiguous ->
                "“$name” is the start of more than one app's name (${match.candidates.take(MAX_NAMED_APPS).joinToString()}) - say the full name"
            is AppMatch.Found -> null
            AppMatch.NotFound ->
                "no rule matches, and “$name” is not an installed app - a bare name needs an app's exact name or the unique start of one"
        }
    }

    private fun rule(words: List<String>): Command? =
        global(words) ?: motion(words) ?: media(words) ?: torch(words) ?: settingsPage(words) ?: textEdit(words) ?: openApp(words)

    /** The command [times] over, or null for a command that does not repeat. */
    private fun Command.repeated(times: Int?): Command? {
        if (times == null || times == 1) return this
        val n = times.coerceAtMost(Repeats.MAX_TIMES)
        return when (this) {
            is Command.Navigate -> if (action == GlobalAction.BACK) copy(times = n) else null
            is Command.Scroll -> copy(times = n)
            is Command.Page -> copy(times = n)
            is Command.Swipe -> copy(times = n)
            is Command.Volume -> if (change == VolumeChange.LOUDER || change == VolumeChange.QUIETER) copy(times = n) else null
            is Command.Edit -> if (edit == TextEdit.DELETE_WORD) copy(times = n) else null
            else -> null
        }
    }

    // ---- Tidying ----

    private val FILLERS = (TranscriptNormalizer.DEFAULT_FILLERS + TranscriptNormalizer.ENGLISH_ONLY_FILLERS).map(::normalize).toSet()
    private val POLITE = setOf("bitte", "please", "mal", "doch", "jetzt", "now", "noch", "nochmal", "einfach", "just")
    private val CALLS = listOf("hey amber", "hallo amber", "ok amber", "okay amber", "amber")
    private val ASKS = listOf("kannst du", "konntest du", "wurdest du", "can you", "could you", "would you", "will you")

    /** Without fillers, politeness, a call of Amber's name or a leading "can you". */
    private fun tidy(words: List<String>): List<String> {
        var rest = words.filter { it !in FILLERS && it !in POLITE }
        CALLS.firstOrNull { startsWith(rest, it) }?.let { rest = rest.drop(it.split(' ').size) }
        ASKS.firstOrNull { startsWith(rest, it) }?.let { rest = rest.drop(it.split(' ').size) }
        return rest
    }

    /** German compounds a speech engine writes as one word: "runterscrollen", "zurückgehen", "WLAN-Einstellungen". */
    private val GLUED_VERBS = listOf("scrollen", "scrolle", "blattern", "wischen", "gehen")
    private val GLUED_WAYS = setOf("runter", "herunter", "hinunter", "hoch", "rauf", "herauf", "hinauf", "weiter", "zuruck")
    private val GLUED_PAGES = setOf("wlan", "wifi", "bluetooth", "ton", "sound", "display", "anzeige", "bildschirm", "akku", "batterie", "lautstarke")

    private fun unglue(word: String): List<String> {
        for (verb in GLUED_VERBS) {
            val head = word.removeSuffix(verb)
            if (head != word && head in GLUED_WAYS) return listOf(head, verb)
        }
        val page = word.removeSuffix("einstellungen")
        if (page != word && page in GLUED_PAGES) return listOf(page, "einstellungen")
        return listOf(word)
    }

    private fun startsWith(words: List<String>, phrase: String): Boolean {
        val parts = phrase.split(' ')
        return words.size >= parts.size && words.subList(0, parts.size) == parts
    }

    // ---- Navigation: global actions ----

    /** Words a phrase may start or end with around its noun: "zeig die …", "… öffnen". */
    private val LEAD_VERBS = listOf(
        "geh zu", "gehe zu", "go to", "geh", "gehe", "go", "zeig", "zeige", "show", "offne", "oeffne", "open",
        "mach", "mache", "take", "nimm",
    )
    private val TRAIL_VERBS = setOf(
        "offnen", "oeffnen", "zeigen", "anzeigen", "machen", "aufnehmen", "erstellen", "gehen", "an", "auf",
    )
    private val ARTICLES = setOf(
        "der", "die", "das", "den", "dem", "ein", "eine", "einen", "the", "a", "an", "zum", "zur", "to", "mir", "me", "my",
        "mein", "meine",
    )

    /** "Zurück zum Startbildschirm", "back to the home screen": the way back names where to. */
    private val RETURN_LEADS = setOf("zuruck", "back")

    /** "Schließ die Benachrichtigungen", "Benachrichtigungen zu". */
    private val CLOSE_LEADS = setOf("schliess", "schliesse", "close", "dismiss", "hide")
    private val CLOSE_TRAILS = setOf("schliessen", "zumachen", "ausblenden", "zu", "weg", "close")

    private val NOUNS: Map<String, GlobalAction> = buildMap {
        fun put(action: GlobalAction, vararg phrases: String) = phrases.forEach { put(it, action) }
        put(GlobalAction.BACK, "zuruck", "zuruckgehen", "back", "schritt zuruck", "schritte zuruck", "step back", "steps back")
        put(
            GlobalAction.HOME,
            "startbildschirm", "start bildschirm", "startseite", "homebildschirm", "home bildschirm", "hauptbildschirm",
            "homescreen", "home screen", "home",
        )
        put(
            GlobalAction.RECENTS,
            "letzte apps", "letzten apps", "zuletzt verwendet", "zuletzt verwendete apps", "ubersicht", "app ubersicht",
            "multitasking", "recents", "recent apps", "overview", "app switcher",
        )
        put(GlobalAction.NOTIFICATIONS, "benachrichtigungen", "mitteilungen", "benachrichtigungsleiste", "notifications", "notification shade")
        put(GlobalAction.QUICK_SETTINGS, "schnelleinstellungen", "quick settings")
        put(GlobalAction.LOCK_SCREEN, "sperrbildschirm", "screen lock", "lock screen")
        put(GlobalAction.SCREENSHOT, "screenshot", "screen shot", "bildschirmfoto")
        put(
            GlobalAction.SPLIT_SCREEN,
            "splitscreen", "split screen", "geteilter bildschirm", "geteilten bildschirm", "geteilte ansicht", "bildschirm teilen",
            "teile bildschirm", "teil bildschirm",
        )
        put(
            GlobalAction.POWER_MENU,
            "power menu", "powermenu", "power dialog", "power options", "ein aus menu", "ausschaltmenu", "ausschalt menu",
        )
    }

    /** "Sperr das Handy", "Bildschirm sperren", "lock my phone" - in either order. */
    private val LOCK_VERBS = setOf("sperr", "sperre", "sperren", "lock")
    private val LOCK_OBJECTS = setOf("handy", "telefon", "bildschirm", "display", "gerat", "smartphone", "screen", "phone", "device")

    private fun global(words: List<String>): Command? {
        val candidates = if (words.size > 1 && words.first() in RETURN_LEADS) listOf(words, words.drop(1)) else listOf(words)
        return candidates.firstNotNullOfOrNull(::navigation)
    }

    private fun navigation(words: List<String>): Command? {
        var rest = words
        var close = false
        LEAD_VERBS.firstOrNull { startsWith(rest, it) }?.let { rest = rest.drop(it.split(' ').size) }
        // "Geh zurück zum Homescreen": the way back, after the verb, names where to.
        if (rest.size > 1 && rest.first() in RETURN_LEADS) navigation(rest.drop(1))?.let { return it }
        if (rest.size > 1 && rest.last() in TRAIL_VERBS) rest = rest.dropLast(1)
        if (rest.size > 1 && rest.last() in CLOSE_TRAILS) {
            close = true
            rest = rest.dropLast(1)
        }
        if (rest.size > 1 && rest.first() in CLOSE_LEADS) {
            close = true
            rest = rest.drop(1)
        }
        val nouns = rest.filterNot { it in ARTICLES }
        val action = NOUNS[rest.joinToString(" ")] ?: NOUNS[nouns.joinToString(" ")]
            ?: GlobalAction.LOCK_SCREEN.takeIf { isLock(nouns) }
            ?: return null
        if (!close) return Command.Navigate(action)
        val shade = action == GlobalAction.NOTIFICATIONS || action == GlobalAction.QUICK_SETTINGS
        return if (shade) Command.Navigate(GlobalAction.CLOSE_NOTIFICATIONS) else null
    }

    private fun isLock(words: List<String>): Boolean =
        words.count { it in LOCK_VERBS } == 1 && words.count { it in LOCK_OBJECTS } <= 1 && words.all { it in LOCK_VERBS || it in LOCK_OBJECTS }

    // ---- Scrolling, pages, swiping ----

    private val SCROLL_VERBS = setOf(
        "scroll", "scrolle", "scrollen", "skroll", "skrolle", "skrollen", "blattere", "blattre", "blattern", "blatter",
    )
    private val SWIPE_VERBS = setOf("wisch", "wische", "wischen", "swipe", "swipen")
    private val DIRECTIONS = mapOf(
        "unten" to Direction.DOWN, "runter" to Direction.DOWN, "herunter" to Direction.DOWN, "hinunter" to Direction.DOWN,
        "abwarts" to Direction.DOWN, "down" to Direction.DOWN, "downwards" to Direction.DOWN,
        "oben" to Direction.UP, "hoch" to Direction.UP, "rauf" to Direction.UP, "herauf" to Direction.UP, "hinauf" to Direction.UP,
        "aufwarts" to Direction.UP, "up" to Direction.UP, "upwards" to Direction.UP,
        "links" to Direction.LEFT, "left" to Direction.LEFT,
        "rechts" to Direction.RIGHT, "right" to Direction.RIGHT,
    )
    private val MOTION_EXTRAS = setOf(
        "nach", "ein", "bisschen", "stuck", "etwas", "wenig", "weiter", "a", "bit", "little", "further", "more", "some",
        "geh", "gehe", "gehen", "go",
    )

    /** "Bis zum Ende", "ganz nach unten", "to the bottom": the end a scroll goes to. */
    private val ENDS = mapOf(
        "ende" to Direction.DOWN, "end" to Direction.DOWN, "bottom" to Direction.DOWN,
        "anfang" to Direction.UP, "beginning" to Direction.UP, "top" to Direction.UP,
    )
    private val END_WORDS = setOf("ganz", "bis", "zum", "zur", "ans", "an", "den", "das", "to", "the", "all", "way", "back", "very")

    private val PAGES = setOf("seite", "seiten", "page", "pages")
    private val PAGE_WAYS = mapOf(
        "nachste" to Direction.DOWN, "nachsten" to Direction.DOWN, "next" to Direction.DOWN, "weiter" to Direction.DOWN,
        "vorherige" to Direction.UP, "vorherigen" to Direction.UP, "vorige" to Direction.UP, "vorigen" to Direction.UP,
        "previous" to Direction.UP,
    )
    private val PAGE_EXTRAS = setOf("ein", "eine", "einen", "die", "the", "a", "one", "nach")

    /** Bare directions scroll ("runter", "nach oben"); a scroll without one goes down ("scroll weiter"). */
    private fun motion(words: List<String>): Command? {
        if (words.any { it in PAGES }) return page(words)
        if (words.any { it in ENDS || it == "ganz" } || ("all" in words && "way" in words)) return toEnd(words)
        val scroll = words.any { it in SCROLL_VERBS }
        val swipe = words.any { it in SWIPE_VERBS }
        if (scroll && swipe) return null
        val directions = words.mapNotNull { DIRECTIONS[it] }.distinct()
        if (directions.size > 1) return null
        if (!words.all { it in SCROLL_VERBS || it in SWIPE_VERBS || it in DIRECTIONS || it in MOTION_EXTRAS }) return null
        val direction = directions.singleOrNull()
        return when {
            swipe -> direction?.let { Command.Swipe(it) }
            scroll -> Command.Scroll(direction ?: Direction.DOWN)
            else -> direction?.let { Command.Scroll(it) }
        }
    }

    private fun toEnd(words: List<String>): Command? {
        if (!words.all { it in SCROLL_VERBS || it in DIRECTIONS || it in ENDS || it in END_WORDS || it in MOTION_EXTRAS }) return null
        val directions = words.mapNotNull { DIRECTIONS[it] ?: ENDS[it] }.distinct()
        return directions.singleOrNull()?.let { Command.ScrollToEnd(it) }
    }

    private fun page(words: List<String>): Command? {
        if (words.count { it in PAGES } != 1) return null
        if (!words.all { it in PAGES || it in DIRECTIONS || it in PAGE_WAYS || it in PAGE_EXTRAS || it in SCROLL_VERBS || it in MOTION_EXTRAS }) return null
        val directions = words.mapNotNull { DIRECTIONS[it] ?: PAGE_WAYS[it] }.distinct()
        return directions.singleOrNull()?.let { Command.Page(it) }
    }

    // ---- Media and volume ----

    private val VOLUME_NOUNS = setOf("lautstarke", "volume", "ton", "sound", "audio", "musik", "music")
    private val SONG_NOUNS = setOf("lied", "song", "titel", "track", "stuck", "video", "wiedergabe", "playback")
    private val MEDIA_FILLERS = setOf(
        "mach", "mache", "machen", "dreh", "drehe", "stell", "stelle", "schalt", "schalte", "die", "das", "den", "der", "dieses",
        "the", "it", "this", "my", "meine", "etwas", "bisschen", "ein", "a", "bit", "little", "wieder", "again",
    )
    private val LOUDER = setOf("lauter", "louder")
    private val QUIETER = setOf("leiser", "quieter", "softer")

    /** Need a volume noun or "turn": a bare "hoch" scrolls. */
    private val RAISE = setOf("hoch", "rauf", "erhohen", "erhoehen", "hoher", "up", "increase", "raise")
    private val LOWER = setOf("runter", "senken", "verringern", "reduzieren", "niedriger", "down", "decrease", "lower", "reduce")
    private val MUTE = setOf("stumm", "stummschalten", "mute")
    private val ON = setOf("an", "on", "einschalten", "anschalten", "anmachen")
    private val OFF = setOf("aus", "off", "ausschalten", "ausmachen", "weg")
    private val PAUSE = setOf("pause", "pausieren", "pausiere", "pausier", "anhalten", "stopp", "stop", "stoppen", "stoppe")
    private val PLAY = setOf("play", "abspielen", "fortsetzen", "resume", "weiterspielen", "wiedergeben")
    private val PLAY_PARTS = PLAY + setOf("spiel", "spiele", "ab", "weiter", "continue")
    private val NEXT = setOf("nachste", "nachstes", "nachster", "nachsten", "next")
    private val SKIP = setOf("uberspringen", "skip")
    private val PREVIOUS = setOf(
        "vorherige", "vorheriges", "vorheriger", "vorigen", "voriges", "voriger", "letzte", "letztes", "letzter", "last",
        "previous", "zuruck",
    )

    private fun media(words: List<String>): Command? {
        val rest = words.filterNot { it in MEDIA_FILLERS }
        val turn = "turn" in rest
        val keys = rest.filterNot { it in VOLUME_NOUNS || it in SONG_NOUNS || it == "turn" }
        val nouns = rest.filter { it in VOLUME_NOUNS || it in SONG_NOUNS }
        val key = keys.singleOrNull()
        val sound = nouns.isNotEmpty() && nouns.all { it in VOLUME_NOUNS }
        val music = nouns.any { it == "musik" || it == "music" }
        val song = nouns.isNotEmpty() && nouns.all { it in SONG_NOUNS }
        if (keys.isEmpty()) return null
        if (nouns.isEmpty() || sound) {
            when {
                key in LOUDER -> return Command.Volume(VolumeChange.LOUDER)
                key in QUIETER -> return Command.Volume(VolumeChange.QUIETER)
                key in RAISE && (sound || turn) -> return Command.Volume(VolumeChange.LOUDER)
                key in LOWER && (sound || turn) -> return Command.Volume(VolumeChange.QUIETER)
                key in MUTE || keys == listOf("stumm", "schalten") -> return Command.Volume(VolumeChange.MUTE)
                key == "unmute" || keys == listOf("stummschaltung", "aufheben") -> return Command.Volume(VolumeChange.UNMUTE)
                key in OFF && sound -> return if (music) Command.Media(MediaKey.PAUSE) else Command.Volume(VolumeChange.MUTE)
                key in ON && sound -> return if (music) Command.Media(MediaKey.PLAY) else Command.Volume(VolumeChange.UNMUTE)
            }
        }
        if (turn) return null
        return when {
            key in PAUSE -> Command.Media(MediaKey.PAUSE)
            keys.all { it in PLAY_PARTS } && (keys.any { it in PLAY } || "ab" in keys || nouns.isNotEmpty()) -> Command.Media(MediaKey.PLAY)
            key in SKIP || (key in NEXT && song) -> Command.Media(MediaKey.NEXT)
            key in PREVIOUS && song -> Command.Media(MediaKey.PREVIOUS)
            else -> null
        }
    }

    // ---- System: flashlight, settings pages ----

    private val TORCHES = setOf("taschenlampe", "lampe", "flashlight", "torch", "blitzlicht")
    private val SWITCH_FILLERS = setOf("mach", "mache", "schalt", "schalte", "schalten", "turn", "switch", "die", "das", "the", "my", "meine")

    private fun torch(words: List<String>): Command? {
        val rest = words.filterNot { it in SWITCH_FILLERS }
        if (rest.count { it in TORCHES } != 1) return null
        val switches = rest.filterNot { it in TORCHES }
        return when {
            switches.isEmpty() -> Command.Torch(null)
            switches.size > 1 -> null
            switches.single() in ON || switches.single() == "ein" -> Command.Torch(true)
            switches.single() in OFF -> Command.Torch(false)
            else -> null
        }
    }

    /** Named alone they open their page; switching them is never Amber's - "WLAN aus" opens the page too. */
    private val PAGE_NAMES = mapOf(
        "wlan" to SettingsPage.WIFI, "wifi" to SettingsPage.WIFI,
        "bluetooth" to SettingsPage.BLUETOOTH,
        "display" to SettingsPage.DISPLAY, "helligkeit" to SettingsPage.DISPLAY, "brightness" to SettingsPage.DISPLAY,
        "akku" to SettingsPage.BATTERY, "batterie" to SettingsPage.BATTERY, "battery" to SettingsPage.BATTERY,
    )

    /** Too common alone ("Ton aus" mutes): a page only with "Einstellungen" or "settings". */
    private val PAGE_TOPICS = mapOf(
        "ton" to SettingsPage.SOUND, "sound" to SettingsPage.SOUND, "lautstarke" to SettingsPage.SOUND, "audio" to SettingsPage.SOUND,
        "anzeige" to SettingsPage.DISPLAY, "bildschirm" to SettingsPage.DISPLAY,
    )
    private val SETTINGS = setOf("einstellungen", "einstellung", "settings", "setting")
    private val SETTINGS_FILLERS = setOf(
        "offne", "oeffne", "open", "offnen", "oeffnen", "zeig", "zeige", "show", "zeigen", "anzeigen", "geh", "gehe", "go",
        "zu", "to", "der", "die", "das", "den", "the", "my", "meine", "fur", "for", "mir", "me", "menu", "page",
    )
    private val RADIO_SWITCHES = ON + OFF + setOf("ein")
    private val RADIOS = setOf(SettingsPage.WIFI, SettingsPage.BLUETOOTH)

    private fun settingsPage(words: List<String>): Command? {
        val rest = words.joinToString(" ").replace("wi fi", "wifi").replace("w lan", "wlan").split(' ')
        val named = rest.filter { it in PAGE_NAMES || it in PAGE_TOPICS }
        val page = named.singleOrNull()?.let { PAGE_NAMES[it] ?: PAGE_TOPICS[it] } ?: return null
        val asked = rest.any { it in SETTINGS }
        if (named.single() in PAGE_TOPICS && !asked) return null
        val others = rest.filterNot { it in named || it in SETTINGS || it in SETTINGS_FILLERS }
        val switching = others.isNotEmpty() && others.all { it in RADIO_SWITCHES } && page in RADIOS && others.size == 1
        return if (others.isEmpty() || switching) Command.OpenSettings(page) else null
    }

    // ---- Text in the field being written in ----

    private val DELETE_VERBS = setOf("losche", "losch", "loschen", "loesche", "loeschen", "entferne", "entfern", "entfernen", "delete", "remove", "erase")
    private val WORDS = setOf("wort", "worter", "worte", "word", "words")
    private val DELETE_EXTRAS = setOf("das", "die", "den", "the", "letzte", "letztes", "letzten", "last", "mein", "my", "meine")

    private val ALL = setOf("alles", "all", "everything")
    private val SELECT_VERBS = setOf("markieren", "markiere", "markier", "auswahlen", "auswaehlen", "wahle", "waehle", "wahl", "select", "mark", "highlight")
    private val OBJECT_WORDS = setOf("das", "es", "den", "die", "the", "it", "that", "this", "text", "hier", "here")
    private val NEW_LINES = setOf("neue zeile", "nachste zeile", "zeilenumbruch", "new line", "newline", "line break", "next line")

    private fun textEdit(words: List<String>): Command? {
        val edit = when {
            words.any { it in DELETE_VERBS } && words.count { it in WORDS } == 1 &&
                words.all { it in DELETE_VERBS || it in WORDS || it in DELETE_EXTRAS } -> TextEdit.DELETE_WORD
            words.any { it in ALL } && words.any { it in SELECT_VERBS } &&
                words.all { it in ALL || it in SELECT_VERBS || it in OBJECT_WORDS || it == "aus" } -> TextEdit.SELECT_ALL
            else -> clipboard(words.filterNot { it in OBJECT_WORDS })
                ?: TextEdit.NEW_LINE.takeIf { words.filterNot { it in ARTICLES }.joinToString(" ") in NEW_LINES }
        }
        return edit?.let { Command.Edit(it) }
    }

    /** "Kopieren", "schneide das aus", "füge ein", "paste it" - the object words already gone. */
    private fun clipboard(words: List<String>): TextEdit? = when (words.toSet()) {
        setOf("kopieren"), setOf("kopiere"), setOf("kopier"), setOf("copy") -> TextEdit.COPY
        setOf("ausschneiden"), setOf("schneide", "aus"), setOf("schneid", "aus"), setOf("cut") -> TextEdit.CUT
        setOf("einfugen"), setOf("einfuegen"), setOf("fuge", "ein"), setOf("fug", "ein"), setOf("fuege", "ein"), setOf("paste") -> TextEdit.PASTE
        else -> null
    }

    // ---- Apps ----

    private val APP_LEADS = listOf("offne", "oeffne", "starte", "open", "launch", "start", "run", "geh zu", "gehe zu", "go to", "wechsel zu", "wechsle zu", "switch to")
    private val APP_TRAILS = setOf("offnen", "oeffnen", "starten", "aufmachen")

    /** "Zu WhatsApp", "geh auf WhatsApp": a bare name, which must surely be an app. */
    private val BARE_LEADS = listOf("geh auf", "gehe auf", "zu", "auf", "to")

    /** A launcher label longer than this is rare; a longer sentence is not a name. */
    private const val MAX_NAME_WORDS = 4

    private fun openApp(words: List<String>): Command? {
        val name = when {
            (words.first() == "mach" || words.first() == "mache") && words.last() == "auf" -> words.drop(1).dropLast(1)
            words.size > 1 && words.last() in APP_TRAILS -> words.dropLast(1)
            else -> APP_LEADS.firstOrNull { startsWith(words, it) }?.let { words.drop(it.split(' ').size) } ?: return null
        }
        return appName(name)?.let { Command.OpenApp(it) }
    }

    /**
     * A name said without "open" ("WhatsApp", "zu WhatsApp") opens an app only when it is one's
     * exact name or the unique start of one - otherwise any short sentence would open something.
     */
    private fun bareApp(words: List<String>, appLabels: () -> List<String>): Command? {
        val rest = BARE_LEADS.firstOrNull { startsWith(words, it) && words.size > it.split(' ').size }
            ?.let { words.drop(it.split(' ').size) } ?: words
        val name = appName(rest)?.takeIf { rest.size <= MAX_NAME_WORDS } ?: return null
        return Command.OpenApp(name).takeIf { AppMatch.find(name, appLabels(), strict = true) { it } is AppMatch.Found }
    }

    /** Without articles in front and the word "app" around it: "die App WhatsApp", "the Gmail app". */
    private fun appName(words: List<String>): String? {
        var trimmed = words.dropWhile { it in ARTICLES }
        if (trimmed.firstOrNull() == "app") trimmed = trimmed.drop(1)
        if (trimmed.size > 1 && trimmed.last() == "app") trimmed = trimmed.dropLast(1)
        return trimmed.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }
}
