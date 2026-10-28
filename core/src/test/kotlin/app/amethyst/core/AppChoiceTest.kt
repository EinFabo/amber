package app.amethyst.core

import app.amethyst.core.apps.AppChoice
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.stats.DictationStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppChoiceTest {

    private data class App(val pkg: String, val label: String)

    private val apps = listOf(
        App("org.telegram.messenger", "Telegram"),
        App("com.google.android.gm", "Gmail"),
        App("com.whatsapp", "WhatsApp"),
        App("com.example.agenda", "Ágenda"),
        App("com.google.android.keep", "Keep Notes"),
    )

    @Test
    fun `the apps dictated in come first, most used first, the rest by name`() {
        val usage = mapOf("com.google.android.gm" to 3, "com.whatsapp" to 12, "gone.app" to 40)
        val grouped = AppChoice.group(apps, { it.pkg }, { it.label }, usage)
        assertEquals(listOf("WhatsApp", "Gmail"), grouped.frequent.map { it.label })
        // Accents do not push an app to the end.
        assertEquals(listOf("Ágenda", "Keep Notes", "Telegram"), grouped.rest.map { it.label })
    }

    @Test
    fun `only a few apps make the frequent group`() {
        val many = (1..10).map { App("p$it", "App $it") }
        val usage = many.associate { it.pkg to 1 }
        val grouped = AppChoice.group(many, { it.pkg }, { it.label }, usage)
        assertEquals(AppChoice.FREQUENT_MAX, grouped.frequent.size)
        assertEquals(10, grouped.frequent.size + grouped.rest.size)
    }

    @Test
    fun `search finds names, packages and words in any order, ignoring accents`() {
        assertTrue(AppChoice.matches("tele", "Telegram", "org.telegram.messenger"))
        assertTrue(AppChoice.matches("gm", "Gmail", "com.google.android.gm"))
        assertTrue(AppChoice.matches("agenda", "Ágenda", "com.example.agenda"))
        assertTrue(AppChoice.matches("notes keep", "Keep Notes", "com.google.android.keep"))
        assertTrue(AppChoice.matches("  ", "Keep Notes", "com.google.android.keep"))
        assertFalse(AppChoice.matches("signal", "Telegram", "org.telegram.messenger"))
    }

    @Test
    fun `usage counts dictations per app and skips unknown ones`() {
        val stats = listOf("a", "b", "a", null).mapIndexed { i, pkg ->
            DictationStat(
                at = i.toLong(), day = 0, engine = "e", trust = TrustLevel.ON_DEVICE,
                words = 1, audioMs = 1, waitMs = 1, app = pkg,
            )
        }
        assertEquals(mapOf("a" to 2, "b" to 1), AppChoice.usage(stats))
    }
}
