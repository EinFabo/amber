package app.amethyst.core.settings

import app.amethyst.core.text.CleanupTrouble
import app.amethyst.core.update.ReleaseChannel
import app.amethyst.core.update.UpdatePolicy

/** The things the Reliability page lists, top to bottom: each keeps the microphone available in other apps. */
enum class ReliabilityItem { MIC_SERVICE, NOTIFICATIONS, BATTERY }

/** What the row on top of the Settings page points at; see [SettingsAttention.of]. */
sealed interface Attention {

    /** [open] on the Reliability page is not done yet - never empty. */
    data class Reliability(val open: List<ReliabilityItem>) : Attention

    /** Amber [version] is out and this build can get it under Updates. */
    data class Update(val version: String) : Attention

    /** The cleanup did not run and will not until the user puts [trouble] right ([CleanupTrouble]). */
    data class Cleanup(val trouble: CleanupTrouble.Open) : Attention
}

/**
 * Whether something in the settings needs a look. The Settings page says so in one row on top:
 * the Reliability row sits at the bottom of the page, out of sight on a phone. Nothing open: no
 * row.
 */
object SettingsAttention {

    /** What the Reliability page shows as not done, in its order. */
    fun reliabilityOpen(micServiceRunning: Boolean, notificationsGranted: Boolean, batteryUnrestricted: Boolean): List<ReliabilityItem> =
        listOfNotNull(
            ReliabilityItem.MIC_SERVICE.takeUnless { micServiceRunning },
            ReliabilityItem.NOTIFICATIONS.takeUnless { notificationsGranted },
            ReliabilityItem.BATTERY.takeUnless { batteryUnrestricted },
        )

    /**
     * The one thing to point at, or null. Reliability comes first: it decides whether a
     * dictation works, and the release is still there afterwards. Then a cleanup that does not
     * run ([cleanup], until one succeeds again): the text goes in as heard meanwhile. A release counts as Home's
     * card does ([UpdatePolicy.forHome]): not on a build that gets its updates elsewhere, not
     * while the check is off, not a version the user dismissed. [channel] is null until known.
     * [reliabilityRow]: the Reliability row says what is open itself, in sight, so the row on
     * top goes to what comes next.
     */
    fun of(
        reliabilityOpen: List<ReliabilityItem>,
        channel: ReleaseChannel?,
        updates: UpdateSettings,
        installed: String,
        cleanup: CleanupTrouble.Open? = null,
        reliabilityRow: Boolean = false,
    ): Attention? = when {
        reliabilityOpen.isNotEmpty() && !reliabilityRow -> Attention.Reliability(reliabilityOpen)
        cleanup != null -> Attention.Cleanup(cleanup)
        else -> channel?.let { UpdatePolicy.forHome(it, updates, installed) }?.let { Attention.Update(it.version) }
    }
}
