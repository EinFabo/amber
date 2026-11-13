package app.amethyst.core

import app.amethyst.core.settings.AppCleanupMode
import app.amethyst.core.settings.AppKind
import app.amethyst.core.settings.AppProfile
import app.amethyst.core.settings.AppProfiles
import app.amethyst.core.settings.KnownApps
import app.amethyst.core.settings.WritingStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KnownAppsTest {

    private fun styleOf(packageName: String): WritingStyle =
        AppProfiles.defaultsFor(setOf(packageName)).single().style

    @Test
    fun `the catalog names each app once`() {
        assertEquals(KnownApps.ALL.size, KnownApps.ALL.map { it.packageName }.toSet().size)
        assertEquals(KnownApps.ALL.size, KnownApps.ALL.map { it.label }.toSet().size)
        assertTrue(KnownApps.ALL.size >= 30)
        assertTrue(KnownApps.ALL.all { it.label.isNotBlank() && it.packageName.contains('.') })
    }

    @Test
    fun `every kind of app is in it`() {
        assertEquals(AppKind.entries.toSet(), KnownApps.ALL.map { it.kind }.toSet())
    }

    @Test
    fun `messengers casual, mail formal, notes concise`() {
        assertEquals(WritingStyle.CASUAL, styleOf("com.whatsapp"))
        assertEquals(WritingStyle.CASUAL, styleOf("org.thoughtcrime.securesms"))
        assertEquals(WritingStyle.FORMAL, styleOf("com.google.android.gm"))
        assertEquals(WritingStyle.FORMAL, styleOf("com.microsoft.office.outlook"))
        assertEquals(WritingStyle.CONCISE, styleOf("com.google.android.keep"))
    }

    @Test
    fun `a terminal gets the text as recognized`() {
        val termux = AppProfiles.defaultsFor(setOf("com.termux")).single()
        assertEquals(AppCleanupMode.OFF, termux.appCleanup.mode)
        assertEquals(WritingStyle.AS_SPOKEN, termux.style)
        // Everything else follows the cleanup for every app.
        assertTrue(KnownApps.ALL.filter { it.kind != AppKind.TERMINAL }.all { KnownApps.profile(it).appCleanup.mode == AppCleanupMode.DEFAULT })
    }

    @Test
    fun `only installed apps get a style`() {
        assertTrue(AppProfiles.defaultsFor(emptySet()).isEmpty())
        assertTrue(AppProfiles.defaultsFor(setOf("org.example.unknown")).isEmpty())
        val defaults = AppProfiles.defaultsFor(setOf("com.google.android.keep", "com.whatsapp", "org.example.unknown"))
        // In the catalog's order, whatever order the phone lists them in.
        assertEquals(listOf("com.whatsapp", "com.google.android.keep"), defaults.map { it.packageName })
        assertEquals("WhatsApp", defaults.first().label)
    }

    @Test
    fun `an app with a profile gets no default`() {
        val own = listOf(AppProfile("com.whatsapp", "WhatsApp", WritingStyle.FORMAL, instruction = "sign with F."))
        val installed = setOf("com.whatsapp", "com.google.android.gm")
        assertEquals(listOf("com.google.android.gm"), AppProfiles.defaultsFor(installed, own).map { it.packageName })
        val merged = AppProfiles.withDefaults(own, installed)
        assertEquals(own.single(), merged.first())
        assertEquals(2, merged.size)
        // A second time adds nothing.
        assertEquals(merged, AppProfiles.withDefaults(merged, installed))
    }

    @Test
    fun `the apps every install used to start with are all known, in the same style`() {
        AppProfiles.DEFAULTS.forEach { old ->
            assertEquals(old.style, styleOf(old.packageName), old.packageName)
        }
    }
}
