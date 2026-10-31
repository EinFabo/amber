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
        assertContains(reason("Scroll und wisch nach unten"), "a scroll word and a swipe word")
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
    fun `the verdict always agrees with parse`() {
        listOf(
            "Zurück", "zweimal zurück", "Öffne WhatsApp", "WhatsApp", "Tele", "Kalender", "zehnmal runter", "ganz nach unten",
            "nächste Seite", "wisch nach links", "lauter", "dreimal leiser", "Pause", "Taschenlampe an", "WLAN", "lösche 3 Wörter",
            "alles markieren", "neue Zeile", "Äh", "Ich bin gleich zu Hause", "Zweimal Startbildschirm", "", "öffne",
        ).forEach { heard ->
            val last = verdict(heard)
            when (val understanding = VoiceCommands.parse(heard) { apps }) {
                is Understanding.Understood -> assertTrue(
                    last.startsWith("understood: ${VoiceCommands.describe(understanding.command)}"),
                    "\"$heard\" -> $last",
                )
                is Understanding.NotUnderstood -> assertTrue(last.startsWith("not understood: "), "\"$heard\" -> $last")
            }
        }
    }
}
