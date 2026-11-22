package app.amethyst.apps

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import app.amethyst.AmethystApp

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
     * shade's reply field is a fair wish. So does an app in another profile (a work profile,
     * the private space): the package manager does not know it, but the accessibility service
     * saw it in front ([app.amethyst.core.apps.AppsSeenInFront]), or another profile has it.
     */
    fun installed(context: Context, packageName: String?): String? {
        if (packageName.isNullOrBlank() || packageName == context.packageName || packageName.length > MAX_PACKAGE_CHARS) return null
        // The home screen is seen in front between apps, but nothing is dictated there.
        if (isHome(context, packageName)) return null
        val found = inOwnProfile(context, packageName) ||
            packageName in AmethystApp.graph(context).appsSeenInFront ||
            otherProfiles(context).any { user -> inProfile(context, packageName, user) }
        return packageName.takeIf { found }
    }

    /**
     * Whether [packageName] is in Amber's own profile - false for an app the user runs in a
     * work profile or the private space, for the diagnostics.
     */
    fun inOwnProfile(context: Context, packageName: String): Boolean =
        runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.isSuccess

    /**
     * [packageName]'s name as the launcher shows it - from Amber's own profile, else from
     * another one Android lets Amber see. Null when neither has it: an app in a profile hidden
     * from Amber (the private space, on most phones).
     */
    fun label(context: Context, packageName: String): String? {
        val packages = context.packageManager
        runCatching { return packages.getApplicationInfo(packageName, 0).loadLabel(packages).toString() }
        return otherProfiles(context).firstNotNullOfOrNull { user ->
            runCatching { launcherApps(context)?.getActivityList(packageName, user)?.firstOrNull()?.label?.toString() }.getOrNull()
                ?: runCatching { launcherApps(context)?.getApplicationInfo(packageName, 0, user)?.loadLabel(packages)?.toString() }.getOrNull()
        }?.takeIf { it.isNotBlank() }
    }

    /** [packageName]'s icon, from Amber's profile or another one - there with the profile's badge. */
    fun icon(context: Context, packageName: String): Drawable? {
        runCatching { return context.packageManager.getApplicationIcon(packageName) }
        return otherProfiles(context).firstNotNullOfOrNull { user ->
            runCatching { launcherApps(context)?.getActivityList(packageName, user)?.firstOrNull()?.getBadgedIcon(0) }.getOrNull()
        }
    }

    /**
     * Whether [packageName] is a home screen (a launcher in the HOME sense): seen in front
     * between apps, never one to give a style or a quick menu action to.
     */
    fun isHome(context: Context, packageName: String): Boolean {
        val home = homes ?: runCatching {
            context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .mapTo(HashSet()) { it.activityInfo.packageName }
        }.getOrDefault(emptySet()).also { homes = it }
        return packageName in home
    }

    /** The home screens, read once per process: they rarely change. */
    @Volatile
    private var homes: Set<String>? = null

    /** The profiles besides Amber's own that Android lets Amber look into; none where it lets it see none. */
    private fun otherProfiles(context: Context): List<UserHandle> {
        val own = Process.myUserHandle()
        return runCatching { launcherApps(context)?.profiles.orEmpty().filter { it != own } }.getOrDefault(emptyList())
    }

    private fun inProfile(context: Context, packageName: String, user: UserHandle): Boolean =
        runCatching { launcherApps(context)?.getApplicationInfo(packageName, 0, user) != null }.getOrDefault(false)

    private fun launcherApps(context: Context): LauncherApps? = context.getSystemService(LauncherApps::class.java)

    /** Android allows no longer package name. */
    private const val MAX_PACKAGE_CHARS = 255
}
