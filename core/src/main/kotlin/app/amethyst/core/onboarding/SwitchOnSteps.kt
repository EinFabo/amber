package app.amethyst.core.onboarding

/**
 * Whose settings app the phone has. Android's Settings differs by maker: One UI hides
 * downloaded accessibility services behind "Installed apps", MIUI and HyperOS behind
 * "Downloaded apps", AOSP and Pixel list them directly.
 */
enum class SettingsMaker {
    AOSP,
    SAMSUNG,
    XIAOMI,
    ;

    companion object {
        /** The maker for Build.MANUFACTURER, in any case; any maker not named here is AOSP. */
        fun of(manufacturer: String): SettingsMaker = when (manufacturer.trim().lowercase()) {
            "samsung" -> SAMSUNG
            "xiaomi", "redmi", "poco" -> XIAOMI
            else -> AOSP
        }
    }
}

/** One thing to tap or switch in Android's settings; Texts words each, in the phone's language. */
enum class SwitchOnStep {
    /** Samsung: the accessibility list's "Installed apps" row, where downloaded services sit. */
    INSTALLED_APPS,

    /** Xiaomi: the same row, called "Downloaded apps". */
    DOWNLOADED_APPS,

    /** The row of sayEmber's service. */
    TAP_SERVICE,

    /** The service's own switch. */
    TURN_ON,

    /** Android's question under it. */
    ALLOW,

    /** sayEmber's app info, which the app opens itself. */
    APP_INFO,

    /** The app info's "Battery" row. */
    TAP_BATTERY,

    /** The battery page's "Unrestricted" choice (Android 12 and later). */
    UNRESTRICTED,

    /** Before Android 12: Android's "Battery optimization" list, which the app opens itself. */
    BATTERY_OPTIMIZATION,

    /** Its filter at the top, from the apps that are not optimized to "All apps". */
    ALL_APPS,

    /** sayEmber in that list, then "Don't optimize". */
    DONT_OPTIMIZE,

    /** Before Android 12 on Samsung: One UI's "Optimize battery usage" list, which the app opens itself. */
    OPTIMIZE_BATTERY_USAGE,

    /** Its filter at the top, set to "All". */
    ALL_SAMSUNG,

    /** The switch beside sayEmber, turned off. */
    SWITCH_OFF_OPTIMIZING,

    /** Samsung's list of apps that are never put to sleep, beside the battery choice ([SwitchOnPlan.batteryExtra]). */
    NEVER_SLEEPING,

    /** Xiaomi's autostart switch, which keeps a background app from being killed at once ([SwitchOnPlan.batteryExtra]). */
    AUTOSTART,
}

/** Where the battery row's button goes ([SwitchOnPlan.batteryPage]). */
enum class BatteryPage {
    /** sayEmber's own app info, whose "Battery" page has "Unrestricted". */
    APP_INFO,

    /** Android's battery optimization list, with sayEmber under "All apps". */
    OPTIMIZATION_LIST,
}

/** The steps to the accessibility switch and to unrestricted battery use, per [SettingsMaker]. */
object SwitchOnPlan {

    /** The way to sayEmber's accessibility switch from the settings app's list. */
    fun accessibility(maker: SettingsMaker): List<SwitchOnStep> {
        val first = when (maker) {
            SettingsMaker.AOSP -> emptyList()
            SettingsMaker.SAMSUNG -> listOf(SwitchOnStep.INSTALLED_APPS)
            SettingsMaker.XIAOMI -> listOf(SwitchOnStep.DOWNLOADED_APPS)
        }
        return first + listOf(SwitchOnStep.TAP_SERVICE, SwitchOnStep.TURN_ON, SwitchOnStep.ALLOW)
    }

    /** The first Android whose app info has a battery page with "Unrestricted" (12). */
    const val UNRESTRICTED_FROM_SDK = 31

    /**
     * The way to unrestricted battery use on Android [sdk], numbered under the battery row:
     * from Android 12 in sayEmber's app info (Battery, Unrestricted); before it, where the app
     * info has no such choice, in the battery optimization list, as the [maker] words it. The
     * maker's own extra step is not among them: the return to sayEmber after the last one ends
     * the list, so it has a row of its own ([batteryExtra]).
     */
    fun battery(maker: SettingsMaker, sdk: Int): List<SwitchOnStep> = when {
        sdk >= UNRESTRICTED_FROM_SDK -> listOf(SwitchOnStep.APP_INFO, SwitchOnStep.TAP_BATTERY, SwitchOnStep.UNRESTRICTED)
        maker == SettingsMaker.SAMSUNG -> listOf(SwitchOnStep.OPTIMIZE_BATTERY_USAGE, SwitchOnStep.ALL_SAMSUNG, SwitchOnStep.SWITCH_OFF_OPTIMIZING)
        else -> listOf(SwitchOnStep.BATTERY_OPTIMIZATION, SwitchOnStep.ALL_APPS, SwitchOnStep.DONT_OPTIMIZE)
    }

    /** The page the battery row's button opens on Android [sdk], where [battery]'s steps start. */
    fun batteryPage(sdk: Int): BatteryPage =
        if (sdk >= UNRESTRICTED_FROM_SDK) BatteryPage.APP_INFO else BatteryPage.OPTIMIZATION_LIST

    /**
     * What the [maker] adds to unrestricted battery use, so the bubble is not put to sleep or
     * killed after all: Samsung's "Never sleeping apps", Xiaomi's "Autostart". Null elsewhere.
     */
    fun batteryExtra(maker: SettingsMaker): SwitchOnStep? = when (maker) {
        SettingsMaker.AOSP -> null
        SettingsMaker.SAMSUNG -> SwitchOnStep.NEVER_SLEEPING
        SettingsMaker.XIAOMI -> SwitchOnStep.AUTOSTART
    }

    /**
     * The [maker]'s extra step as a row of its own: once the battery is unrestricted - the
     * numbered steps come first, and Android shows no sign of the extra one - until the user
     * marked it [done], which is for good.
     */
    fun batteryExtraShown(maker: SettingsMaker, batteryUnrestricted: Boolean, done: Boolean): SwitchOnStep? =
        batteryExtra(maker)?.takeIf { batteryUnrestricted && !done }
}
