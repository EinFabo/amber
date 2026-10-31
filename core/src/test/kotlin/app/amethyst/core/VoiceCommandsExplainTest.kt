package app.amethyst.core

import app.amethyst.core.commands.Command
import app.amethyst.core.commands.Direction
import app.amethyst.core.commands.GlobalAction
import app.amethyst.core.commands.MediaKey
import app.amethyst.core.commands.SettingsPage
import app.amethyst.core.commands.TextEdit
import app.amethyst.core.commands.Understanding
import app.amethyst.core.commands.VoiceCommands
import app.amethyst.core.commands.VolumeChange
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What Diagnostics shows of a phrase: the text as the rules read it, the words they kept, and
 * the command - or the most specific reason there is none.
 */
class VoiceCommandsExplainTest {

    private val apps = listOf("WhatsApp", "Telegram", "Telefon", "Google Maps", "Spotify")

    private fun explain(heard: String, labels: List<String> = apps) = VoiceCommands.explain(heard) { labels }

    /** The last line: "understood: …" or "not understood: …". */
    private fun verdict(heard: String, labels: List<String> = apps) = explain(heard, labels).last()

    private fun reason(heard: String, labels: List<String> = apps): String {
        val last = verdict(heard, labels)
        assertTrue(last.startsWith("not understood: "), "\"$heard\" -> $last")
        return last.removePrefix("not understood: ")
    }

    @Test
    fun `an understood phrase shows the folded text, its words, its count and the command`() {
        assertEquals(
            listOf(
                "normalized: \"scroll zehnmal nach unten\"",
                "count: 10",
                "words: scroll nach unten",
                "understood: Scroll DOWN ×10",
            ),
            explain("Scroll zehnmal nach unten!"),
        )
    }

    @Test
    fun `the words are what is left without fillers, politeness and Amber's name`() {
        val lines = explain("Hey Amber, äh, bitte zurück")
        assertEquals("normalized: \"hey amber ah bitte zuruck\"", lines.first())
        assertContains(lines, "words: zuruck")
        assertEquals("understood: Navigate BACK", lines.last())
    }

    @Test
    fun `a count above the cap says it was capped`() {
        val lines = explain("50 mal runter")
        assertContains(lines, "count: 50, capped at 20")
        assertEquals("understood: Scroll DOWN ×20", lines.last())
    }

    @Test
    fun `an app is named, and a bare name says it was one`() {
        assertEquals("understood: OpenApp \"whatsapp\"", verdict("Öffne WhatsApp"))
        assertEquals("understood: OpenApp \"whatsapp\" (a bare name: an installed app)", verdict("WhatsApp"))
    }

    @Test
    fun `a misheard open says which word was taken for it`() {
        assertEquals(
            "understood: OpenApp \"whatsapp\" (“erfner” taken for a misheard “öffne”: an installed app's exact name follows)",
            verdict("Erfner WhatsApp."),
        )
        assertEquals("understood: Navigate HOME", verdict("Homepage"))
    }

    @Test
    fun `an app name after more than one word, a known word or a count is not opened`() {
        val sentence = reason("Ich habe kein WhatsApp")
        assertContains(sentence, "“ich habe kein” before “whatsapp”")
        assertContains(sentence, "only one unknown word")
        val known = reason("Kein WhatsApp")
        assertContains(known, "“kein” is a word the rules know")
        assertContains(known, "misheard “öffne”")
        assertContains(reason("Zweimal Erfner WhatsApp"), "with a count")
    }

    @Test
    fun `a misheard open before only the start of a name says the name must be exact`() {
        val start = reason("Erfner Whats")
        assertContains(start, "“whats” is only the start of an app's name")
        assertContains(start, "exact name")
    }

    @Test
    fun `each kind of command reads with its parameters`() {
        assertEquals("Navigate BACK ×2", VoiceCommands.describe(Command.Navigate(GlobalAction.BACK, 2)))
        assertEquals("ScrollToEnd UP", VoiceCommands.describe(Command.ScrollToEnd(Direction.UP)))
        assertEquals("Page DOWN ×3", VoiceCommands.describe(Command.Page(Direction.DOWN, 3)))
        assertEquals("Swipe LEFT", VoiceCommands.describe(Command.Swipe(Direction.LEFT)))
        assertEquals("Media PAUSE", VoiceCommands.describe(Command.Media(MediaKey.PAUSE)))
        assertEquals("Volume LOUDER ×3", VoiceCommands.describe(Command.Volume(VolumeChange.LOUDER, 3)))
        assertEquals("Torch toggle", VoiceCommands.describe(Command.Torch(null)))
        assertEquals("Torch on", VoiceCommands.describe(Command.Torch(true)))
        assertEquals("OpenSettings WIFI", VoiceCommands.describe(Command.OpenSettings(SettingsPage.WIFI)))
        assertEquals("Edit DELETE_WORD ×3", VoiceCommands.describe(Command.Edit(TextEdit.DELETE_WORD, 3)))
    }

    @Test
    fun `only fillers`() {
        assertContains(reason("Äh, bitte."), "only filler words were heard")
        assertEquals("words: (none)", explain("Äh, bitte.")[1])
        assertContains(reason("Zehnmal"), "only a count was heard")
    }

    @Test
    fun `a count of zero`() = assertContains(reason("0 mal runter"), "a count of 0")

    @Test
    fun `a scroll or swipe word without one clear direction`() {
        assertContains(reason("Wisch"), "a swipe word without a direction")
        assertContains(reason("Seite"), "a page word without a direction")
        val several = reason("Scroll hoch runter")
        assertContains(several, "more than one direction")
        assertContains(several, "up, down")
        // "Scroll und wisch nach unten" is two commands now: scroll (down), then swipe down.
        assertContains(reason("Wisch scroll nach unten"), "a scroll word and a swipe word")
    }

    @Test
    fun `a direction with words the rules do not know names them`() {
        val unknown = reason("Runter schnell sofort")
        assertContains(unknown, "a direction")
        assertContains(unknown, "“schnell”, “sofort”")
    }

    @Test
    fun `an open word without an app name`() {
        assertContains(reason("Öffne"), "“offne” without an app name")
        assertContains(reason("Öffne die App"), "without an app name")
        assertContains(reason("Öffnen die App"), "“offnen” without an app name")
    }

    @Test
    fun `a closing word or a possessive before an app's name says why it does not open`() {
        val quit = reason("Beende WhatsApp")
        assertContains(quit, "“beende”")
        assertContains(quit, "closes, quits or removes")
        val mine = reason("Mein Telegram", labels = apps)
        assertContains(mine, "“mein”")
        assertContains(mine, "say “öffne telegram”")
    }

    @Test
    fun `a bare name that is no installed app`() {
        val none = reason("Kalender")
        assertContains(none, "“kalender” is not an installed app")
        assertContains(none, "exact name or the unique start")
        val several = reason("Tele")
        assertContains(several, "the start of more than one app's name")
        assertContains(several, "Telegram, Telefon")
        assertContains(reason("Kalender", labels = emptyList()), "no app list")
    }

    @Test
    fun `a count the rules cannot read`() {
        assertContains(reason("Dreißigmal runter"), "a count the rules cannot read: “dreissigmal”")
        assertContains(reason("Dreißig mal runter"), "a count the rules cannot read: “dreissig”")
        assertContains(reason("Scroll fifty times down"), "a count the rules cannot read: “fifty”")
        assertContains(reason("Hundertmal lauter"), "“hundertmal”")
        assertContains(reason("99999999999 mal runter"), "“99999999999”")
    }

    @Test
    fun `a word that only contains a number word keeps its real reason`() {
        val century = reason("Jahrhundert runter")
        assertFalse("count" in century, century)
        assertContains(century, "“jahrhundert”")
        val millipede = reason("Tausendfüßler")
        assertFalse("count" in millipede, millipede)
        assertContains(millipede, "“tausendfussler” is not an installed app")
        // Real counts past twenty still say so.
        assertContains(reason("Dreihundert mal runter"), "a count the rules cannot read: “dreihundert”")
        assertContains(reason("Scroll down a thousand times"), "a count the rules cannot read: “thousand”")
    }

    @Test
    fun `a number without mal or times`() =
        assertContains(reason("Zehn runter"), "the number “zehn” has no “mal” or “times” after it")

    @Test
    fun `a count on a command that does not repeat`() {
        assertContains(reason("Zweimal Startbildschirm"), "Navigate HOME does not repeat")
        assertContains(reason("Zweimal WhatsApp"), "a bare app name with a count")
    }

    @Test
    fun `everything else is no rule`() {
        assertEquals("no rule matches", reason("Ich bin gleich wieder zu Hause bei dir"))
    }

    @Test
    fun `several commands show each part and then all of them in order`() {
        assertEquals(
            listOf(
                "normalized: \"alles markieren und dann kopieren\"",
                "words: alles markieren und dann kopieren",
                "part 1: “alles markieren” → Edit SELECT_ALL",
                "part 2: “kopieren” → Edit COPY",
                "understood: Edit SELECT_ALL → Edit COPY",
            ),
            explain("Alles markieren und dann kopieren."),
        )
        val counted = explain("Zweimal zurück und dann öffne WhatsApp")
        assertContains(counted, "part 1: “zweimal zuruck” → Navigate BACK ×2")
        assertEquals("understood: Navigate BACK ×2 → OpenApp \"whatsapp\"", counted.last())
        assertEquals("understood: Edit SELECT_ALL → Edit COPY", verdict("Select all copy"))
    }

    @Test
    fun `a part that is no command is named with its reason`() {
        val door = explain("Open the door and then go")
        val why = door.last()
        assertTrue(why.startsWith("not understood: “open the door” opens no installed app"), why)
        assertContains(why, "exact name or the unique start")
        // What the words are as one command comes first.
        assertTrue(door.any { it.startsWith("as one command: ") }, "$door")

        val go = reason("Zurück und go")
        assertTrue(go.startsWith("“go” is no command"), go)

        val home = reason("Ich gehe zurück und dann nach Hause")
        assertContains(home, "“ich gehe zuruck” is no command")
        assertContains(home, "no rule matches")
    }

    @Test
    fun `a bare app name among several commands says it needs an open word`() {
        val bare = reason("Markier alles und WhatsApp")
        assertContains(bare, "“whatsapp”")
        assertContains(bare, "say “öffne whatsapp”")
    }

    @Test
    fun `steps that undo each other, too many steps and a lone joining word say so`() {
        val undo = reason("Hoch und runter")
        assertContains(undo, "Scroll UP, then Scroll DOWN")
        assertContains(undo, "undo each other")
        assertContains(reason("Zurück, runter, zurück, runter, zurück, runter"), "more than 5 commands - at most 5 in one go")
        // Said as six, though one command would read them all.
        val six = explain("Zurück, zurück, zurück, zurück, zurück, zurück")
        assertContains(six.last(), "more than 5 commands")
        assertContains(six, "as one command: would be Navigate BACK")
        assertContains(reason("Zurück und"), "only one command")
    }

    @Test
    fun `cutting without a joining word, a count between two and select all then paste say why not`() {
        // Said as one phrase: its own reason is the verdict, the cut that was refused comes before it.
        fun cut(heard: String) = explain(heard).single { it.startsWith("as several commands: ") }
        val back = cut("Zurück kopieren")
        assertContains(back, "“zuruck” and “kopieren” without a joining word")
        assertContains(back, "say “und” or “dann” between them")
        assertContains(cut("Lauter zweimal kopieren"), "a count between “lauter zweimal” and “kopieren”")
        val paste = reason("Select all, then paste")
        assertContains(paste, "Edit SELECT_ALL, then Edit PASTE")
        assertContains(paste, "does not empty or replace a whole field in one go")
    }

    @Test
    fun `the same command said again and a direction that goes with the scroll before show as one part`() {
        assertEquals(
            listOf("part 1: “zuruck” → Navigate BACK", "understood: Navigate BACK"),
            explain("Zurück und zurück").takeLast(2),
        )
    }

    @Test
    fun `one phrase that does not split keeps its own reason, the split noted before it`() {
        val lines = explain("Runter schnell sofort")
        assertContains(lines, "part 1: “runter” → Scroll DOWN")
        assertTrue(lines.any { it.startsWith("as several commands: “schnell sofort” is no command: no rule matches") }, "$lines")
        assertContains(lines.last(), "a direction, but with words the rules don't know there")
        // Nothing found at all: only the reason, as before.
        assertEquals(listOf("normalized: \"tele\"", "words: tele"), explain("Tele").dropLast(1))
    }

    @Test
    fun `the verdict always agrees with parse`() {
        listOf(
            "Zurück", "zweimal zurück", "Öffne WhatsApp", "WhatsApp", "Tele", "Kalender", "zehnmal runter", "ganz nach unten",
            "nächste Seite", "wisch nach links", "lauter", "dreimal leiser", "Pause", "Taschenlampe an", "WLAN", "lösche 3 Wörter",
            "alles markieren", "neue Zeile", "Äh", "Ich bin gleich zu Hause", "Zweimal Startbildschirm", "", "öffne",
            "Erfner WhatsApp", "Öffner WhatsApp", "Ich habe kein WhatsApp", "Kein WhatsApp", "Erfner Whats", "Homepage",
            "Home page", "Zweimal Erfner WhatsApp", "Öffnen WhatsApp", "Starten Spotify", "Beende WhatsApp", "Mein Telegram",
            "Quit Spotify", "Öffnen die App", "Select all copy", "alles markieren und kopieren", "zweimal zurück und dann öffne WhatsApp",
            "Hoch und runter", "Open the door and then go", "Öffne WhatsApp und dann zurück", "Open Tom and Jerry", "Zurück und",
            "Markier alles und WhatsApp", "Runter schnell sofort", "Zurück, zurück, zurück, zurück, zurück, zurück", "Zurück und zurück",
            "Scroll, nach unten.", "Zurück kopieren", "Select all, then paste", "Zurück nach oben", "Open the door and then go back",
        ).forEach { heard ->
            val last = verdict(heard)
            when (val understanding = VoiceCommands.parse(heard) { apps }) {
                is Understanding.Understood -> assertTrue(
                    last.startsWith("understood: ${VoiceCommands.describe(understanding.commands)}"),
                    "\"$heard\" -> $last",
                )
                is Understanding.NotUnderstood -> assertTrue(last.startsWith("not understood: "), "\"$heard\" -> $last")
            }
        }
    }
}
