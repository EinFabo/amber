package app.amethyst.core.commands

import app.amethyst.core.text.TranscriptNormalizer
import java.text.Normalizer

/**
 * Fixed phrases, German and English at once - like Voice Access, not free language: a static,
 * human-written rule maps each phrase to one action. Text is folded first (case, accents, ß,
 * punctuation, fillers, "bitte", a leading "Amber"), so "Öffne WhatsApp!" and "offne whatsapp"
 * are the same command. A count ("zehnmal runter") is taken out before the rules, compound
 * words ("runterscrollen") are split, and every rule wants all the words it is given: a
 * sentence with one word it does not know is not a command - save a single unknown word right
 * before an installed app's exact name, taken for a misheard "öffne" ("Erfner WhatsApp").
 *
 * Up to [MAX_STEPS] commands may come in one go, in their order: cut at joining words ("und
 * dann", "then") and sentence marks, a piece without one cut into the longest commands from its
 * start ("select all copy"), each with its own count. Every part must be a command, or nothing
 * runs; a bare name and a misheard "öffne" stay rules for one command alone.
 *
 * Nothing that sends, pays, confirms, deletes items or empties a field has a phrase here.
 */
object VoiceCommands : CommandParser {

    /** At most this many commands in one go ("alles markieren und kopieren"). */
    const val MAX_STEPS = 5

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
        CommandGroup("Several at once", listOf("alles markieren und kopieren · select all, then copy")),
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

    override fun parse(heard: String, appLabels: () -> List<String>): Understanding = understand(heard, appLabels, guessMisheard = true)

    /**
     * [parse] without the guess that one unknown word before an app's name is a misheard
     * "öffne" ([misheardOpen]) - for choosing among a command's language passes
     * ([CommandLanguages]), where a later pass must not turn a refused phrase into an open.
     * Several commands in one go never take that guess, so they read the same in both.
     */
    fun parseStrict(heard: String, appLabels: () -> List<String>): Understanding = understand(heard, appLabels, guessMisheard = false)

    private fun understand(heard: String, appLabels: () -> List<String>, guessMisheard: Boolean): Understanding {
        val single = command(read(heard), appLabels, guessMisheard)
        val pieces = pieces(heard)
        // One piece that is one command reads as it always did, without looking for more.
        val commands = if (single != null && pieces.size < 2) listOf(single) else decide(single, combo(pieces))
        return commands?.let { Understanding.Understood(it) } ?: Understanding.NotUnderstood(heard)
    }

    /**
     * One command, or several. Where joining words or sentence marks split the words, several
     * commands that read cleanly win over one that would swallow the rest ("öffne WhatsApp und
     * zurück" is no app called "whatsapp und zuruck"); else the one command stands.
     */
    private fun decide(single: Command?, combo: Combo): List<Command>? = when {
        single != null && combo.pieces < 2 -> listOf(single)
        else -> combo.commands ?: single?.let(::listOf)
    }

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

    private fun command(reading: Reading, appLabels: () -> List<String>, guessMisheard: Boolean = true): Command? {
        val (times, words) = reading.times to reading.words
        if (words.isEmpty() || (times != null && times < 1)) return null
        // A bare name with a count ("zweimal WhatsApp") is not an app being opened.
        val app = if (times != null) null else bareApp(words, appLabels) ?: misheardOpen(words, appLabels).takeIf { guessMisheard }
        return (rule(words) ?: app)?.repeated(times)
    }

    // ---- Several commands in one go ----

    /**
     * Words that join commands, longest first: "und dann" before "und". Whatever is said, the
     * pieces between them must each be commands, or none of them runs.
     */
    private val JOINERS = listOf("und dann", "and then", "after that", "und", "dann", "danach", "anschliessend", "and", "then")
        .map { it.split(' ') }

    /** What a speech engine ends a sentence or a clause with: each mark cuts the commands apart too. */
    private val SENTENCE_MARKS = Regex("[,.;!?]")

    /** One command among several: the folded words it was read from, and what they mean. */
    private class Part(val words: List<String>, val command: Command)

    /**
     * What reading the words as several commands found. [pieces] is how many pieces the joining
     * words and sentence marks cut it into, fillers alone not counted; [parts] the commands
     * found, in order; [stuck] the words at which no command could be found, if any.
     */
    private class Combo(val pieces: Int, val joined: Boolean, val parts: List<Part>, val stuck: List<String>?) {

        /** Said as several - with a joining word or a sentence mark between - rather than read so. */
        val intended: Boolean get() = pieces > 1 || joined

        /** The second of two steps in a row that undo each other ("hoch und runter"), if any. */
        val undoneAt: Int? = (1 until parts.size).firstOrNull { undoes(parts[it - 1].command, parts[it].command) }

        /** Two to [MAX_STEPS] commands, all read cleanly; else null. */
        val commands: List<Command>?
            get() = parts.map { it.command }.takeIf { stuck == null && it.size in 2..MAX_STEPS && undoneAt == null }
    }

    /** The pieces of [heard] between sentence marks and joining words, folded, without those that hold only fillers. */
    private class Pieces(val words: List<List<String>>, val joined: Boolean) {
        val size: Int get() = words.size
    }

    private fun pieces(heard: String): Pieces {
        var joined = false
        val pieces = SENTENCE_MARKS.split(heard).flatMap { chunk ->
            val words = normalize(chunk).split(' ').filter { it.isNotEmpty() }
            val cut = mutableListOf<List<String>>()
            var start = 0
            var i = 0
            while (i < words.size) {
                val joiner = JOINERS.firstOrNull { words.size - i >= it.size && words.subList(i, i + it.size) == it }
                if (joiner == null) {
                    i++
                    continue
                }
                joined = true
                cut += words.subList(start, i)
                i += joiner.size
                start = i
            }
            cut + listOf(words.subList(start, words.size))
        }
        // "Amber, …", "äh, …": a piece of fillers alone is no command, and no missing one either.
        return Pieces(pieces.filter { piece -> read(piece.joinToString(" ")).let { it.words.isNotEmpty() || it.times != null } }, joined)
    }

    /**
     * Each piece cut into commands from its start: the longest run of words that is a command,
     * then the rest the same way ("select all copy"). Only the fixed rules, each with its own
     * count: a bare name or a misheard "öffne" is a command only when it is all that was said.
     */
    private fun combo(pieces: Pieces): Combo {
        val parts = mutableListOf<Part>()
        for (piece in pieces.words) {
            var rest = piece
            while (rest.isNotEmpty()) {
                val part = (rest.size downTo 1).firstNotNullOfOrNull { n -> step(rest.take(n))?.let { Part(rest.take(n), it) } }
                    ?: return Combo(pieces.size, pieces.joined, parts, stuck = rest)
                parts += part
                rest = rest.drop(part.words.size)
            }
        }
        return Combo(pieces.size, pieces.joined, parts, stuck = null)
    }

    private fun combo(heard: String): Combo = combo(pieces(heard))

    /** One command among several, from its folded [words]: its count, then the rules alone. */
    private fun step(words: List<String>): Command? {
        val reading = read(words.joinToString(" "))
        val times = reading.times
        if (reading.words.isEmpty() || (times != null && times < 1)) return null
        return rule(reading.words)?.repeated(times)
    }

    /**
     * "Hoch und runter", "lauter und leiser", "Taschenlampe an und aus": the second of two steps
     * takes the first back - a turn of phrase, not two commands.
     */
    private fun undoes(first: Command, second: Command): Boolean {
        fun way(command: Command): Direction? = when (command) {
            is Command.Scroll -> command.direction
            is Command.ScrollToEnd -> command.direction
            is Command.Page -> command.direction
            else -> null
        }
        fun opposite(a: Direction, b: Direction) = setOf(a, b).let { it == setOf(Direction.UP, Direction.DOWN) || it == setOf(Direction.LEFT, Direction.RIGHT) }
        val a = way(first)
        val b = way(second)
        return when {
            a != null && b != null -> opposite(a, b)
            first is Command.Swipe && second is Command.Swipe -> opposite(first.direction, second.direction)
            first is Command.Volume && second is Command.Volume -> setOf(first.change, second.change).let {
                it == setOf(VolumeChange.LOUDER, VolumeChange.QUIETER) || it == setOf(VolumeChange.MUTE, VolumeChange.UNMUTE)
            }
            first is Command.Torch && second is Command.Torch -> first.on != null && second.on != null && first.on != second.on
            else -> false
        }
    }

    // ---- Explaining, for Diagnostics ----

    /**
     * What the rules made of [heard], line by line, for Diagnostics: the folded text, the count,
     * the words left without fillers, politeness and Amber's name, then the command - or, when
     * there is none, the most specific reason the rules can tell. Several commands show each
     * part ("part 2: “kopieren” → Edit COPY") and, when they fail, the part that is none and
     * why. The same reading as [parse].
     */
    fun explain(heard: String, appLabels: () -> List<String> = { emptyList() }): List<String> {
        val reading = read(heard)
        val command = command(reading, appLabels)
        val combo = combo(heard)
        val commands = decide(command, combo)
        return buildList {
            add("normalized: \"${reading.normalized}\"")
            reading.times?.let { times ->
                add(if (times > Repeats.MAX_TIMES) "count: $times, capped at ${Repeats.MAX_TIMES}" else "count: $times")
            }
            add("words: " + reading.words.joinToString(" ").ifEmpty { "(none)" })
            val parts = combo.parts.mapIndexed { i, part -> "part ${i + 1}: “${part.words.joinToString(" ")}” → ${describe(part.command)}" }
            when {
                commands != null && commands.size > 1 -> {
                    addAll(parts)
                    add("understood: ${describe(commands)}")
                }
                command != null -> add(
                    when {
                        rule(reading.words) != null -> "understood: ${describe(command)}"
                        bareApp(reading.words, appLabels) != null -> "understood: ${describe(command)} (a bare name: an installed app)"
                        else -> "understood: ${describe(command)} (“${reading.words.first()}” taken for a misheard “öffne”: " +
                            "an installed app's exact name follows)"
                    },
                )
                else -> {
                    val split = splitReason(combo, appLabels)
                    if (combo.intended && split != null) {
                        // Said as several: the part that is none is the reason.
                        add("as one command: ${reason(reading, appLabels)}")
                        addAll(parts)
                        add("not understood: $split")
                    } else {
                        // One phrase: its own reason stands, the split that was tried noted before it.
                        if (parts.isNotEmpty() && split != null) {
                            addAll(parts)
                            add("as several commands: $split")
                        }
                        add("not understood: ${reason(reading, appLabels)}")
                    }
                }
            }
        }
    }

    /** Why [combo] is not several commands, or null when it is. */
    private fun splitReason(combo: Combo, appLabels: () -> List<String>): String? {
        val parts = combo.parts
        val stuck = combo.stuck
        val undone = combo.undoneAt
        return when {
            stuck != null -> {
                val words = stuck.joinToString(" ")
                val reading = read(words)
                val app = if (reading.times != null || reading.words.isEmpty()) null
                else (bareApp(reading.words, appLabels) ?: misheardOpen(reading.words, appLabels)) as? Command.OpenApp
                if (app != null) "“$words” alone opens an app only when it is all that was said - among several commands say “öffne ${app.name}”"
                else "“$words” is no command: ${reason(reading, appLabels)}"
            }
            parts.size > MAX_STEPS -> "${parts.size} commands - at most $MAX_STEPS in one go"
            undone != null -> "${describe(parts[undone - 1].command)}, then ${describe(parts[undone].command)}: the two undo each other - " +
                "taken for a turn of phrase like “hoch und runter”, not two commands"
            parts.size < 2 -> "only one command (${parts.singleOrNull()?.let { describe(it.command) } ?: "none"}), and a joining word " +
                "with no second command - say both, or the one alone"
            else -> null
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

    /** Several commands in their order, for Diagnostics: `Edit SELECT_ALL → Edit COPY`. */
    fun describe(commands: List<Command>): String = commands.joinToString(" → ", transform = ::describe)

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
            if (misheardOpen(words, appLabels) != null) return "an app with a count is not opened - say “öffne …” without the count"
        }
        return motionReason(words) ?: openReason(words) ?: misheardReason(words, appLabels) ?: bareReason(words, appLabels) ?: "no rule matches"
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
            words.first() in APP_TRAILS -> words.first() to words.drop(1)
            (words.first() == "mach" || words.first() == "mache") && words.last() == "auf" -> "mach … auf" to words.drop(1).dropLast(1)
            else -> return null
        }
        return when {
            appName(name) == null -> "“$verb” without an app name"
            sequenced(name) -> "“$verb” before words that put commands in a row (“dann”, “then”) - never part of an app's name"
            else -> null
        }
    }

    /**
     * A phrase that ends in an installed app's name but is no misheard "öffne" ([misheardOpen]):
     * more than one word before the name, a word the rules know, or only the start of a name.
     */
    private fun misheardReason(words: List<String>, appLabels: () -> List<String>): String? {
        if (words.size < 2) return null
        val labels = appLabels()
        if (labels.isEmpty()) return null
        // The longest end of the phrase that is an app's exact name, with words before it.
        for (cut in 1 until words.size) {
            val name = appName(words.drop(cut))?.takeIf { words.size - cut <= MAX_NAME_WORDS } ?: continue
            if (exactApp(name, labels) == null) continue
            val before = words.take(cut)
            val word = before.first()
            return when {
                before.size > 1 -> "“${before.joinToString(" ")}” before “$name”: only one unknown word before an app's exact name " +
                    "is taken for a misheard “öffne”"
                word in CLOSING_VERBS -> "“$word” closes, quits or removes - it is never taken for a misheard “öffne” before “$name”"
                word in POSSESSIVES -> "“$word” before “$name”: a name alone is opened, not with “$word” - say “öffne $name”"
                else -> "“$word” is a word the rules know, so it is not taken for a misheard “öffne” before “$name” - say “öffne $name”"
            }
        }
        val rest = appName(words.drop(1))?.takeIf { words.size - 1 <= MAX_NAME_WORDS } ?: return null
        if (isKnown(words.first()) || AppMatch.find(rest, labels, strict = true) { it } !is AppMatch.Found) return null
        return "“$rest” is only the start of an app's name - a misheard “öffne” is only assumed before an app's exact name"
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
        "geh zu", "gehe zu", "go to", "geh", "gehe", "go", "zeig", "zeige", "show", "offne", "oeffne", "offnen", "oeffnen", "open",
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
            "homescreen", "home screen", "home", "homepage", "home page",
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

    /** "Kopieren", "schneide das aus", "füge ein", "paste it": the words of each, in any order. */
    private val CLIPBOARD: Map<Set<String>, TextEdit> = buildMap {
        fun put(edit: TextEdit, vararg phrases: String) = phrases.forEach { put(it.split(' ').toSet(), edit) }
        put(TextEdit.COPY, "kopieren", "kopiere", "kopier", "copy")
        put(TextEdit.CUT, "ausschneiden", "schneide aus", "schneid aus", "cut")
        put(TextEdit.PASTE, "einfugen", "einfuegen", "fuge ein", "fug ein", "fuege ein", "paste")
    }

    /** The object words already gone. */
    private fun clipboard(words: List<String>): TextEdit? = CLIPBOARD[words.toSet()]

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
            // "Öffnen WhatsApp", "Starten Spotify": the infinitive first, as it is often said.
            words.size > 1 && words.first() in APP_TRAILS -> words.drop(1)
            else -> APP_LEADS.firstOrNull { startsWith(words, it) }?.let { words.drop(it.split(' ').size) } ?: return null
        }
        if (sequenced(name)) return null
        return appName(name)?.let { Command.OpenApp(it) }
    }

    /**
     * "Dann", "then", "after that": words that put commands in a row, so never part of an app's
     * name - "open the door and then go" opens nothing. A plain "und" or "and" may be ("Tom and
     * Jerry"), unless what it joins are commands.
     */
    private val SEQUENCE_WORDS = setOf("dann", "danach", "anschliessend", "then")

    private fun sequenced(words: List<String>): Boolean =
        words.any { it in SEQUENCE_WORDS } || words.windowed(2).any { it == listOf("after", "that") }

    /**
     * A name said without "open" ("WhatsApp", "zu WhatsApp") opens an app only when it is one's
     * exact name or the unique start of one - otherwise any short sentence would open something.
     */
    private fun bareApp(words: List<String>, appLabels: () -> List<String>): Command? {
        val rest = BARE_LEADS.firstOrNull { startsWith(words, it) && words.size > it.split(' ').size }
            ?.let { words.drop(it.split(' ').size) } ?: words
        // "Mein Telegram" is more likely the start of a sentence than a command.
        if (rest.any { it in POSSESSIVES }) return null
        val name = appName(rest)?.takeIf { rest.size <= MAX_NAME_WORDS } ?: return null
        return Command.OpenApp(name).takeIf { AppMatch.find(name, appLabels(), strict = true) { it } is AppMatch.Found }
    }

    /**
     * "Erfner WhatsApp": one word no rule knows, then an installed app's exact name - an "öffne"
     * or "open" the speech engine misheard. Only one word, only an unknown one, and only before
     * the exact name: "Ich habe kein WhatsApp", "Kein WhatsApp" and "Erfner Whats" open nothing.
     */
    private fun misheardOpen(words: List<String>, appLabels: () -> List<String>): Command? {
        if (words.size < 2 || isKnown(words.first())) return null
        val name = appName(words.drop(1))?.takeIf { words.size - 1 <= MAX_NAME_WORDS } ?: return null
        return exactApp(name, appLabels())?.let { Command.OpenApp(name) }
    }

    /** The one installed app [name] is the exact name of (case and marks aside), else null. */
    private fun exactApp(name: String, labels: List<String>): String? =
        (AppMatch.find(name, labels, strict = true) { it } as? AppMatch.Found)?.app
            ?.takeIf { AppMatch.tier(name, it) == AppMatch.Tier.EXACT }

    /** "Kein WhatsApp", "nicht Spotify": never taken for a misheard "öffne". */
    private val NEGATIONS = setOf(
        "kein", "keine", "keinen", "keiner", "keinem", "keines", "nicht", "nie", "niemals", "ohne", "nein", "nee", "no", "not",
        "never", "without", "dont", "doesnt", "cant", "wont",
    )

    /** "Beende WhatsApp", "quit Spotify": the opposite of opening, never taken for a misheard "öffne". */
    private val CLOSING_VERBS = setOf(
        "beende", "beenden", "beend", "schliess", "schliesse", "schliessen", "verlasse", "verlassen", "verlass", "deinstalliere",
        "deinstallieren", "deinstallier", "losche", "losch", "loschen", "loesche", "loeschen", "entferne", "entfern", "entfernen",
        "quit", "exit", "close", "kill", "uninstall", "delete", "remove",
    )

    /** "Mein Telegram", "my Spotify": a bare name after them is not opened, nor taken for a misheard "öffne". */
    private val POSSESSIVES = setOf(
        "mein", "meine", "meinen", "meinem", "meiner", "dein", "deine", "deinen", "sein", "seine", "ihr", "ihre", "unser", "unsere",
        "my", "your", "his", "her", "our", "their",
    )

    /** Every word some rule takes, and negations. Lazy: the word lists stand all over this file. */
    private val KNOWN_WORDS: Set<String> by lazy {
        listOf(
            LEAD_VERBS, TRAIL_VERBS, ARTICLES, RETURN_LEADS, CLOSE_LEADS, CLOSE_TRAILS, NOUNS.keys, LOCK_VERBS, LOCK_OBJECTS,
            MOTION_WORDS, VOLUME_NOUNS, SONG_NOUNS, MEDIA_FILLERS, LOUDER, QUIETER, RAISE, LOWER, MUTE, ON, OFF, PAUSE, PLAY_PARTS,
            NEXT, SKIP, PREVIOUS, TORCHES, SWITCH_FILLERS, PAGE_NAMES.keys, PAGE_TOPICS.keys, SETTINGS, SETTINGS_FILLERS,
            DELETE_VERBS, WORDS, DELETE_EXTRAS, ALL, SELECT_VERBS, OBJECT_WORDS, NEW_LINES, CLIPBOARD.keys.flatten(), APP_LEADS, APP_TRAILS,
            BARE_LEADS, NEGATIONS, CLOSING_VERBS, POSSESSIVES, setOf("app", "turn", "unmute", "stummschaltung", "aufheben", "schalten", "ein"),
        ).flatten().flatMap { it.split(' ') }.toSet()
    }

    private fun isKnown(word: String): Boolean = word in KNOWN_WORDS || Repeats.number(word) != null || Repeats.unreadable(word)

    /** Without articles in front and the word "app" around it: "die App WhatsApp", "the Gmail app". */
    private fun appName(words: List<String>): String? {
        var trimmed = words.dropWhile { it in ARTICLES }
        if (trimmed.firstOrNull() == "app") trimmed = trimmed.drop(1)
        if (trimmed.size > 1 && trimmed.last() == "app") trimmed = trimmed.dropLast(1)
        return trimmed.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }
}
