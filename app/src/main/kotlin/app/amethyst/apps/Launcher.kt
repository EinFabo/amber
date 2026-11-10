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
     * [packageName] when it is an installed app other than Amber, else null - the one rule for
     * "another app is in front" in the quick menu and for what a screen opened for one app
     * accepts. Any installed app counts, a launcher icon or not: a style for the notification
     * shade's reply field is a fair wish. An app Android keeps out of Amber's sight counts as
     * not installed.
     */
    fun installed(context: Context, packageName: String?): String? {
        if (packageName.isNullOrBlank() || packageName == context.packageName || packageName.length > MAX_PACKAGE_CHARS) return null
        val found = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.isSuccess
        return packageName.takeIf { found }
    }

    /** Android allows no longer package name. */
    private const val MAX_PACKAGE_CHARS = 255
}
