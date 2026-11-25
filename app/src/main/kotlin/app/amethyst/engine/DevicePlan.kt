package app.amethyst.engine

import android.content.Context
import android.os.Build
import android.os.PowerManager
import app.amethyst.core.engine.InferenceThreads

/**
 * Reads the phone's heat and battery saver once, as a recognition starts, and turns them into
 * the thread count and hint choice of [InferenceThreads]. Before Android 10 there is no thermal
 * status: it counts as normal.
 */
internal object DevicePlan {

    fun now(context: Context): InferenceThreads.Plan {
        val power = runCatching { context.getSystemService(PowerManager::class.java) }.getOrNull()
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { power?.currentThermalStatus }.getOrNull()?.let(::thermalOf) ?: InferenceThreads.Thermal.NORMAL
        } else {
            InferenceThreads.Thermal.NORMAL
        }
        val saver = runCatching { power?.isPowerSaveMode == true }.getOrDefault(false)
        return InferenceThreads.choose(Runtime.getRuntime().availableProcessors(), thermal, saver)
    }

    private fun thermalOf(status: Int): InferenceThreads.Thermal = when {
        status <= PowerManager.THERMAL_STATUS_NONE -> InferenceThreads.Thermal.NORMAL
        status == PowerManager.THERMAL_STATUS_LIGHT -> InferenceThreads.Thermal.LIGHT
        status == PowerManager.THERMAL_STATUS_MODERATE -> InferenceThreads.Thermal.MODERATE
        status == PowerManager.THERMAL_STATUS_SEVERE -> InferenceThreads.Thermal.SEVERE
        else -> InferenceThreads.Thermal.CRITICAL
    }
}
