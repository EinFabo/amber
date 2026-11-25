package app.amethyst.core

import app.amethyst.core.onboarding.AmberVariant
import app.amethyst.core.onboarding.OtherAmbers
import app.amethyst.core.onboarding.OtherAmbersNotice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtherAmbersTest {

    private val service = "app.amethyst.accessibility.AmethystAccessibilityService"
    private val release = "com.embersay/$service"
    private val debug = "com.embersay.debug/$service"
    private val oldRelease = "app.amber/$service"
    private val oldDebug = "app.amber.debug/$service"
    private val talkBack = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"

    @Test
    fun `nothing switched on finds nothing`() {
        assertTrue(OtherAmbers.find("com.embersay", null).isEmpty())
        assertTrue(OtherAmbers.find("com.embersay", "").isEmpty())
    }

    @Test
    fun `only the app itself finds nothing`() {
        assertTrue(OtherAmbers.find("com.embersay", release).isEmpty())
        assertTrue(OtherAmbers.find("com.embersay.debug", "$talkBack:$debug").isEmpty())
    }

    @Test
    fun `the regular build sees the debug build and the other way round`() {
        val both = "$talkBack:$release:$debug"
        assertEquals(listOf(AmberVariant("com.embersay.debug")), OtherAmbers.find("com.embersay", both))
        assertEquals(listOf(AmberVariant("com.embersay")), OtherAmbers.find("com.embersay.debug", both))
    }

    @Test
    fun `the old Amber's builds count as other apps`() {
        assertEquals(
            listOf(AmberVariant("app.amber"), AmberVariant("app.amber.debug")),
            OtherAmbers.find("com.embersay", "$release:$oldRelease:$talkBack:$oldDebug"),
        )
        assertEquals(listOf(AmberVariant("app.amber")), OtherAmbers.find("com.embersay.debug", oldRelease))
    }

    @Test
    fun `the other one counts also while the own service is off`() {
        assertEquals(listOf(AmberVariant("com.embersay.debug")), OtherAmbers.find("com.embersay", debug))
    }

    @Test
    fun `a short class name is read relative to its package`() {
        // com.embersay.debug/.x is com.embersay.debug.x - not the app's class, which lives in app.amethyst.
        assertTrue(OtherAmbers.find("com.embersay", "com.embersay.debug/.accessibility.AmethystAccessibilityService").isEmpty())
        assertTrue(OtherAmbers.find("com.embersay", "app.amber/.accessibility.AmethystAccessibilityService").isEmpty())
    }

    @Test
    fun `any build type's suffix is a variant, spaces around an entry do not matter`() {
        assertEquals(listOf(AmberVariant("com.embersay.beta")), OtherAmbers.find("com.embersay", " com.embersay.beta/$service "))
    }

    @Test
    fun `malformed entries are skipped`() {
        val list = ":: $debug ::com.embersay.debug:/$service:com.embersay.debug/:com.embersay.debug/a/b:$talkBack:"
        assertEquals(listOf(AmberVariant("com.embersay.debug")), OtherAmbers.find("com.embersay", list))
    }

    @Test
    fun `an entry twice is one variant`() {
        assertEquals(1, OtherAmbers.find("com.embersay", "$debug:$debug").size)
    }

    @Test
    fun `other apps of the same prefix or another service are not this app`() {
        assertTrue(OtherAmbers.find("com.embersay", "com.embersayfoo/$service").isEmpty())
        assertTrue(OtherAmbers.find("com.embersay", "app.amberfoo/$service").isEmpty())
        assertTrue(OtherAmbers.find("com.embersay", "com.embersay.debug/app.amethyst.accessibility.OtherService").isEmpty())
        assertTrue(OtherAmbers.find("com.embersay", "com.example/$service").isEmpty())
    }

    @Test
    fun `matching is case-sensitive, as Android's is`() {
        assertTrue(OtherAmbers.find("com.embersay", "Com.sayEmber.Debug/$service").isEmpty())
        assertTrue(OtherAmbers.find("com.embersay", "App.Amber/$service").isEmpty())
        assertTrue(OtherAmbers.find("com.embersay", "com.embersay.debug/${service.lowercase()}").isEmpty())
        // The own package in another case is someone else's package - but not an sayEmber either.
        assertTrue(OtherAmbers.find("COM.EMBERSAY", release).single().packageName == "com.embersay")
    }

    @Test
    fun `a variant says whether it is the old app, and its build type`() {
        AmberVariant("com.embersay").let { assertFalse(it.old); assertNull(it.buildType) }
        AmberVariant("com.embersay.debug").let { assertFalse(it.old); assertEquals("debug", it.buildType) }
        AmberVariant("app.amber").let { assertTrue(it.old); assertNull(it.buildType) }
        AmberVariant("app.amber.debug").let { assertTrue(it.old); assertEquals("debug", it.buildType) }
    }

    @Test
    fun `the manifest's queries name exactly the old builds`() {
        assertEquals(listOf("app.amber", "app.amber.debug"), OtherAmbers.OLD_PACKAGES)
        assertTrue(OtherAmbers.OLD_PACKAGES.all(OtherAmbers::isOld))
    }

    // What Home shows.

    private val none = OtherAmbersNotice(emptyList(), emptyList())

    @Test
    fun `nothing installed and nothing on shows nothing`() {
        assertEquals(none, OtherAmbers.notice("com.embersay", emptyList(), emptyList(), dismissed = false))
    }

    @Test
    fun `the old app merely installed asks to uninstall it`() {
        assertEquals(
            OtherAmbersNotice(listOf(AmberVariant("app.amber")), emptyList()),
            OtherAmbers.notice("com.embersay", listOf("app.amber", "com.whatsapp"), emptyList(), dismissed = false),
        )
    }

    @Test
    fun `both old builds installed are both named`() {
        assertEquals(
            listOf(AmberVariant("app.amber"), AmberVariant("app.amber.debug")),
            OtherAmbers.notice("com.embersay.debug", listOf("app.amber", "app.amber.debug"), emptyList(), dismissed = false).uninstall,
        )
    }

    @Test
    fun `dismissed, the uninstall card stays away while the old app's service is off`() {
        assertEquals(none, OtherAmbers.notice("com.embersay", listOf("app.amber"), emptyList(), dismissed = true))
    }

    @Test
    fun `with the old app's service on, the card comes back even dismissed`() {
        val on = listOf(AmberVariant("app.amber"))
        assertEquals(OtherAmbersNotice(on, emptyList()), OtherAmbers.notice("com.embersay", listOf("app.amber"), on, dismissed = true))
        // Even when Android does not list it as installed (no <queries> entry for that build).
        val beta = listOf(AmberVariant("app.amber.beta"))
        assertEquals(OtherAmbersNotice(beta, emptyList()), OtherAmbers.notice("com.embersay", emptyList(), beta, dismissed = false))
    }

    @Test
    fun `the uninstall card names the old app once, the other card keeps the rest`() {
        val on = listOf(AmberVariant("com.embersay.debug"), AmberVariant("app.amber"))
        assertEquals(
            OtherAmbersNotice(listOf(AmberVariant("app.amber")), listOf(AmberVariant("com.embersay.debug"))),
            OtherAmbers.notice("com.embersay", listOf("app.amber"), on, dismissed = false),
        )
    }

    @Test
    fun `the new app's own builds are never asked to be uninstalled`() {
        val on = listOf(AmberVariant("com.embersay.debug"))
        assertEquals(
            OtherAmbersNotice(emptyList(), on),
            OtherAmbers.notice("com.embersay", listOf("com.embersay", "com.embersay.debug"), on, dismissed = false),
        )
    }

    @Test
    fun `a name listed twice is one`() {
        assertEquals(1, OtherAmbers.notice("com.embersay", listOf("app.amber", "app.amber"), emptyList(), dismissed = false).uninstall.size)
    }
}
