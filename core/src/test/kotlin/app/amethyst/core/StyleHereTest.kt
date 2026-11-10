package app.amethyst.core

import app.amethyst.core.settings.AppCleanup
import app.amethyst.core.settings.AppCleanupMode
import app.amethyst.core.settings.AppProfile
import app.amethyst.core.settings.AppProfiles
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.WritingStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The quick menu's "Next style", "Style here" and "App page": an app's profile from the bubble. */
class StyleHereTest {

    @Test
    fun `the styles go round in the order of the app page`() {
        assertEquals(WritingStyle.CASUAL, AppProfiles.nextStyle(WritingStyle.AS_SPOKEN))
        assertEquals(WritingStyle.FORMAL, AppProfiles.nextStyle(WritingStyle.CASUAL))
        assertEquals(WritingStyle.CONCISE, AppProfiles.nextStyle(WritingStyle.FORMAL))
        assertEquals(WritingStyle.AS_SPOKEN, AppProfiles.nextStyle(WritingStyle.CONCISE))
    }

    @Test
    fun `next style keeps the rest of the profile`() {
        val own = CleanupChoice("groq", "llama")
        val whatsapp = AppProfile("com.whatsapp", "WhatsApp", WritingStyle.CASUAL, "sign with Fabi", AppCleanup(AppCleanupMode.MODEL, own))
        val next = AppProfiles.withNextStyle(listOf(whatsapp), "com.whatsapp", "WhatsApp (label ignored)").single()
        assertEquals(whatsapp.copy(style = WritingStyle.FORMAL), next)
    }

    @Test
    fun `next style in an app without a profile adds one, one step on from as spoken`() {
        val next = AppProfiles.withNextStyle(AppProfiles.DEFAULTS, "org.example.chat", "Chat")
        assertEquals(AppProfiles.DEFAULTS, next.dropLast(1))
        assertEquals(AppProfile("org.example.chat", "Chat", WritingStyle.CASUAL), next.last())
    }

    @Test
    fun `a profile is made once, as the cleanup switch makes it`() {
        val made = AppProfiles.ensure(AppProfiles.DEFAULTS, "org.example.chat", "Chat")
        assertEquals(AppProfile("org.example.chat", "Chat", WritingStyle.AS_SPOKEN), made.last())
        assertSame(made, AppProfiles.ensure(made, "org.example.chat", "Other name"))
        assertSame(AppProfiles.DEFAULTS, AppProfiles.ensure(AppProfiles.DEFAULTS, "com.whatsapp", "WhatsApp"))
    }
}
