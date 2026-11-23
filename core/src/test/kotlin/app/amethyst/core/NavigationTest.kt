package app.amethyst.core

import app.amethyst.core.ui.Navigation
import app.amethyst.core.ui.Screen
import app.amethyst.core.ui.SettingsPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationTest {

    private val deep = listOf(Screen.Home, Screen.Settings, Screen.Page(SettingsPage.RECOGNITION), Screen.EngineDetail("whisper"))

    @Test
    fun `a fresh install starts in the introduction, everyone else on Home`() {
        assertEquals(listOf(Screen.Onboarding), Navigation.start(introduce = true))
        assertEquals(listOf(Screen.Home), Navigation.start(introduce = false))
    }

    @Test
    fun `open puts a screen on top and back takes it off again`() {
        val opened = Navigation.open(listOf(Screen.Home), Screen.Settings)
        assertEquals(listOf(Screen.Home, Screen.Settings), opened)
        assertEquals(listOf(Screen.Home), Navigation.back(opened))
    }

    @Test
    fun `back never leaves the stack empty`() {
        assertEquals(listOf(Screen.Home), Navigation.back(listOf(Screen.Home)))
    }

    @Test
    fun `a second tap on the same row opens the page once`() {
        val once = Navigation.open(listOf(Screen.Home), Screen.Settings)
        assertEquals(once, Navigation.open(once, Screen.Settings))
    }

    @Test
    fun `the quick menu's app page comes with the way back through the app styles and Settings`() {
        assertEquals(
            listOf(Screen.Home, Screen.Settings, Screen.Page(SettingsPage.APP_STYLES), Screen.AppStyle("org.chat")),
            Navigation.toAppPage(deep, "org.chat"),
        )
    }

    @Test
    fun `the quick menu never leads out of the introduction`() {
        val introduction = listOf(Screen.Onboarding)
        assertEquals(introduction, Navigation.toAppPage(introduction, "org.chat"))
        // Replayed from About: still the introduction until it is left.
        val replay = listOf(Screen.Home, Screen.Settings, Screen.Page(SettingsPage.ABOUT), Screen.Onboarding)
        assertEquals(replay, Navigation.toAppPage(replay, "org.chat"))
    }

    @Test
    fun `back is a step towards Home, anything else is forward`() {
        assertTrue(Navigation.isBack(deep, deep.dropLast(1)))
        assertTrue(Navigation.isBack(deep, listOf(Screen.Home)))
        assertFalse(Navigation.isBack(deep.dropLast(1), deep))
        // The introduction finished: a new place, not a way back.
        assertFalse(Navigation.isBack(listOf(Screen.Onboarding), listOf(Screen.Home)))
        // The quick menu's app page over a deeper stack: shorter, but not the way that was come.
        val deeper = deep + Screen.Page(SettingsPage.FALLBACK)
        assertFalse(Navigation.isBack(deeper, Navigation.toAppPage(deeper, "org.chat")))
    }

    @Test
    fun `the stack survives being saved as text`() {
        val stack = listOf(
            Screen.Home,
            Screen.Settings,
            Screen.Page(SettingsPage.APP_STYLES),
            Screen.AppStyle("org.chat"),
            Screen.Onboarding,
        )
        assertEquals(stack, Navigation.restore(Navigation.save(stack), introduce = false))
        assertEquals(deep, Navigation.restore(Navigation.save(deep), introduce = false))
    }

    @Test
    fun `an engine id with a slash in it is kept whole`() {
        val stack = listOf(Screen.Home, Screen.EngineDetail("server/a:b"))
        assertEquals(stack, Navigation.restore(Navigation.save(stack), introduce = false))
    }

    @Test
    fun `a saved stack that cannot be read starts over instead of failing`() {
        val home = Navigation.start(introduce = false)
        assertEquals(home, Navigation.restore(null, introduce = false))
        assertEquals(home, Navigation.restore(emptyList(), introduce = false))
        assertEquals(home, Navigation.restore(listOf("home", "page/NO_SUCH_PAGE"), introduce = false))
        assertEquals(home, Navigation.restore(listOf("home", "somewhere"), introduce = false))
        assertEquals(home, Navigation.restore(listOf("home", "engine/"), introduce = false))
        // Every stack starts at Home or in the introduction.
        assertEquals(home, Navigation.restore(listOf("settings"), introduce = false))
        // A fresh install starts in the introduction, as it would without a saved stack.
        assertEquals(Navigation.start(introduce = true), Navigation.restore(listOf("somewhere"), introduce = true))
    }

    @Test
    fun `each place in the stack keeps its own state, even the same page twice`() {
        val twice = listOf(Screen.Home, Screen.Page(SettingsPage.CLEANUP), Screen.EngineDetail("a"), Screen.Page(SettingsPage.CLEANUP))
        val keys = Navigation.keys(twice)
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(keys.last(), Navigation.topKey(twice))
        // Opening further keeps the keys below as they were.
        assertEquals(keys, Navigation.keys(twice + Screen.Settings).dropLast(1))
    }

    @Test
    fun `what was taken off the stack is forgotten, what is still there is kept`() {
        assertEquals(listOf(Navigation.topKey(deep)), Navigation.forgotten(deep, Navigation.back(deep)))
        assertEquals(emptyList(), Navigation.forgotten(deep.dropLast(1), deep))
        // Replaced as a whole: Home and Settings stay what they were, the rest is new.
        val appPage = Navigation.toAppPage(deep, "org.chat")
        assertEquals(Navigation.keys(deep).drop(2), Navigation.forgotten(deep, appPage))
    }
}
