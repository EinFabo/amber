package app.amethyst.core

import app.amethyst.core.apps.AppsSeenInFront
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppsSeenInFrontTest {

    @Test
    fun `an app seen in front is known, nothing else and nothing blank`() {
        val seen = AppsSeenInFront()
        seen.add("org.thoughtcrime.securesms")
        seen.add("")
        seen.add(null)
        assertTrue("org.thoughtcrime.securesms" in seen)
        assertFalse("com.whatsapp" in seen)
        assertFalse("" in seen)
        assertFalse(null in seen)
    }

    @Test
    fun `the oldest app goes first, one seen again stays`() {
        val seen = AppsSeenInFront()
        seen.add("first")
        seen.add("second")
        repeat(AppsSeenInFront.MAX - 2) { seen.add("app$it") }
        // Seen again: now the latest, so "second" is the oldest.
        seen.add("first")
        seen.add("one more")
        assertTrue("first" in seen)
        assertFalse("second" in seen)
        assertTrue("one more" in seen)
    }
}
