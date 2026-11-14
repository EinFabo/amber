package app.amethyst.core

import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppProfile
import app.amethyst.core.settings.AppRow
import app.amethyst.core.settings.AppRows
import app.amethyst.core.settings.BubbleSettings
import app.amethyst.core.settings.SettingsCodec
import app.amethyst.core.settings.WritingStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The Apps page: profiles and hidden apps side by side, each written to its own store. */
class AppRowsTest {

    private val whatsapp = AppProfile("com.whatsapp", "WhatsApp", WritingStyle.CASUAL)
    private val keep = AppProfile("com.google.android.keep", "Keep", WritingStyle.CONCISE)
    private val profiles = listOf(whatsapp, keep)

    @Test
    fun `apps with a profile come first, then those where only the bubble is hidden`() {
        assertEquals(
            listOf(
                AppRow("com.whatsapp", whatsapp, bubbleHidden = false),
                AppRow("com.google.android.keep", keep, bubbleHidden = true),
                AppRow("com.game", profile = null, bubbleHidden = true),
                AppRow("com.other", profile = null, bubbleHidden = true),
            ),
            AppRows.of(profiles, hiddenIn = listOf("com.game", "com.google.android.keep", "com.other")),
        )
    }

    @Test
    fun `an app is listed once, however often it is stored`() {
        val rows = AppRows.of(profiles + whatsapp.copy(style = WritingStyle.FORMAL), hiddenIn = listOf("com.game", "com.game", "com.whatsapp"))
        assertEquals(listOf("com.whatsapp", "com.google.android.keep", "com.game"), rows.map { it.packageName })
        assertEquals(whatsapp, rows.first().profile)
    }

    @Test
    fun `nothing set is no row`() {
        assertEquals(emptyList(), AppRows.of(emptyList(), emptyList()))
        assertEquals(0 to 0, AppRows.counts(emptyList(), emptyList()))
    }

    @Test
    fun `the two numbers count each store by itself`() {
        // Keep has a style and a hidden bubble: it counts in both.
        assertEquals(2 to 2, AppRows.counts(profiles, listOf("com.game", "com.google.android.keep")))
        assertEquals(2 to 0, AppRows.counts(profiles, emptyList()))
        assertEquals(0 to 1, AppRows.counts(emptyList(), listOf("com.game", "com.game")))
    }

    @Test
    fun `hiding and showing the bubble writes the hidden apps only`() {
        val bubble = BubbleSettings(sizeDp = 64, hiddenIn = listOf("com.game"))
        assertEquals(bubble.copy(hiddenIn = listOf("com.game", "com.whatsapp")), AppRows.withBubble(bubble, "com.whatsapp", shown = false))
        assertEquals(bubble.copy(hiddenIn = emptyList()), AppRows.withBubble(bubble, "com.game", shown = true))
        // Twice the same is once.
        assertEquals(bubble, AppRows.withBubble(bubble, "com.game", shown = false))
        assertEquals(bubble, AppRows.withBubble(bubble, "com.whatsapp", shown = true))
    }

    @Test
    fun `a change on an app's page edits its profile`() {
        val edited = AppRows.edited(profiles, "com.whatsapp", "WhatsApp") { it.copy(style = WritingStyle.FORMAL) }
        assertEquals(listOf(whatsapp.copy(style = WritingStyle.FORMAL), keep), edited)
    }

    @Test
    fun `the first change on a hidden app's page gives it a profile`() {
        val edited = AppRows.edited(profiles, "com.game", "Game") { it.copy(instruction = "short") }
        assertEquals(profiles + AppProfile("com.game", "Game", instruction = "short"), edited)
    }

    @Test
    fun `a change that changes nothing makes no profile`() {
        assertSame(profiles, AppRows.edited(profiles, "com.game", "Game") { it.copy(style = WritingStyle.AS_SPOKEN) })
    }

    @Test
    fun `removing an app takes its profile and shows the bubble there again`() {
        val settings = AmethystSettings.defaults(TEST_PHONE).copy(
            profiles = profiles,
            bubble = BubbleSettings(hiddenIn = listOf("com.game", "com.google.android.keep")),
        )
        val withoutKeep = AppRows.removed(settings, "com.google.android.keep")
        assertEquals(listOf(whatsapp), withoutKeep.profiles)
        assertEquals(listOf("com.game"), withoutKeep.bubble.hiddenIn)
        // An app with a hidden bubble only leaves the page too.
        val withoutGame = AppRows.removed(withoutKeep, "com.game")
        assertEquals(listOf("com.whatsapp"), AppRows.of(withoutGame.profiles, withoutGame.bubble.hiddenIn).map { it.packageName })
        // Nothing else of the settings moves.
        assertEquals(settings.copy(profiles = listOf(whatsapp), bubble = settings.bubble.copy(hiddenIn = listOf("com.game"))), withoutKeep)
    }

    @Test
    fun `what the page writes is stored as before - two lists, no new field`() {
        val settings = AmethystSettings.defaults(TEST_PHONE).copy(profiles = profiles)
        val hidden = settings.copy(bubble = AppRows.withBubble(settings.bubble, "com.game", shown = false))
        val decoded = SettingsCodec.decode(SettingsCodec.encode(hidden), TEST_PHONE)
        assertEquals(listOf("com.game"), decoded.bubble.hiddenIn)
        assertEquals(profiles, decoded.profiles)
    }
}
