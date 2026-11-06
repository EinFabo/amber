package app.amethyst.core

import app.amethyst.core.settings.AppCleanup
import app.amethyst.core.settings.AppCleanupMode
import app.amethyst.core.settings.AppProfile
import app.amethyst.core.settings.AppProfiles
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.WritingStyle
import kotlin.test.Test
import kotlin.test.assertEquals

/** The quick menu's "Cleanup off here" / "Cleanup on here". */
class CleanupHereTest {

    @Test
    fun `cleanup here off keeps the app's style and its own model for later`() {
        val own = CleanupChoice("groq", "llama")
        val profiles = listOf(AppProfile("com.whatsapp", "WhatsApp", WritingStyle.CASUAL, appCleanup = AppCleanup(AppCleanupMode.MODEL, own)))
        val off = AppProfiles.withCleanupHere(profiles, "com.whatsapp", "WhatsApp", on = false)
        assertEquals(AppCleanup(AppCleanupMode.OFF, own), off.single().appCleanup)
        assertEquals(WritingStyle.CASUAL, off.single().style)
        val on = AppProfiles.withCleanupHere(off, "com.whatsapp", "WhatsApp", on = true)
        assertEquals(AppCleanup(AppCleanupMode.MODEL, own), on.single().appCleanup)
    }

    @Test
    fun `cleanup here on without an own model follows the default`() {
        val profiles = listOf(AppProfile("com.whatsapp", "WhatsApp", appCleanup = AppCleanup(AppCleanupMode.OFF)))
        val on = AppProfiles.withCleanupHere(profiles, "com.whatsapp", "WhatsApp", on = true)
        assertEquals(AppCleanup(AppCleanupMode.DEFAULT), on.single().appCleanup)
    }

    @Test
    fun `cleanup here off in an app without a style adds one for it`() {
        val off = AppProfiles.withCleanupHere(AppProfiles.DEFAULTS, "org.example.chat", "Chat", on = false)
        assertEquals(AppProfiles.DEFAULTS, off.dropLast(1))
        val added = off.last()
        assertEquals("org.example.chat", added.packageName)
        assertEquals("Chat", added.label)
        assertEquals(AppCleanupMode.OFF, added.appCleanup.mode)
        assertEquals(WritingStyle.AS_SPOKEN, added.style)
    }
}
