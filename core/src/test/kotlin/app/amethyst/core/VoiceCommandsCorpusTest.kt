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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What people say, written the way speech engines write it - capitalized, with a full stop,
 * compound words glued or split - each with the one command it must mean. A table, so a new
 * phrasing that fails is one line here.
 */
class VoiceCommandsCorpusTest {

    private val apps = listOf(
        "WhatsApp", "Telegram", "Telefon", "Google Maps", "Spotify", "YouTube", "Kamera", "Einstellungen", "Chrome",
        "Gmail", "Play Store", "Signal",
    )

    private fun parse(heard: String) = VoiceCommands.parse(heard) { apps }

    private fun nav(action: GlobalAction, times: Int = 1) = Command.Navigate(action, times)
    private fun scroll(direction: Direction, times: Int = 1) = Command.Scroll(direction, times)
    private fun page(direction: Direction, times: Int = 1) = Command.Page(direction, times)
    private fun swipe(direction: Direction, times: Int = 1) = Command.Swipe(direction, times)
    private fun volume(change: VolumeChange, times: Int = 1) = Command.Volume(change, times)
    private fun edit(edit: TextEdit, times: Int = 1) = Command.Edit(edit, times)
    private fun app(name: String) = Command.OpenApp(name)

    private val back = nav(GlobalAction.BACK)
    private val home = nav(GlobalAction.HOME)
    private val down = scroll(Direction.DOWN)
    private val up = scroll(Direction.UP)

    /** The phrasings a probe of v1 did not understand (2026-10-31), every one of them. */
    private val v1Misses: List<Pair<String, Command>> = listOf(
        "Runter" to down,
        "Nach unten" to down,
        "Hoch" to up,
        "Weiter runter" to down,
        "Scroll ganz nach unten" to Command.ScrollToEnd(Direction.DOWN),
        "Runterscrollen" to down,
        "Herunterscrollen" to down,
        "Scrollen" to down,
        "Scroll weiter" to down,
        "Weiter scrollen" to down,
        "Seite runter" to page(Direction.DOWN),
        "Nächste Seite" to page(Direction.DOWN),
        "Vorherige Seite" to page(Direction.UP),
        "WhatsApp" to app("whatsapp"),
        "Geh auf WhatsApp" to app("whatsapp"),
        "Zu WhatsApp" to app("whatsapp"),
        "Einen Schritt zurück" to back,
        "Zurück zum Startbildschirm" to home,
        "Zum Homebildschirm" to home,
        "Homebildschirm" to home,
        "Home Bildschirm" to home,
        "Benachrichtigungen schließen" to nav(GlobalAction.CLOSE_NOTIFICATIONS),
        "Sperr das Handy" to nav(GlobalAction.LOCK_SCREEN),
        "Scroll 10 mal nach unten" to scroll(Direction.DOWN, 10),
        "Zehnmal runter" to scroll(Direction.DOWN, 10),
        "Scroll dreimal runter" to scroll(Direction.DOWN, 3),
        "Swipe 5 times down" to swipe(Direction.DOWN, 5),
        "Multitasking" to nav(GlobalAction.RECENTS),
        "Scroll to the bottom" to Command.ScrollToEnd(Direction.DOWN),
    )

    private val navigate: List<Pair<String, Command>> = listOf(
        "Zurück" to back,
        "Zurück." to back,
        "Geh zurück" to back,
        "Gehe zurück!" to back,
        "Zurückgehen" to back,
        "Zurück gehen." to back,
        "Ein Schritt zurück" to back,
        "Back" to back,
        "Go back." to back,
        "One step back" to back,
        "Zweimal zurück" to nav(GlobalAction.BACK, 2),
        "Drei Schritte zurück" to nav(GlobalAction.BACK, 3),
        "2 mal zurück" to nav(GlobalAction.BACK, 2),
        "Go back twice" to nav(GlobalAction.BACK, 2),
        "Back 3 times" to nav(GlobalAction.BACK, 3),
        "Startbildschirm" to home,
        "Zum Startbildschirm" to home,
        "Geh zum Startbildschirm" to home,
        "Home-Bildschirm." to home,
        "Home" to home,
        "Go home" to home,
        "Go to the home screen" to home,
        "Back to the home screen" to home,
        "Startseite" to home,
        "Homescreen" to home,
        "Letzte Apps" to nav(GlobalAction.RECENTS),
        "Zeig die letzten Apps" to nav(GlobalAction.RECENTS),
        "Übersicht" to nav(GlobalAction.RECENTS),
        "App-Übersicht" to nav(GlobalAction.RECENTS),
        "Recent apps" to nav(GlobalAction.RECENTS),
        "Show recent apps" to nav(GlobalAction.RECENTS),
        "Benachrichtigungen" to nav(GlobalAction.NOTIFICATIONS),
        "Öffne die Benachrichtigungen" to nav(GlobalAction.NOTIFICATIONS),
        "Benachrichtigungen anzeigen" to nav(GlobalAction.NOTIFICATIONS),
        "Show notifications" to nav(GlobalAction.NOTIFICATIONS),
        "Schließ die Benachrichtigungen" to nav(GlobalAction.CLOSE_NOTIFICATIONS),
        "Mach die Benachrichtigungen zu" to nav(GlobalAction.CLOSE_NOTIFICATIONS),
        "Close notifications" to nav(GlobalAction.CLOSE_NOTIFICATIONS),
        "Dismiss the notifications" to nav(GlobalAction.CLOSE_NOTIFICATIONS),
        "Schnelleinstellungen" to nav(GlobalAction.QUICK_SETTINGS),
        "Quick settings" to nav(GlobalAction.QUICK_SETTINGS),
        "Schnelleinstellungen schließen" to nav(GlobalAction.CLOSE_NOTIFICATIONS),
        "Handy sperren" to nav(GlobalAction.LOCK_SCREEN),
        "Bildschirm sperren." to nav(GlobalAction.LOCK_SCREEN),
        "Sperren" to nav(GlobalAction.LOCK_SCREEN),
        "Sperre den Bildschirm" to nav(GlobalAction.LOCK_SCREEN),
        "Lock the phone" to nav(GlobalAction.LOCK_SCREEN),
        "Lock screen" to nav(GlobalAction.LOCK_SCREEN),
        "Screenshot" to nav(GlobalAction.SCREENSHOT),
        "Mach einen Screenshot" to nav(GlobalAction.SCREENSHOT),
        "Bildschirmfoto" to nav(GlobalAction.SCREENSHOT),
        "Take a screenshot" to nav(GlobalAction.SCREENSHOT),
        "Screenshot machen." to nav(GlobalAction.SCREENSHOT),
        "Geteilter Bildschirm" to nav(GlobalAction.SPLIT_SCREEN),
        "Split Screen" to nav(GlobalAction.SPLIT_SCREEN),
        "Splitscreen" to nav(GlobalAction.SPLIT_SCREEN),
        "Bildschirm teilen" to nav(GlobalAction.SPLIT_SCREEN),
        "Power-Menü" to nav(GlobalAction.POWER_MENU),
        "Ein-/Aus-Menü" to nav(GlobalAction.POWER_MENU),
        "Show the power menu" to nav(GlobalAction.POWER_MENU),
    )

    private val openApps: List<Pair<String, Command>> = listOf(
        "Öffne WhatsApp" to app("whatsapp"),
        "WhatsApp öffnen" to app("whatsapp"),
        "Open WhatsApp." to app("whatsapp"),
        "Starte Spotify" to app("spotify"),
        "Mach Google Maps auf" to app("google maps"),
        "Launch YouTube" to app("youtube"),
        "WhatsApp." to app("whatsapp"),
        "Spotify" to app("spotify"),
        "Kamera" to app("kamera"),
        "Wechsel zu Telegram" to app("telegram"),
        "Switch to Chrome" to app("chrome"),
        "Öffne Telegramm" to app("telegramm"),
        "Open the Gmail app" to app("gmail"),
    )

    private val scrolling: List<Pair<String, Command>> = listOf(
        "Scroll runter" to down,
        "Runter." to down,
        "Rauf" to up,
        "Nach oben" to up,
        "Ein bisschen runter" to down,
        "Hochscrollen" to up,
        "Raufscrollen" to up,
        "Nach unten scrollen." to down,
        "Weiterscrollen" to down,
        "Scroll down" to down,
        "Scroll up" to up,
        "Down" to down,
        "Scroll down a bit" to down,
        "Scroll nach links" to scroll(Direction.LEFT),
        "Scroll right" to scroll(Direction.RIGHT),
        "Blättere nach rechts" to scroll(Direction.RIGHT),
        "Scroll 10-mal runter" to scroll(Direction.DOWN, 10),
        "Zweimal hoch" to scroll(Direction.UP, 2),
        "Scroll down 5 times" to scroll(Direction.DOWN, 5),
        "Scroll up twice" to scroll(Direction.UP, 2),
        "Fünfmal nach unten scrollen" to scroll(Direction.DOWN, 5),
        "Scroll 3x runter" to scroll(Direction.DOWN, 3),
        "Scroll zwanzigmal runter" to scroll(Direction.DOWN, 20),
        "Scroll 50 mal runter" to scroll(Direction.DOWN, 20),
        "Ganz nach unten" to Command.ScrollToEnd(Direction.DOWN),
        "Ganz runter" to Command.ScrollToEnd(Direction.DOWN),
        "Bis zum Ende scrollen" to Command.ScrollToEnd(Direction.DOWN),
        "All the way down" to Command.ScrollToEnd(Direction.DOWN),
        "Scroll ganz nach oben" to Command.ScrollToEnd(Direction.UP),
        "Ganz nach oben" to Command.ScrollToEnd(Direction.UP),
        "Zum Anfang" to Command.ScrollToEnd(Direction.UP),
        "Bis zum Anfang scrollen" to Command.ScrollToEnd(Direction.UP),
        "Scroll to the top" to Command.ScrollToEnd(Direction.UP),
        "Back to the top" to Command.ScrollToEnd(Direction.UP),
        "Eine Seite nach unten" to page(Direction.DOWN),
        "Seite hoch" to page(Direction.UP),
        "Page down" to page(Direction.DOWN),
        "Next page" to page(Direction.DOWN),
        "Previous page" to page(Direction.UP),
        "Drei Seiten runter" to page(Direction.DOWN, 3),
        "Wisch nach links" to swipe(Direction.LEFT),
        "Nach links wischen" to swipe(Direction.LEFT),
        "Swipe left" to swipe(Direction.LEFT),
        "Wische rechts" to swipe(Direction.RIGHT),
        "Dreimal nach links wischen" to swipe(Direction.LEFT, 3),
        "Swipe right twice" to swipe(Direction.RIGHT, 2),
    )

    private val media: List<Pair<String, Command>> = listOf(
        "Lauter" to volume(VolumeChange.LOUDER),
        "Mach lauter" to volume(VolumeChange.LOUDER),
        "Lautstärke hoch" to volume(VolumeChange.LOUDER),
        "Louder" to volume(VolumeChange.LOUDER),
        "Volume up" to volume(VolumeChange.LOUDER),
        "Turn it up" to volume(VolumeChange.LOUDER),
        "Dreimal lauter" to volume(VolumeChange.LOUDER, 3),
        "Volume up 5 times" to volume(VolumeChange.LOUDER, 5),
        "Leiser" to volume(VolumeChange.QUIETER),
        "Mach leiser." to volume(VolumeChange.QUIETER),
        "Lautstärke runter" to volume(VolumeChange.QUIETER),
        "Quieter" to volume(VolumeChange.QUIETER),
        "Volume down" to volume(VolumeChange.QUIETER),
        "Zweimal leiser" to volume(VolumeChange.QUIETER, 2),
        "Stumm" to volume(VolumeChange.MUTE),
        "Stummschalten" to volume(VolumeChange.MUTE),
        "Ton aus" to volume(VolumeChange.MUTE),
        "Mute" to volume(VolumeChange.MUTE),
        "Ton an" to volume(VolumeChange.UNMUTE),
        "Ton wieder an" to volume(VolumeChange.UNMUTE),
        "Unmute" to volume(VolumeChange.UNMUTE),
        "Pause" to Command.Media(MediaKey.PAUSE),
        "Musik pausieren" to Command.Media(MediaKey.PAUSE),
        "Musik anhalten" to Command.Media(MediaKey.PAUSE),
        "Stopp" to Command.Media(MediaKey.PAUSE),
        "Pause the music" to Command.Media(MediaKey.PAUSE),
        "Play" to Command.Media(MediaKey.PLAY),
        "Abspielen" to Command.Media(MediaKey.PLAY),
        "Weiter abspielen" to Command.Media(MediaKey.PLAY),
        "Resume" to Command.Media(MediaKey.PLAY),
        "Nächstes Lied" to Command.Media(MediaKey.NEXT),
        "Nächster Titel" to Command.Media(MediaKey.NEXT),
        "Next track" to Command.Media(MediaKey.NEXT),
        "Überspringen" to Command.Media(MediaKey.NEXT),
        "Vorheriges Lied" to Command.Media(MediaKey.PREVIOUS),
        "Letztes Lied" to Command.Media(MediaKey.PREVIOUS),
        "Previous song" to Command.Media(MediaKey.PREVIOUS),
    )

    private val system: List<Pair<String, Command>> = listOf(
        "Taschenlampe an" to Command.Torch(true),
        "Mach die Taschenlampe an" to Command.Torch(true),
        "Taschenlampe einschalten" to Command.Torch(true),
        "Flashlight on" to Command.Torch(true),
        "Turn on the flashlight" to Command.Torch(true),
        "Taschenlampe aus" to Command.Torch(false),
        "Turn off the torch" to Command.Torch(false),
        "Taschenlampe" to Command.Torch(null),
        "WLAN" to Command.OpenSettings(SettingsPage.WIFI),
        "WLAN-Einstellungen" to Command.OpenSettings(SettingsPage.WIFI),
        "Öffne die WLAN Einstellungen" to Command.OpenSettings(SettingsPage.WIFI),
        "Wi-Fi settings" to Command.OpenSettings(SettingsPage.WIFI),
        "WLAN aus" to Command.OpenSettings(SettingsPage.WIFI),
        "Bluetooth" to Command.OpenSettings(SettingsPage.BLUETOOTH),
        "Bluetooth-Einstellungen öffnen" to Command.OpenSettings(SettingsPage.BLUETOOTH),
        "Toneinstellungen" to Command.OpenSettings(SettingsPage.SOUND),
        "Sound settings" to Command.OpenSettings(SettingsPage.SOUND),
        "Display-Einstellungen" to Command.OpenSettings(SettingsPage.DISPLAY),
        "Helligkeit" to Command.OpenSettings(SettingsPage.DISPLAY),
        "Akku" to Command.OpenSettings(SettingsPage.BATTERY),
        "Battery settings" to Command.OpenSettings(SettingsPage.BATTERY),
    )

    private val text: List<Pair<String, Command>> = listOf(
        "Lösche das letzte Wort" to edit(TextEdit.DELETE_WORD),
        "Letztes Wort löschen" to edit(TextEdit.DELETE_WORD),
        "Wort löschen" to edit(TextEdit.DELETE_WORD),
        "Delete the last word" to edit(TextEdit.DELETE_WORD),
        "Delete word" to edit(TextEdit.DELETE_WORD),
        "Drei Wörter löschen" to edit(TextEdit.DELETE_WORD, 3),
        "Lösche 3 Wörter" to edit(TextEdit.DELETE_WORD, 3),
        "Delete the last two words" to edit(TextEdit.DELETE_WORD, 2),
        "Alles markieren" to edit(TextEdit.SELECT_ALL),
        "Alles auswählen" to edit(TextEdit.SELECT_ALL),
        "Select all" to edit(TextEdit.SELECT_ALL),
        "Kopieren" to edit(TextEdit.COPY),
        "Copy" to edit(TextEdit.COPY),
        "Ausschneiden" to edit(TextEdit.CUT),
        "Cut" to edit(TextEdit.CUT),
        "Einfügen" to edit(TextEdit.PASTE),
        "Paste" to edit(TextEdit.PASTE),
        "Neue Zeile" to edit(TextEdit.NEW_LINE),
        "Zeilenumbruch" to edit(TextEdit.NEW_LINE),
        "New line" to edit(TextEdit.NEW_LINE),
    )

    private val polite: List<Pair<String, Command>> = listOf(
        "Bitte zurück" to back,
        "Äh, zurück bitte" to back,
        "Amber, scroll runter" to down,
        "Hey Amber, go back please" to back,
        "Kannst du bitte WhatsApp öffnen?" to app("whatsapp"),
        "Kannst du mal runterscrollen?" to down,
        "Could you scroll down a little" to down,
        "Scroll mal ein bisschen runter" to down,
        "Mach mal lauter" to volume(VolumeChange.LOUDER),
    )

    /** Found by a probe of phrasings outside this table (2026-10-31): kept so they stay understood. */
    private val probed: List<Pair<String, Command>> = listOf(
        "Geh zurück zum Homescreen" to home,
        "Zeig mir den Akku" to Command.OpenSettings(SettingsPage.BATTERY),
        "Nochmal zurück" to back,
        "Bildschirmeinstellungen" to Command.OpenSettings(SettingsPage.DISPLAY),
        "Noch einmal runter" to down,
        "Go to the top" to Command.ScrollToEnd(Direction.UP),
        "Skip this song" to Command.Media(MediaKey.NEXT),
        "Lock my phone" to nav(GlobalAction.LOCK_SCREEN),
        "Close quick settings" to nav(GlobalAction.CLOSE_NOTIFICATIONS),
        "Delete the last 3 words" to edit(TextEdit.DELETE_WORD, 3),
        "Ein Lied zurück" to Command.Media(MediaKey.PREVIOUS),
        "Bluetooth an" to Command.OpenSettings(SettingsPage.BLUETOOTH),
        "Wähle alles aus" to edit(TextEdit.SELECT_ALL),
        "Schneide das aus" to edit(TextEdit.CUT),
        "Lautstärke erhöhen" to volume(VolumeChange.LOUDER),
    )

    private val corpus = v1Misses + navigate + openApps + scrolling + media + system + text + polite + probed

    /** Ordinary sentences, and what Amber must never do: send, pay, confirm, empty a field. */
    private val notCommands = listOf(
        "Ich bin gleich zu Hause",
        "Wir sehen uns morgen um acht",
        "Kannst du mir das bitte schicken",
        "Zurück zum Thema: die Lieferung kommt morgen",
        "Ich habe zweimal angerufen",
        "Das Wetter ist schön heute",
        "Ruf Mama an",
        "öffne",
        "",
        "Schick ihm eine Nachricht",
        "Send the message",
        "Senden",
        "Bestätigen",
        "Lösche alles",
        "Alles löschen",
        "Delete everything",
        "Bezahlen",
        "Hoch und runter",
        "Zweimal Screenshot",
        "0 mal runter",
        "Wort",
        "Seite",
        "Licht an",
        "Bildschirm aus",
    )

    private fun mismatches(cases: List<Pair<String, Command>>): List<String> = cases.mapNotNull { (heard, expected) ->
        val got = parse(heard)
        if (got == Understanding.Understood(expected)) null else "\"$heard\": expected $expected, got $got"
    }

    @Test
    fun `every phrasing v1 missed is understood`() {
        val wrong = mismatches(v1Misses)
        assertTrue(wrong.isEmpty(), "${wrong.size} of ${v1Misses.size} wrong:\n" + wrong.joinToString("\n"))
    }

    @Test
    fun `the whole corpus parses as expected`() {
        val wrong = mismatches(corpus)
        assertTrue(wrong.isEmpty(), "${wrong.size} of ${corpus.size} wrong:\n" + wrong.joinToString("\n"))
    }

    @Test
    fun `the corpus is large`() = assertTrue(corpus.size >= 150, "only ${corpus.size} phrasings")

    @Test
    fun `ordinary sentences and unsafe asks stay not understood`() {
        val wrong = notCommands.filter { parse(it) !is Understanding.NotUnderstood }.map { "\"$it\" -> ${parse(it)}" }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `a bare name opens only an exact or uniquely started app`() {
        assertIs<Understanding.Understood>(parse("Whats"))
        listOf("Maps", "Tele", "Telegramm", "Gmial").forEach { heard ->
            assertIs<Understanding.NotUnderstood>(parse(heard), "\"$heard\"")
        }
        assertIs<Understanding.NotUnderstood>(VoiceCommands.parse("WhatsApp"), "no apps known, no bare name")
    }

    @Test
    fun `every example on the settings page is understood`() {
        val wrong = VoiceCommands.GROUPS.flatMap { it.examples }.flatMap { it.split(" · ") }
            .filter { parse(it) !is Understanding.Understood }
        assertTrue(wrong.isEmpty(), "not understood: $wrong")
    }

    @Test
    fun `the settings page groups commands by kind`() {
        assertTrue(
            VoiceCommands.GROUPS.map { it.title } == listOf("Navigate", "Apps", "Scroll & swipe", "Media", "System", "Text"),
            VoiceCommands.GROUPS.map { it.title }.toString(),
        )
    }
}
