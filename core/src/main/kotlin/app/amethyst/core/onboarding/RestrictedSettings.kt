package app.amethyst.core.onboarding

/** How an app reached the phone, as Android's PackageInstaller.PACKAGE_SOURCE_* tells it. */
enum class PackageSource { UNSPECIFIED, OTHER, STORE, LOCAL_FILE, DOWNLOADED_FILE }

/** Where Android says Amber was installed from (PackageManager.getInstallSourceInfo). */
data class InstallSource(
    /** The installer of record; null for adb. */
    val installer: String?,
    val kind: PackageSource,
)

/**
 * From Android 13, an app installed from a file finds its accessibility switch locked
 * ("Restricted setting") until the user allows it in the app info. Android does not tell an
 * app whether the lock applies to it, so this guesses from the install source the way Android
 * decides: a file install is locked, a store install is not. Anything else may be - the
 * introduction then says so before it opens the settings, and shows the way past the lock if
 * the user comes back without the service on.
 */
object RestrictedSettings {

    /** Android 13, where the lock came in. */
    const val FIRST_SDK = 33

    /** Stores whose installs Android trusts, also when they do not say they are a store. */
    val STORES = setOf(
        "com.android.vending",
        "com.sec.android.app.samsungapps",
        "com.huawei.appmarket",
        "com.amazon.venezia",
    )

    /** Whether Android may lock the accessibility switch for an install from [source]; null when it could not be read. */
    fun mayBlock(sdk: Int, source: InstallSource?): Boolean = when {
        sdk < FIRST_SDK -> false
        source == null -> true
        source.kind == PackageSource.LOCAL_FILE || source.kind == PackageSource.DOWNLOADED_FILE -> true
        source.kind == PackageSource.STORE -> false
        else -> source.installer !in STORES
    }
}
