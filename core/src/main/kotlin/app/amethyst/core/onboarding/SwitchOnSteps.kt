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

    /** The row of Embersay's service. */
    TAP_SERVICE,

    /** The service's own switch. */
    TURN_ON,

    /** Android's question under it. */
    ALLOW,
}

/** The steps to the accessibility switch, per [SettingsMaker]. */
object SwitchOnPlan {

    /** The way to Embersay's accessibility switch from the settings app's list. */
    fun accessibility(maker: SettingsMaker): List<SwitchOnStep> {
        val first = when (maker) {
            SettingsMaker.AOSP -> emptyList()
            SettingsMaker.SAMSUNG -> listOf(SwitchOnStep.INSTALLED_APPS)
            SettingsMaker.XIAOMI -> listOf(SwitchOnStep.DOWNLOADED_APPS)
        }
        return first + listOf(SwitchOnStep.TAP_SERVICE, SwitchOnStep.TURN_ON, SwitchOnStep.ALLOW)
    }
}
