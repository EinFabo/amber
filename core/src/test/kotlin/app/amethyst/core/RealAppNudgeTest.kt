package app.amethyst.core

import app.amethyst.core.onboarding.RealAppNudge
import app.amethyst.core.onboarding.RealAppNudges
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.stats.DictationStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RealAppNudgeTest {

    private fun stat(app: String?) =
        DictationStat(at = 0, day = 0, engine = "local-parakeet", trust = TrustLevel.ON_DEVICE, words = 5, audioMs = 2_000, waitMs = 500, app = app)

    private val phone = mapOf(
        "com.google.android.gm" to "Gmail",
        "com.google.android.apps.messaging" to "Messages",
        "com.whatsapp" to "WhatsApp",
        "com.termux" to "Termux",
        "org.example.game" to "A game",
    )

    @Test
    fun `a messenger on the phone is named, in the known list's order`() {
        assertEquals(RealAppNudge("WhatsApp"), RealAppNudges.of(emptyList(), statsKept = true, bubbleCanShow = true, installed = phone))
        assertEquals("Messages", RealAppNudges.appName(phone - "com.whatsapp"))
    }

    @Test
    fun `without a messenger mail comes next, then notes, never a terminal`() {
        assertEquals("Gmail", RealAppNudges.appName(mapOf("com.google.android.gm" to "Gmail", "com.google.android.keep" to "Keep")))
        assertEquals("Keep", RealAppNudges.appName(mapOf("com.termux" to "Termux", "com.google.android.keep" to "Keep")))
        assertNull(RealAppNudges.appName(mapOf("com.termux" to "Termux")))
    }

    @Test
    fun `the launcher's name wins, the known one stands in for a blank one`() {
        assertEquals("Nachrichten", RealAppNudges.appName(mapOf("com.samsung.android.messaging" to "Nachrichten")))
        assertEquals("Signal", RealAppNudges.appName(mapOf("org.thoughtcrime.securesms" to " ")))
    }

    @Test
    fun `no known app on the phone - any app`() {
        assertEquals(RealAppNudge(null), RealAppNudges.of(emptyList(), true, true, mapOf("org.example.game" to "A game")))
        assertEquals(RealAppNudge(null), RealAppNudges.of(emptyList(), true, true, emptyMap()))
    }

    @Test
    fun `dictations in amber's own field keep it, the first one into another app ends it`() {
        assertEquals(RealAppNudge("WhatsApp"), RealAppNudges.of(listOf(stat(null), stat(null)), true, true, phone))
        assertNull(RealAppNudges.of(listOf(stat(null), stat("com.google.android.gm")), true, true, phone))
    }

    @Test
    fun `nothing while the statistics are off, the bubble cannot show, or the apps are still being read`() {
        assertNull(RealAppNudges.of(emptyList(), statsKept = false, bubbleCanShow = true, installed = phone))
        assertNull(RealAppNudges.of(emptyList(), statsKept = true, bubbleCanShow = false, installed = phone))
        assertNull(RealAppNudges.of(emptyList(), statsKept = true, bubbleCanShow = true, installed = null))
    }
}
