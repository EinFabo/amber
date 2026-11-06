package app.amethyst.core

import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.CaptureSettings
import app.amethyst.core.settings.SettingsCodec
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Auto-pause is off for new installs (roadmap P3); an upgrade keeps its own. */
class AutoPauseTest {

    @Test
    fun `a new install has auto-pause off`() {
        assertFalse(CaptureSettings().autoStopOnSilence)
        assertFalse(AmethystSettings.defaults(TEST_PHONE).capture.autoStopOnSilence)
    }

    @Test
    fun `an upgrade keeps the auto-pause it had`() {
        val old = """{"activeEngineId":"android-on-device","engines":[],"capture":{"autoStopOnSilence":true},"schemaVersion":10}"""
        assertTrue(SettingsCodec.decode(old, TEST_PHONE).capture.autoStopOnSilence)
    }
}
