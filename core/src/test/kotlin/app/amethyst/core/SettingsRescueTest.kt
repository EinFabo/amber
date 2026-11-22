package app.amethyst.core

import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.FallbackMode
import app.amethyst.core.settings.FallbackSettings
import app.amethyst.core.settings.SettingsCodec
import app.amethyst.core.settings.SettingsRead
import app.amethyst.core.settings.SettingsRescue
import app.amethyst.core.settings.SettingsRescue.Stored
import app.amethyst.core.settings.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsRescueTest {

    private val mine = AmethystSettings.defaults(TEST_PHONE).copy(
        onboardingDone = true,
        fallback = FallbackSettings(FallbackMode.OFF),
    )
    private val saved = SettingsCodec.encode(mine)

    /** [saved] with one value replaced, as a newer build or a damaged file would leave it. */
    private fun savedWith(old: String, new: String): String {
        assertTrue(old in saved, "fixture: $old not in the encoded settings")
        return saved.replaceFirst(old, new)
    }

    @Test
    fun `an option this build does not know takes the field's default and keeps the rest`() {
        val newer = savedWith("\"mode\": \"OFF\"", "\"mode\": \"SOMETHING_NEWER\"")
        val read = SettingsCodec.read(newer, TEST_PHONE)
        assertIs<SettingsRead.Saved>(read)
        assertEquals(FallbackMode.AUTOMATIC, read.settings.fallback.mode)
        assertTrue(read.settings.onboardingDone)
    }

    @Test
    fun `a null where a value with a default belongs takes the default`() {
        val read = SettingsCodec.read(savedWith("\"theme\": \"SYSTEM\"", "\"theme\": null"), TEST_PHONE)
        assertIs<SettingsRead.Saved>(read)
        assertEquals(ThemeMode.SYSTEM, read.settings.ui.theme)
        assertTrue(read.settings.onboardingDone)
    }

    @Test
    fun `a malformed field is unreadable, not a first launch`() {
        val broken = savedWith("\"onboardingDone\": true", "\"onboardingDone\": \"maybe\"")
        val read = SettingsCodec.read(broken, TEST_PHONE)
        assertIs<SettingsRead.Unreadable>(read)
        assertEquals(AmethystSettings.defaults(TEST_PHONE), read.settings)
        assertTrue("onboardingDone" in read.reason, read.reason)
        assertTrue("maybe" !in read.reason, "no value in the reason: ${read.reason}")
        assertIs<SettingsRead.Fresh>(SettingsCodec.read("", TEST_PHONE))
    }

    @Test
    fun `readable settings change nothing`() {
        val outcome = SettingsRescue.of(Stored(saved, null), TEST_PHONE)
        assertEquals(mine, outcome.settings)
        assertNull(outcome.store)
        assertNull(outcome.event)
        assertNull(SettingsRescue.of(Stored(null, null), TEST_PHONE).store)
    }

    @Test
    fun `unreadable settings go aside before anything is written over them`() {
        val broken = savedWith("\"onboardingDone\": true", "\"onboardingDone\": 7")
        val outcome = SettingsRescue.of(Stored(broken, null), TEST_PHONE)
        assertEquals(AmethystSettings.defaults(TEST_PHONE), outcome.settings)
        assertEquals(Stored(broken, broken), outcome.store)
        assertIs<SettingsRescue.Event.KeptAside>(outcome.event)
        // Kept already: nothing to store, nothing to say again.
        val again = SettingsRescue.of(Stored(broken, broken), TEST_PHONE)
        assertNull(again.store)
        assertNull(again.event)
    }

    @Test
    fun `changes made meanwhile leave the copy alone while it cannot be read`() {
        val broken = "{ \"engines\": 3 }"
        val meanwhile = SettingsCodec.encode(AmethystSettings.defaults(TEST_PHONE).copy(onboardingDone = true))
        val outcome = SettingsRescue.of(Stored(meanwhile, broken), TEST_PHONE)
        assertTrue(outcome.settings.onboardingDone)
        assertNull(outcome.store)
        assertNull(outcome.event)
    }

    @Test
    fun `a copy that reads again comes back in place of the defaults saved meanwhile`() {
        val meanwhile = SettingsCodec.encode(AmethystSettings.defaults(TEST_PHONE).copy(onboardingDone = true))
        val outcome = SettingsRescue.of(Stored(meanwhile, saved), TEST_PHONE)
        assertEquals(mine, outcome.settings)
        assertEquals(Stored(saved, null), outcome.store)
        assertEquals(SettingsRescue.Event.Restored, outcome.event)
    }

    @Test
    fun `newer unreadable settings replace an older copy`() {
        val older = "{ \"engines\": 3 }"
        val newer = "{ \"engines\": 4 }"
        val outcome = SettingsRescue.of(Stored(newer, older), TEST_PHONE)
        assertEquals(Stored(newer, newer), outcome.store)
    }
}
