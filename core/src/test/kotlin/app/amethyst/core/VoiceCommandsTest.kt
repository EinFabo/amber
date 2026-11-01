package app.amethyst.core

import app.amethyst.core.commands.Command
import app.amethyst.core.commands.Direction
import app.amethyst.core.commands.GlobalAction
import app.amethyst.core.commands.MediaKey
import app.amethyst.core.commands.TextEdit
import app.amethyst.core.commands.VolumeChange
import app.amethyst.core.commands.Understanding
import app.amethyst.core.commands.VoiceCommands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VoiceCommandsTest {

    private fun understood(heard: String): Command =
        assertIs<Understanding.Understood>(VoiceCommands.parse(heard), "\"$heard\"").commands.single()

    private fun assertCommand(expected: Command, vararg heard: String) =
        heard.forEach { assertEquals(expected, understood(it), "\"$it\"") }

    @Test
    fun `back in both languages`() = assertCommand(
        Command.Navigate(GlobalAction.BACK),
        "Zurück", "Zurück.", "Geh zurück", "Gehe zurück!", "zurück gehen", "Back", "Go back.",
    )

    @Test
    fun `home`() = assertCommand(
        Command.Navigate(GlobalAction.HOME),
        "Startbildschirm", "Geh zum Startbildschirm", "Home", "Go home", "Go to the home screen", "Startseite",
        "Homepage", "Homepage.", "Home page", "Zur Homepage",
    )

    @Test
    fun `recent apps`() = assertCommand(
        Command.Navigate(GlobalAction.RECENTS),
        "Letzte Apps", "Zeig die letzten Apps", "Übersicht", "Recent apps", "Show recent apps", "Overview",
    )

    @Test
    fun `notifications and quick settings`() {
        assertCommand(
            Command.Navigate(GlobalAction.NOTIFICATIONS),
            "Benachrichtigungen", "Öffne die Benachrichtigungen", "Benachrichtigungen anzeigen", "Show notifications",
            "Open notifications",
        )
        assertCommand(
            Command.Navigate(GlobalAction.QUICK_SETTINGS),
            "Schnelleinstellungen", "Schnelleinstellungen öffnen", "Quick settings", "Open quick settings",
        )
    }

    @Test
    fun `lock and screenshot`() {
        assertCommand(
            Command.Navigate(GlobalAction.LOCK_SCREEN),
            "Sperren", "Bildschirm sperren", "Handy sperren", "Lock", "Lock the screen", "Lock the phone",
        )
        assertCommand(
            Command.Navigate(GlobalAction.SCREENSHOT),
            "Screenshot", "Mach einen Screenshot", "Bildschirmfoto", "Take a screenshot",
        )
    }

    @Test
    fun `opening an app`() {
        assertCommand(Command.OpenApp("whatsapp"), "Öffne WhatsApp", "WhatsApp öffnen", "Open WhatsApp.", "Starte WhatsApp", "Launch WhatsApp")
        assertCommand(Command.OpenApp("google maps"), "Öffne Google Maps", "Mach Google Maps auf", "open the Google Maps app")
        assertCommand(Command.OpenApp("whatsapp"), "Öffne die App WhatsApp", "oeffne whatsapp", "offne whatsapp")
    }

    @Test
    fun `an opening verb may come first in its infinitive too`() {
        assertCommand(Command.OpenApp("whatsapp"), "Öffnen WhatsApp", "Oeffnen WhatsApp.", "Öffnen die App WhatsApp")
        assertCommand(Command.OpenApp("spotify"), "Starten Spotify", "Start Spotify", "Launch Spotify")
        assertCommand(Command.Navigate(GlobalAction.NOTIFICATIONS), "Öffnen Benachrichtigungen")
    }

    private val apps = listOf("WhatsApp", "Google Maps", "Spotify", "Telegram", "Telefon")

    @Test
    fun `one unknown word before an installed app's exact name is a misheard open`() {
        listOf("Erfner WhatsApp.", "Öffner WhatsApp", "Erfner die App WhatsApp", "Erfner Google Maps", "Uff Spotify").forEach { heard ->
            val expected = if ("Maps" in heard) "google maps" else if ("Spotify" in heard) "spotify" else "whatsapp"
            assertEquals(Understanding.Understood(Command.OpenApp(expected)), VoiceCommands.parse(heard) { apps }, "\"$heard\"")
        }
    }

    @Test
    fun `a sentence, a known word, a count or a partial name before an app is no misheard open`() {
        listOf(
            "Ich habe kein WhatsApp",
            "Ich habe WhatsApp",
            "Kein WhatsApp",
            "Keinem WhatsApp",
            "Nicht WhatsApp",
            "Nein WhatsApp",
            "Nee Spotify",
            "Mein Telegram",
            "Meine Telegram",
            "My Spotify",
            "Beende WhatsApp",
            "Beenden Spotify",
            "Schließe WhatsApp",
            "Schließen WhatsApp",
            "Verlasse WhatsApp",
            "Deinstalliere WhatsApp",
            "Entferne WhatsApp",
            "Quit Spotify",
            "Exit WhatsApp",
            "Close WhatsApp",
            "Kill Spotify",
            "Uninstall WhatsApp",
            "Delete WhatsApp",
            "Remove WhatsApp",
            "Lauter WhatsApp",
            "Schließ WhatsApp",
            "Lösche WhatsApp",
            "Zweimal Erfner WhatsApp",
            "Erfner Whats",
            "Erfner WhatsAp",
            "Erfner Maps",
            "Erfner Tele",
        ).forEach { assertIs<Understanding.NotUnderstood>(VoiceCommands.parse(it) { apps }, "\"$it\"") }
        assertIs<Understanding.NotUnderstood>(VoiceCommands.parse("Erfner WhatsApp"), "no apps known, nothing to open")
    }

    @Test
    fun `scrolling`() {
        assertCommand(Command.Scroll(Direction.DOWN), "Scroll runter", "Scrolle nach unten", "Nach unten scrollen", "Runter scrollen", "Scroll down")
        assertCommand(Command.Scroll(Direction.UP), "Scroll hoch", "Scroll nach oben", "Hoch scrollen", "Scroll up")
        assertCommand(Command.Scroll(Direction.LEFT), "Scroll nach links", "Scroll left")
        assertCommand(Command.Scroll(Direction.RIGHT), "Scroll nach rechts", "Scroll right", "Blättere nach rechts")
    }

    @Test
    fun `swiping`() {
        assertCommand(Command.Swipe(Direction.LEFT), "Wisch nach links", "Nach links wischen", "Swipe left", "Wische links")
        assertCommand(Command.Swipe(Direction.RIGHT), "Wisch nach rechts", "Swipe right")
    }

    @Test
    fun `politeness, fillers and the name are dropped`() {
        assertCommand(Command.Navigate(GlobalAction.BACK), "Bitte zurück", "Äh, zurück bitte", "Amber, zurück", "Hey Amber, go back please")
        assertCommand(Command.OpenApp("whatsapp"), "Kannst du bitte WhatsApp öffnen?", "Can you open WhatsApp", "Mach mal WhatsApp auf")
        assertCommand(Command.Scroll(Direction.DOWN), "Scroll mal ein bisschen runter")
    }

    @Test
    fun `system nouns win over apps of the same name`() {
        assertCommand(Command.Navigate(GlobalAction.NOTIFICATIONS), "Öffne Benachrichtigungen")
        assertCommand(Command.Navigate(GlobalAction.QUICK_SETTINGS), "Open quick settings")
    }

    @Test
    fun `dictation-like sentences are not commands`() {
        listOf(
            "Ich bin gleich zu Hause",
            "Wir sehen uns morgen um acht",
            "Kannst du mir das bitte schicken",
            "öffne",
            "",
            "Zurück zum Thema: die Lieferung kommt morgen",
        ).forEach { assertIs<Understanding.NotUnderstood>(VoiceCommands.parse(it), "\"$it\"") }
    }

    private fun commands(heard: String, labels: List<String> = emptyList()): List<Command> =
        assertIs<Understanding.Understood>(VoiceCommands.parse(heard) { labels }, "\"$heard\"").commands

    @Test
    fun `several commands run in the order said, each with its own count`() {
        assertEquals(
            listOf(Command.Navigate(GlobalAction.BACK, 2), Command.OpenApp("whatsapp")),
            commands("zweimal zurück und dann öffne WhatsApp", listOf("WhatsApp")),
        )
        assertEquals(
            listOf(Command.Navigate(GlobalAction.BACK), Command.Scroll(Direction.DOWN, 3)),
            commands("zurück und dann dreimal runter"),
        )
    }

    @Test
    fun `without a joining word the longest command comes first`() {
        assertEquals(
            listOf(Command.Edit(TextEdit.SELECT_ALL), Command.Edit(TextEdit.COPY)),
            commands("Select all copy"),
        )
        // A count before the first command is its own.
        assertEquals(
            listOf(Command.Volume(VolumeChange.LOUDER, 2), Command.Edit(TextEdit.COPY)),
            commands("zweimal lauter kopieren"),
        )
    }

    @Test
    fun `without a joining word nothing that leaves the screen is cut apart, nor a count between two`() {
        listOf(
            "Startbildschirm zweimal runter", "zurück kopieren", "Scroll back up kopieren", "Stop scroll", "Startbildschirm öffne Spotify",
            "lauter zweimal kopieren", "Pause zweimal lauter",
        ).forEach { assertIs<Understanding.NotUnderstood>(VoiceCommands.parse(it) { listOf("Spotify") }, "\"$it\"") }
        // With a joining word or a comma between them they are several.
        assertEquals(listOf(Command.Navigate(GlobalAction.BACK), Command.Edit(TextEdit.COPY)), commands("zurück, kopieren"))
        assertEquals(listOf(Command.Media(MediaKey.PAUSE), Command.Volume(VolumeChange.LOUDER, 2)), commands("Pause, zweimal lauter"))
    }

    @Test
    fun `back with a way to scroll is that scroll`() {
        assertEquals(listOf(Command.Scroll(Direction.UP)), commands("Scroll back up"))
        assertEquals(listOf(Command.Scroll(Direction.UP)), commands("Zurück nach oben"))
        assertEquals(listOf(Command.ScrollToEnd(Direction.UP)), commands("Zurück zum Anfang"))
        assertEquals(listOf(Command.ScrollToEnd(Direction.DOWN)), commands("Zurück zum Ende"))
        assertEquals(listOf(Command.Scroll(Direction.UP)), commands("Zurück blättern"))
        // A count goes with the scroll; nothing goes back.
        assertEquals(listOf(Command.Scroll(Direction.DOWN, 2)), commands("zurück zweimal runter"))
        // Alone it is still the way back.
        assertEquals(listOf(Command.Navigate(GlobalAction.BACK)), commands("Zurück"))
        assertEquals(listOf(Command.Navigate(GlobalAction.BACK)), commands("Go back"))
    }

    @Test
    fun `the same command said again runs once, a direction or a count alone goes with the one before`() {
        listOf("zurück zurück", "Zurück, zurück", "Zurück und zurück").forEach {
            assertEquals(listOf(Command.Navigate(GlobalAction.BACK)), commands(it), "\"$it\"")
        }
        assertEquals(listOf(Command.Scroll(Direction.DOWN)), commands("Scroll, nach unten."))
        assertEquals(listOf(Command.Swipe(Direction.LEFT)), commands("Wisch nach links, nach links"))
        assertEquals(listOf(Command.Navigate(GlobalAction.BACK, 2)), commands("Zurück, zweimal"))
        // After a command that does not scroll, a direction is a scroll of its own.
        assertEquals(listOf(Command.Navigate(GlobalAction.HOME), Command.Scroll(Direction.DOWN)), commands("Startbildschirm, runter"))
    }

    @Test
    fun `select all, then cut or paste is refused - the whole field would be emptied or replaced`() {
        listOf("alles markieren und ausschneiden", "Select all, then paste", "alles markieren, kopieren und einfügen").forEach {
            assertIs<Understanding.NotUnderstood>(VoiceCommands.parse(it), "\"$it\"")
        }
        // Copying, or a new line behind the selection, leaves the text as it is.
        assertEquals(2, commands("alles markieren und kopieren").size)
        assertEquals(2, commands("alles markieren und neue Zeile").size)
    }

    @Test
    fun `one command reads as it always did`() {
        // "Zurück zum Startbildschirm" names where to; the same rule makes a doubled "zurück" one step back.
        assertEquals(listOf(Command.Navigate(GlobalAction.HOME)), commands("Zurück zum Startbildschirm"))
        assertEquals(listOf(Command.Navigate(GlobalAction.BACK)), commands("zurück zurück"))
        // A name with a joining word in it still opens when the rest is no command.
        assertEquals(listOf(Command.OpenApp("tom and jerry")), commands("Open Tom and Jerry"))
        assertEquals(listOf(Command.OpenApp("booking com")), commands("Öffne Booking.com"))
        assertEquals(listOf(Command.OpenApp("whatsapp")), commands("Hey Amber, öffne WhatsApp."))
    }

    @Test
    fun `an app among several commands needs an open word and an installed app's name`() {
        val apps = listOf("WhatsApp", "Spotify")
        assertEquals(listOf(Command.Edit(TextEdit.SELECT_ALL), Command.OpenApp("whatsapp")), commands("markier alles und öffne WhatsApp", apps))
        assertEquals(listOf(Command.OpenApp("spot"), Command.Navigate(GlobalAction.BACK)), commands("öffne Spot und zurück", apps))
        listOf(
            "markier alles und WhatsApp", "WhatsApp und markier alles", "alles markieren und Erfner WhatsApp",
            "Open the door and then go back", "zurück und öffne Kalender",
        ).forEach {
            assertIs<Understanding.NotUnderstood>(VoiceCommands.parse(it) { apps }, "\"$it\"")
        }
    }

    @Test
    fun `a part that is no command stops the whole phrase - nothing runs halfway`() {
        listOf("alles markieren und blabla", "Ich gehe zurück und dann nach Hause", "Open the door and then go", "zurück und").forEach {
            assertIs<Understanding.NotUnderstood>(VoiceCommands.parse(it), "\"$it\"")
        }
    }

    @Test
    fun `at most five commands in one go`() {
        assertEquals(5, commands("zurück, runter, zurück, runter, Startbildschirm").size)
        assertIs<Understanding.NotUnderstood>(VoiceCommands.parse("zurück, runter, zurück, runter, zurück, runter"))
        // Said as six, it is not taken for the one command the words would also make.
        assertIs<Understanding.NotUnderstood>(VoiceCommands.parse("Zurück, zurück, zurück, zurück, zurück, zurück"))
    }

    @Test
    fun `two steps that undo each other are a phrase`() {
        listOf("hoch und runter", "rauf und runter", "up and down", "lauter und leiser", "Ton aus und Ton an", "Taschenlampe an, Taschenlampe aus").forEach {
            assertIs<Understanding.NotUnderstood>(VoiceCommands.parse(it), "\"$it\"")
        }
        // Not next to each other: no phrase.
        assertEquals(3, commands("hoch, zurück, runter").size)
    }

    /**
     * How often the installed apps are asked for while a command is chosen among its language
     * passes (parseStrict) and then run and explained (parse, explain) - each ask is the
     * launcher list read on the phone, up to a few hundred milliseconds.
     */
    private fun labelReads(heard: String): Int {
        var reads = 0
        val apps = { reads++; listOf("WhatsApp", "Spotify", "Telegram") }
        VoiceCommands.parseStrict(heard, apps)
        VoiceCommands.parse(heard, apps)
        VoiceCommands.explain(heard, apps)
        return reads
    }

    @Test
    fun `a command the rules know never asks for the installed apps`() {
        listOf(
            "Zurück", "Lauter", "Scroll runter", "Taschenlampe an", "Zehnmal runter", "Startbildschirm", "Nächste Seite",
            "Pause", "WLAN-Einstellungen", "neue Zeile", "Lösche das letzte Wort", "Go back.", "Flashlight off",
        ).forEach {
            assertIs<Understanding.Understood>(VoiceCommands.parse(it), "\"$it\"")
            assertEquals(0, labelReads(it), "\"$it\" read the app list")
        }
    }

    @Test
    fun `an app, a bare name or several commands still look the apps up`() {
        // Opening needs the list anyway; a bare name and the parts of several commands are checked against it.
        listOf("Öffne WhatsApp", "WhatsApp", "Zu Spotify", "alles markieren und kopieren", "markier alles und öffne WhatsApp").forEach {
            assertIs<Understanding.Understood>(VoiceCommands.parse(it) { listOf("WhatsApp", "Spotify") }, "\"$it\"")
            assertTrue(labelReads(it) > 0, "\"$it\" never read the app list")
        }
    }

    @Test
    fun `normalizing folds case, accents, sharp s and punctuation`() {
        assertEquals("offne strasse app", VoiceCommands.normalize("Öffne  Straße-App!"))
        assertEquals("whats app", VoiceCommands.normalize("What's App?"))
    }
}
