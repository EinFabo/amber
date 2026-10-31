package app.amethyst.core

import app.amethyst.core.commands.AppMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AppMatchTest {

    private val apps = listOf(
        "WhatsApp", "Telegram", "Telefon", "Google", "Google Maps", "Gmail", "Einstellungen", "Straßenbahn Plan", "Café Finder",
    )

    private fun find(name: String) = AppMatch.find(name, apps) { it }

    private fun found(name: String): String = assertIs<AppMatch.Found<String>>(find(name), name).app

    @Test
    fun `the exact name wins, spaces and case aside`() {
        assertEquals("WhatsApp", found("whatsapp"))
        assertEquals("WhatsApp", found("whats app"))
        assertEquals("Google", found("google"))
        assertEquals("Google Maps", found("google maps"))
    }

    @Test
    fun `a unique start or a word inside finds the app`() {
        assertEquals("Einstellungen", found("einst"))
        assertEquals("Google Maps", found("maps"))
        assertEquals("Straßenbahn Plan", found("strassenbahn"))
        assertEquals("Café Finder", found("cafe"))
    }

    @Test
    fun `a mis-heard name is still found`() {
        assertEquals("Telegram", found("telegramm"))
        assertEquals("WhatsApp", found("whats up"))
        assertEquals("Gmail", found("g mail"))
    }

    @Test
    fun `several apps in the best tier are never guessed`() {
        val tele = assertIs<AppMatch.Ambiguous<String>>(find("tele"))
        assertEquals(listOf("Telegram", "Telefon"), tele.candidates)
        assertIs<AppMatch.Ambiguous<String>>(find("goo"))
    }

    @Test
    fun `strict matching knows only the exact name and a unique start`() {
        fun strict(name: String) = AppMatch.find(name, apps, strict = true) { it }
        assertEquals(AppMatch.Found("WhatsApp"), strict("whatsapp"))
        assertEquals(AppMatch.Found("Einstellungen"), strict("einst"))
        assertIs<AppMatch.Ambiguous<String>>(strict("tele"))
        assertIs<AppMatch.NotFound>(strict("maps"), "a word inside is not enough")
        assertIs<AppMatch.NotFound>(strict("telegramm"), "nor a near miss")
    }

    @Test
    fun `short or unknown names find nothing`() {
        assertIs<AppMatch.NotFound>(find("xyz"))
        assertIs<AppMatch.NotFound>(find("ga"))
        assertIs<AppMatch.NotFound>(find("kalender"))
        assertIs<AppMatch.NotFound>(find(""))
    }
}
