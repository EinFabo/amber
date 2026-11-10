package app.amethyst.apps

import android.content.Context
import android.content.Intent

/** An app the user can pick or name: what the launcher shows. */
data class InstalledApp(val packageName: String, val label: String)

/**
 * The apps with a launcher icon, Amber itself left out - for the app pickers and for "open
 * WhatsApp". Visible through the manifest's MAIN/LAUNCHER query, without QUERY_ALL_PACKAGES.
 * Reads the package manager: call it off the main thread.
 */
object Launcher {

    fun apps(context: Context): List<InstalledApp> {
        val packages = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packages.queryIntentActivities(launcher, 0)
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(packages).toString()) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
    }

    /**
     * [packageName] when it is an installed app with a launcher icon other than Amber, else
     * null: what a screen opened for one app (from the quick menu) accepts in its intent.
     */
    fun launchable(context: Context, packageName: String?): String? {
        if (packageName.isNullOrBlank() || packageName == context.packageName || packageName.length > MAX_PACKAGE_CHARS) return null
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
        val found = runCatching { context.packageManager.queryIntentActivities(launcher, 0).isNotEmpty() }.getOrDefault(false)
        return packageName.takeIf { found }
    }

    /** Android allows no longer package name. */
    private const val MAX_PACKAGE_CHARS = 255
}
