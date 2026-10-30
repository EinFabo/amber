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
}
