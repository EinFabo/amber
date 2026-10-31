package app.amethyst.accessibility

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import app.amethyst.core.onboarding.SettingsReturn
import app.amethyst.core.onboarding.SettingsTrip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Brings Amber back to the front once the user has switched on what Amber sent them into
 * Android's settings for, so they do not have to find the way back with Back. Whether that is
 * due is [SettingsReturn]'s decision; this feeds it the clock, watches the battery exemption
 * (Android tells nobody when it changes) and leaves the bringing back to the accessibility
 * service - the one part of Amber Android lets start an activity from the background.
 */
class ReturnToAmber(
    private val context: Context,
    private val scope: CoroutineScope,
    private val service: () -> AmethystAccessibilityService?,
) {

    private val requests = SettingsReturn()
    private var batteryWatch: Job? = null

    /** The user is being sent to switch [trip] on. */
    fun sending(trip: SettingsTrip) {
        requests.sent(trip, now())
        batteryWatch?.cancel()
        // Without the service nothing could bring Amber back, so there is nothing to watch for.
        if (trip == SettingsTrip.BATTERY && service() != null) batteryWatch = scope.launch { watchBattery() }
    }

    /** Amber is in front again, however it got there: nothing is due any more. */
    fun cancel() {
        requests.cancel()
        batteryWatch?.cancel()
    }

    /** The accessibility service connected: back to Amber if the user was just sent to switch it on. */
    fun serviceConnected(connected: AmethystAccessibilityService) {
        if (requests.take(SettingsTrip.ACCESSIBILITY, now())) connected.bringAmberBack()
    }

    private suspend fun watchBattery() {
        val power = context.getSystemService(PowerManager::class.java) ?: return
        while (requests.pending(SettingsTrip.BATTERY, now())) {
            delay(BATTERY_CHECK_MS)
            if (power.isIgnoringBatteryOptimizations(context.packageName)) {
                if (requests.take(SettingsTrip.BATTERY, now())) {
                    withContext(Dispatchers.Main) { service()?.bringAmberBack() }
                }
                return
            }
        }
    }

    private fun now() = SystemClock.elapsedRealtime()

    private companion object {
        const val BATTERY_CHECK_MS = 500L
    }
}
