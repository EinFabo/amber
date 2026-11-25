package app.amethyst.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.os.SystemClock
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
import app.amethyst.core.engine.PhoneFacts
import app.amethyst.core.onboarding.AmberVariant
import app.amethyst.core.onboarding.BatteryPage
import app.amethyst.core.onboarding.InstallSource
import app.amethyst.core.onboarding.OtherAmbers
import app.amethyst.core.onboarding.PackageSource
import app.amethyst.core.onboarding.RestrictedSettings
import app.amethyst.core.onboarding.ServiceHealth
import app.amethyst.core.onboarding.SwitchOnPlan
import app.amethyst.core.onboarding.SwitchOnStep
import app.amethyst.engine.FirstRunSetup

/** Everything that has to be true before a tap on the bubble can produce text. */
data class SetupState(
    val microphoneGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    /** Whether the switch is backed by a live connection; only [ServiceHealth.NOT_RUNNING] and [ServiceHealth.STARTING] differ from a plain "on". */
    val serviceHealth: ServiceHealth = ServiceHealth.RUNNING,
    val notificationsGranted: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    /** Android 13+ may lock the accessibility switch: Amber was not installed by a store. */
    val restrictedSettings: Boolean = false,
    /** Amber has sent the user to switch the service on, and it is still off. */
    val accessibilityTried: Boolean = false,
    /** Other builds (the debug one, the old Amber) whose service is on beside this one's; empty while this one is off. */
    val otherAmbers: List<AmberVariant> = emptyList(),
    /** The old Amber's builds still installed ([OtherAmbers.OLD_PACKAGES]), whether their service is on or not. */
    val oldAmbersInstalled: List<String> = emptyList(),
) {
    /** The two that are actually mandatory; the rest only improve reliability. */
    val ready: Boolean get() = microphoneGranted && accessibilityEnabled

    /** Ready and the service is really connected - not just switched on, not still starting. */
    val working: Boolean get() = ready && serviceHealth == ServiceHealth.RUNNING

    /** The switch is on, yet the service never connected: "ready" would be a lie. */
    val serviceNotRunning: Boolean get() = serviceHealth == ServiceHealth.NOT_RUNNING
}

object Setup {

    fun snapshot(context: Context, accessibilityTried: Boolean = false, serviceConnected: Boolean = true): SetupState {
        val accessibility = isAccessibilityEnabled(context)
        // Only a conflict while this one is on too: with it off, the other is simply the Amber in use.
        val others = if (accessibility) OtherAmbers.find(context.packageName, enabledServices(context)) else emptyList()
        return SetupState(
            microphoneGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
            accessibilityEnabled = accessibility,
            serviceHealth = ServiceHealth.of(
                switchOn = accessibility,
                connected = serviceConnected,
                msSinceAppStart = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime(),
            ),
            notificationsGranted = areNotificationsGranted(context),
            batteryUnrestricted = isBatteryUnrestricted(context),
            restrictedSettings = restrictedSettings(context),
            accessibilityTried = accessibilityTried && !accessibility,
            otherAmbers = others,
            oldAmbersInstalled = OtherAmbers.OLD_PACKAGES.filter { isInstalled(context, it) },
        )
    }

    /** Whether [pkg] is installed - visible to this app only as far as the manifest's <queries> names it. */
    private fun isInstalled(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** Android's app info for [pkg], where it can be uninstalled; false if nothing opened. */
    fun openAppInfo(context: Context, pkg: String): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess

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
    fun phoneFacts(context: Context): PhoneFacts = FirstRunSetup.phoneFacts(context)

    /**
     * Read from Settings.Secure rather than from AccessibilityManager: the latter only lists
     * services the system considers active, which lags behind the user toggling ours off.
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, AmethystAccessibilityService::class.java)
        return enabledServices(context).orEmpty().split(':').any { entry ->
            ComponentName.unflattenFromString(entry) == expected
        }
    }

    /** Every switched-on accessibility service, flattened and joined by ':'; null if none ever was. */
    private fun enabledServices(context: Context): String? =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)

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
     * Opens where the battery steps start ([SwitchOnPlan.batteryPage]): from Android 12
     * sayEmber's own app info, where "Battery" holds "Unrestricted"; before it, where the app
     * info has no such choice, ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS - its list starts
     * filtered to the apps that are not optimized, so the steps say to switch it to all apps -
     * and the app info if that list is missing. Never REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
     * whose prompt is a Play policy violation for most app categories. The steps are on screen
     * ([SwitchOnPlan.battery]). False if nothing opened.
     */
    fun openBatterySettings(context: Context): Boolean = when (SwitchOnPlan.batteryPage(Build.VERSION.SDK_INT)) {
        BatteryPage.APP_INFO -> openAppSettings(context)
        BatteryPage.OPTIMIZATION_LIST ->
            tryStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) || openAppSettings(context)
    }

    /**
     * Opens the maker's extra battery step ([SwitchOnPlan.batteryExtra]) as close as the phone
     * allows: One UI's battery page (where "Background usage limits" leads to "Never sleeping
     * apps"), MIUI's autostart list; both are the maker's own screens, not Android's, and may be
     * missing or guarded - then sayEmber's app info, where MIUI has "Autostart" too. No return
     * to sayEmber is watched: nothing Android reports tells that it is done.
     */
    fun openBatteryExtra(context: Context, step: SwitchOnStep): Boolean {
        val own = when (step) {
            SwitchOnStep.NEVER_SLEEPING -> listOf(
                ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            )
            SwitchOnStep.AUTOSTART -> listOf(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            )
            else -> emptyList()
        }
        return own.any { tryStart(context, Intent().setComponent(it)) } || openAppSettings(context)
    }

    /** Starts [intent] in a task of its own; a missing or guarded screen is a false, not a crash. */
    private fun tryStart(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    /** Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS, which the SDK names only from API 31. */
    private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    /** Settings' own extras for the row to highlight (SettingsActivity.EXTRA_FRAGMENT_ARG_KEY and friends). */
    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"
}
