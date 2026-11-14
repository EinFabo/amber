package app.amethyst.core

import app.amethyst.core.settings.Attention
import app.amethyst.core.settings.ReliabilityItem
import app.amethyst.core.settings.SettingsAttention
import app.amethyst.core.settings.UpdateSettings
import app.amethyst.core.update.ReleaseChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsAttentionTest {

    private val allSet = emptyList<ReliabilityItem>()
    private val found = UpdateSettings(latestVersion = "0.33.0")

    @Test
    fun `reliability lists what is not done, in the page's order`() {
        assertEquals(allSet, SettingsAttention.reliabilityOpen(micServiceRunning = true, notificationsGranted = true, batteryUnrestricted = true))
        assertEquals(
            listOf(ReliabilityItem.BATTERY),
            SettingsAttention.reliabilityOpen(micServiceRunning = true, notificationsGranted = true, batteryUnrestricted = false),
        )
        assertEquals(
            listOf(ReliabilityItem.MIC_SERVICE, ReliabilityItem.NOTIFICATIONS, ReliabilityItem.BATTERY),
            SettingsAttention.reliabilityOpen(micServiceRunning = false, notificationsGranted = false, batteryUnrestricted = false),
        )
    }

    @Test
    fun `nothing open is no row`() {
        assertNull(SettingsAttention.of(allSet, ReleaseChannel.GITHUB, UpdateSettings(), "0.32.0"))
        assertNull(SettingsAttention.of(allSet, ReleaseChannel.GITHUB, UpdateSettings(latestVersion = "0.32.0"), "0.32.0"))
        assertNull(SettingsAttention.of(allSet, ReleaseChannel.OTHER, UpdateSettings(), "0.32.0"))
        // The build is not known yet.
        assertNull(SettingsAttention.of(allSet, channel = null, found, "0.32.0"))
    }

    @Test
    fun `an open reliability thing is said, whatever the build`() {
        val open = listOf(ReliabilityItem.NOTIFICATIONS, ReliabilityItem.BATTERY)
        for (channel in listOf(ReleaseChannel.GITHUB, ReleaseChannel.OTHER, null)) {
            assertEquals(Attention.Reliability(open), SettingsAttention.of(open, channel, UpdateSettings(), "0.32.0"))
        }
    }

    @Test
    fun `a release that is out is said`() {
        assertEquals(Attention.Update("0.33.0"), SettingsAttention.of(allSet, ReleaseChannel.GITHUB, found, "0.32.0"))
    }

    @Test
    fun `reliability comes before a release`() {
        val open = listOf(ReliabilityItem.BATTERY)
        assertEquals(Attention.Reliability(open), SettingsAttention.of(open, ReleaseChannel.GITHUB, found, "0.32.0"))
    }

    @Test
    fun `a release is not said where Home would not say it either`() {
        // A build that gets its updates from where it was installed.
        assertNull(SettingsAttention.of(allSet, ReleaseChannel.OTHER, found, "0.32.0"))
        // The check switched off.
        assertNull(SettingsAttention.of(allSet, ReleaseChannel.GITHUB, found.copy(enabled = false), "0.32.0"))
        // The version dismissed on Home.
        assertNull(SettingsAttention.of(allSet, ReleaseChannel.GITHUB, found.copy(dismissedVersion = "0.33.0"), "0.32.0"))
        // Already installed, or newer.
        assertNull(SettingsAttention.of(allSet, ReleaseChannel.GITHUB, found, "0.33.0"))
        assertNull(SettingsAttention.of(allSet, ReleaseChannel.GITHUB, found, "0.34.0-debug"))
    }
}
