package app.amethyst.ui

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.amethyst.accessibility.AmethystAccessibilityService
import app.amethyst.capture.MicForegroundService
import app.amethyst.core.engine.PhoneFacts
import app.amethyst.core.onboarding.InstallSource
import app.amethyst.core.onboarding.PackageSource
import app.amethyst.core.onboarding.RestrictedSettings
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
    /** Android 13+ may lock the accessibility switch: Amber was not installed by a store. */
    val restrictedSettings: Boolean = false,
    /** Amber has sent the user to switch the service on, and it is still off. */
    val accessibilityTried: Boolean = false,
) {
    /** The two that are actually mandatory; the rest only improve reliability. */
    val ready: Boolean get() = microphoneGranted && accessibilityEnabled
}

object Setup {

    fun snapshot(context: Context, accessibilityTried: Boolean = false): SetupState {
        val accessibility = isAccessibilityEnabled(context)
        return SetupState(
            microphoneGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
            accessibilityEnabled = accessibility,
            notificationsGranted = areNotificationsGranted(context),
            micServiceRunning = MicForegroundService.isRunning,
            batteryUnrestricted = isBatteryUnrestricted(context),
            restrictedSettings = restrictedSettings(context),
            accessibilityTried = accessibilityTried && !accessibility,
        )
    }

    /** Read once: the install source does not change while the process lives. */
    @Volatile
    private var restricted: Boolean? = null

    private fun restrictedSettings(context: Context): Boolean =
        restricted ?: RestrictedSettings.mayBlock(Build.VERSION.SDK_INT, installSource(context)).also { restricted = it }

    /** Who installed Amber and how, from Android 13 where it matters; null if unreadable. */
    private fun installSource(context: Context): InstallSource? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val info = runCatching { context.packageManager.getInstallSourceInfo(context.packageName) }.getOrNull() ?: return null
        val kind = when (info.packageSource) {
            PackageInstaller.PACKAGE_SOURCE_OTHER -> PackageSource.OTHER
            PackageInstaller.PACKAGE_SOURCE_STORE -> PackageSource.STORE
            PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE -> PackageSource.LOCAL_FILE
            PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE -> PackageSource.DOWNLOADED_FILE
            else -> PackageSource.UNSPECIFIED
        }
        return InstallSource(info.installingPackageName, kind)
    }

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

    /**
     * Opens the settings as close to Amber's own switch as Android lets an app go; false if
     * nothing opened at all.
     *
     * 1. Amber's own page (ACCESSIBILITY_DETAILS_SETTINGS; AOSP's
     *    AccessibilityDetailsSettingsFragment reads EXTRA_COMPONENT_NAME as a flattened
     *    String). AOSP guards it with OPEN_ACCESSIBILITY_DETAILS_SETTINGS, which only installers
     *    hold, so on most phones this throws a SecurityException; some builds leave it open.
     * 2. The list, scrolled to Amber's row and flashing it: Settings highlights the preference
     *    whose key arrives as ":settings:fragment_args_key", and a service's key is its
     *    flattened component name.
     * 3. The plain list.
     */
    fun openAccessibilitySettings(context: Context): Boolean {
        val service = ComponentName(context, AmethystAccessibilityService::class.java).flattenToString()
        val ownPage = Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
            .putExtra(Intent.EXTRA_COMPONENT_NAME, service)
        val highlighted = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(EXTRA_FRAGMENT_ARG_KEY, service)
            .putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, service) })
        val list = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        return listOf(ownPage, highlighted, list).any { tryStart(context, it) }
    }

    fun openAppSettings(context: Context): Boolean = tryStart(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null)),
    )

    /**
     * Opens the generic battery-optimisation list instead of requesting the exemption
     * directly: REQUEST_IGNORE_BATTERY_OPTIMIZATIONS prompts are a Play policy violation for
     * most app categories, and the list works everywhere. False if nothing opened.
     */
    fun openBatterySettings(context: Context): Boolean =
        tryStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) || openAppSettings(context)

    /** Starts [intent] in a task of its own; a missing or guarded screen is a false, not a crash. */
    private fun tryStart(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    /** Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS, which the SDK names only from API 31. */
    private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    /** Settings' own extras for the row to highlight (SettingsActivity.EXTRA_FRAGMENT_ARG_KEY and friends). */
    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"
}
