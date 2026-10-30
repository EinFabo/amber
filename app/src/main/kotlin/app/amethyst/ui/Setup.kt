package app.amethyst.ui

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.amethyst.accessibility.AmethystAccessibilityService
import app.amethyst.capture.MicForegroundService
import app.amethyst.core.engine.PhoneFacts
import app.amethyst.engine.AndroidOnDeviceEngine
import app.amethyst.engine.WhisperNative
import java.io.File

/** Everything that has to be true before a tap on the bubble can produce text. */
data class SetupState(
    val microphoneGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val notificationsGranted: Boolean = false,
    val micServiceRunning: Boolean = false,
    val batteryUnrestricted: Boolean = false,
) {
    /** The two that are actually mandatory; the rest only improve reliability. */
    val ready: Boolean get() = microphoneGranted && accessibilityEnabled
}

object Setup {

    fun snapshot(context: Context) = SetupState(
        microphoneGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED,
        accessibilityEnabled = isAccessibilityEnabled(context),
        notificationsGranted = areNotificationsGranted(context),
        micServiceRunning = MicForegroundService.isRunning,
        batteryUnrestricted = isBatteryUnrestricted(context),
    )

    /** What the introduction weighs to suggest an engine: memory, room for models, the CPU. */
    fun phoneFacts(context: Context): PhoneFacts {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
        // Where the stores put models (noBackupFilesDir/models), so the free space is theirs.
        val models = File(context.noBackupFilesDir, "models").apply { mkdirs() }
        return PhoneFacts(
            totalRamMb = memory.totalMem / 1_000_000,
            freeStorageMb = models.usableSpace / 1_000_000,
            whisperCpu = WhisperNative.cpuSupported(),
            androidOnDevice = AndroidOnDeviceEngine.onDeviceSpeech,
        )
    }

    /**
     * Read from Settings.Secure rather than from AccessibilityManager: the latter only lists
     * services the system considers active, which lags behind the user toggling ours off.
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, AmethystAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { entry ->
            ComponentName.unflattenFromString(entry) == expected
        }
    }

    private fun areNotificationsGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    private fun isBatteryUnrestricted(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openAppSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Opens the generic battery-optimisation list instead of requesting the exemption
     * directly: REQUEST_IGNORE_BATTERY_OPTIMIZATIONS prompts are a Play policy violation for
     * most app categories, and the list works everywhere.
     */
    fun openBatterySettings(context: Context) {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { openAppSettings(context) }
    }
}
