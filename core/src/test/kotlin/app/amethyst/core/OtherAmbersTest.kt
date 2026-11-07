package app.amethyst.core

import app.amethyst.core.onboarding.AmberVariant
import app.amethyst.core.onboarding.OtherAmbers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtherAmbersTest {

    private val service = "app.amethyst.accessibility.AmethystAccessibilityService"
    private val release = "app.amber/$service"
    private val debug = "app.amber.debug/$service"
    private val talkBack = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"

    @Test
    fun `nothing switched on finds nothing`() {
        assertTrue(OtherAmbers.find("app.amber", null).isEmpty())
        assertTrue(OtherAmbers.find("app.amber", "").isEmpty())
    }

    @Test
    fun `only Amber itself finds nothing`() {
        assertTrue(OtherAmbers.find("app.amber", release).isEmpty())
        assertTrue(OtherAmbers.find("app.amber.debug", "$talkBack:$debug").isEmpty())
    }

    @Test
    fun `the regular Amber sees the debug build and the other way round`() {
        val both = "$talkBack:$release:$debug"
        assertEquals(listOf(AmberVariant("app.amber.debug")), OtherAmbers.find("app.amber", both))
        assertEquals(listOf(AmberVariant("app.amber")), OtherAmbers.find("app.amber.debug", both))
    }

    @Test
    fun `the other one counts also while the own service is off`() {
        assertEquals(listOf(AmberVariant("app.amber.debug")), OtherAmbers.find("app.amber", debug))
    }

    @Test
    fun `a short class name is read relative to its package`() {
        // app.amber.debug/.x is app.amber.debug.x - not Amber's class, which lives in app.amethyst.
        assertTrue(OtherAmbers.find("app.amber", "app.amber.debug/.accessibility.AmethystAccessibilityService").isEmpty())
    }

    @Test
    fun `any build type's suffix is a variant, spaces around an entry do not matter`() {
        assertEquals(listOf(AmberVariant("app.amber.beta")), OtherAmbers.find("app.amber", " app.amber.beta/$service "))
    }

    @Test
    fun `malformed entries are skipped`() {
        val list = ":: $debug ::app.amber.debug:/$service:app.amber.debug/:app.amber.debug/a/b:$talkBack:"
        assertEquals(listOf(AmberVariant("app.amber.debug")), OtherAmbers.find("app.amber", list))
    }

    @Test
    fun `an entry twice is one variant`() {
        assertEquals(1, OtherAmbers.find("app.amber", "$debug:$debug").size)
    }

    @Test
    fun `other apps of the same prefix or another service are not Amber`() {
        assertTrue(OtherAmbers.find("app.amber", "app.amberfoo/$service").isEmpty())
        assertTrue(OtherAmbers.find("app.amber", "app.amber.debug/app.amethyst.accessibility.OtherService").isEmpty())
        assertTrue(OtherAmbers.find("app.amber", "com.example/$service").isEmpty())
    }

    @Test
    fun `matching is case-sensitive, as Android's is`() {
        assertTrue(OtherAmbers.find("app.amber", "App.Amber.Debug/$service").isEmpty())
        assertTrue(OtherAmbers.find("app.amber", "app.amber.debug/${service.lowercase()}").isEmpty())
        // The own package in another case is someone else's package - but not an Amber either.
        assertTrue(OtherAmbers.find("APP.AMBER", release).single().packageName == "app.amber")
    }

    @Test
    fun `labels read plainly`() {
        assertEquals("Amber", AmberVariant("app.amber").label)
        assertEquals("Amber (debug)", AmberVariant("app.amber.debug").label)
    }
}
