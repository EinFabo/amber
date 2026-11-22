package app.amethyst.core

import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.EngineConfig
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
    fun `a server of a kind this build does not know is left out and the rest is read`() {
        val withServers = mine.copy(
            engines = listOf(EngineConfig.fromPreset(EnginePresets.GROQ), EngineConfig.fromPreset(EnginePresets.OPENAI)),
            activeEngineId = EnginePresets.OPENAI.id,
        )
        val encoded = SettingsCodec.encode(withServers)
        val newer = encoded.replaceFirst("\"kind\": \"OPENAI_COMPATIBLE\"", "\"kind\": \"SOMETHING_NEWER\"")
        assertTrue(newer != encoded, "fixture: no engine kind replaced")
        val read = SettingsCodec.read(newer, TEST_PHONE)
        assertIs<SettingsRead.Partial>(read)
        assertEquals(listOf(EnginePresets.OPENAI.id), read.settings.engines.map { it.id })
        assertEquals(EnginePresets.OPENAI.id, read.settings.activeEngineId)
        assertTrue(read.settings.onboardingDone)
        assertTrue("engines" in read.reason, read.reason)
        assertTrue("SOMETHING_NEWER" !in read.reason, "no value in the reason: ${read.reason}")
    }

    @Test
    fun `the active server left out hands over to one that is read`() {
        val withServers = mine.copy(
            engines = listOf(EngineConfig.fromPreset(EnginePresets.GROQ), EngineConfig.fromPreset(EnginePresets.OPENAI)),
            activeEngineId = EnginePresets.GROQ.id,
        )
        val newer = SettingsCodec.encode(withServers)
            .replaceFirst("\"kind\": \"OPENAI_COMPATIBLE\"", "\"kind\": \"SOMETHING_NEWER\"")
        val read = SettingsCodec.read(newer, TEST_PHONE)
        assertIs<SettingsRead.Partial>(read)
        assertEquals(EnginePresets.OPENAI.id, read.settings.activeEngineId)
    }

    @Test
    fun `settings read in part go aside and are used meanwhile, and only a full read brings them back`() {
        val withServers = mine.copy(engines = listOf(EngineConfig.fromPreset(EnginePresets.GROQ)))
        val newer = SettingsCodec.encode(withServers)
            .replaceFirst("\"kind\": \"OPENAI_COMPATIBLE\"", "\"kind\": \"SOMETHING_NEWER\"")
        val outcome = SettingsRescue.of(Stored(newer, null), TEST_PHONE)
        assertTrue(outcome.settings.onboardingDone)
        assertTrue(outcome.settings.engines.isEmpty())
        assertEquals(Stored(newer, newer), outcome.store)
        assertIs<SettingsRescue.Event.KeptAside>(outcome.event)
        assertTrue("defaults" !in outcome.event!!.line, outcome.event!!.line)
        // Changes made with this build meanwhile stay: the copy reads only in part here.
        val meanwhile = SettingsCodec.encode(outcome.settings.copy(onboardingDone = false))
        val later = SettingsRescue.of(Stored(meanwhile, newer), TEST_PHONE)
        assertEquals(false, later.settings.onboardingDone)
        assertNull(later.store)
        assertNull(later.event)
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
