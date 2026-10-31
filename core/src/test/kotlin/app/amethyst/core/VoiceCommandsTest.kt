package app.amethyst.core

import app.amethyst.core.commands.Command
import app.amethyst.core.commands.Direction
import app.amethyst.core.commands.GlobalAction
import app.amethyst.core.commands.Understanding
import app.amethyst.core.commands.VoiceCommands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class VoiceCommandsTest {

    private fun understood(heard: String): Command =
        assertIs<Understanding.Understood>(VoiceCommands.parse(heard), "\"$heard\"").command

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

    @Test
    fun `normalizing folds case, accents, sharp s and punctuation`() {
        assertEquals("offne strasse app", VoiceCommands.normalize("Öffne  Straße-App!"))
        assertEquals("whats app", VoiceCommands.normalize("What's App?"))
    }
}
