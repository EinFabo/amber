package app.amethyst.core

import app.amethyst.core.history.HistoryKeep
import app.amethyst.core.history.HistoryOffer
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.PrivacySettings
import app.amethyst.core.settings.SettingsCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryOfferTest {

    private val phone = listOf("de-DE")

    @Test
    fun `an install from before the history gets it off, and offered`() {
        val earlier = SettingsCodec.decode("""{"activeEngineId":"android-on-device","engines":[],"schemaVersion":14}""", phone)
        assertEquals(HistoryKeep.OFF, earlier.privacy.history)
        assertTrue(earlier.privacy.historyOffer)
        assertTrue(HistoryOffer.shows(earlier))
        assertEquals(AmethystSettings.SCHEMA_VERSION, earlier.schemaVersion)
    }

    @Test
    fun `also one that had it on without being asked`() {
        val week = """{"activeEngineId":"android-on-device","engines":[],"privacy":{"history":"WEEK"},"schemaVersion":14}"""
        assertEquals(HistoryKeep.OFF, SettingsCodec.decode(week, phone).privacy.history)
        // The security mode it had stays as it was.
        val secure = """{"activeEngineId":"android-on-device","engines":[],"privacy":{"securityMode":true},"schemaVersion":14}"""
        assertTrue(SettingsCodec.decode(secure, phone).privacy.securityMode)
    }

    @Test
    fun `a new install keeps 24 hours and is offered nothing`() {
        val fresh = SettingsCodec.decode("", phone)
        assertEquals(HistoryKeep.DAY, fresh.privacy.history)
        assertFalse(HistoryOffer.shows(fresh))
        // Saved and read again, it stays so.
        val again = SettingsCodec.decode(SettingsCodec.encode(fresh), phone)
        assertEquals(HistoryKeep.DAY, again.privacy.history)
        assertFalse(again.privacy.historyOffer)
    }

    @Test
    fun `an answer saved is kept - the migration runs once`() {
        val earlier = SettingsCodec.decode("""{"activeEngineId":"android-on-device","engines":[],"schemaVersion":14}""", phone)
        val accepted = SettingsCodec.decode(SettingsCodec.encode(HistoryOffer.accept(earlier)), phone)
        assertEquals(HistoryKeep.DAY, accepted.privacy.history)
        assertFalse(HistoryOffer.shows(accepted))
        val declined = SettingsCodec.decode(SettingsCodec.encode(HistoryOffer.decline(earlier)), phone)
        assertEquals(HistoryKeep.OFF, declined.privacy.history)
        assertFalse(declined.privacy.historyOffer)
        assertFalse(HistoryOffer.shows(declined))
    }

    @Test
    fun `nothing to offer while the history is on or the security mode keeps none`() {
        val offered = AmethystSettings.defaults(phone).copy(privacy = PrivacySettings(history = HistoryKeep.OFF, historyOffer = true))
        assertTrue(HistoryOffer.shows(offered))
        // Switched on from its page instead.
        assertFalse(HistoryOffer.shows(offered.copy(privacy = offered.privacy.copy(history = HistoryKeep.WEEK))))
        assertFalse(HistoryOffer.shows(offered.copy(privacy = offered.privacy.copy(securityMode = true))))
    }
}
