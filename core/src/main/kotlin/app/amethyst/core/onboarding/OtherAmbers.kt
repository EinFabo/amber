package app.amethyst.core.onboarding

/**
 * Another Embersay on the same phone - the debug build beside the regular one, say, or the old
 * Amber (app.amber) from before the rename - whose accessibility service is switched on too.
 * Two of them each show a bubble and each get the keyboard and field signals, a likely cause of
 * rare, random misbehaviour, so the app says so.
 */
data class AmberVariant(val packageName: String) {
    /** The old Amber, from before the app was renamed to Embersay (2026-11-17). */
    val old: Boolean get() = OtherAmbers.isOld(packageName)

    /** The build type's suffix ("debug" for com.embersay.debug), null for a regular build. */
    val buildType: String?
        get() = packageName.removePrefix(if (old) OtherAmbers.OLD_PACKAGE else OtherAmbers.PACKAGE)
            .removePrefix(".").ifEmpty { null }
}

/**
 * What Home says about the other apps: [uninstall], the old Amber's builds still installed -
 * removing them is the whole fix; [alsoOn], the rest whose service is on beside this one.
 */
data class OtherAmbersNotice(val uninstall: List<AmberVariant>, val alsoOn: List<AmberVariant>)

object OtherAmbers {

    /** Every Embersay's application id: com.embersay, plus a build type's suffix (com.embersay.debug). */
    const val PACKAGE = "com.embersay"

    /** The id until 2026-11-17, plus a build type's suffix (app.amber.debug). Same code, same service class. */
    const val OLD_PACKAGE = "app.amber"

    /** The old app's builds that may be on a phone; the manifest's <queries> names the same, so Android 11+ shows them. */
    val OLD_PACKAGES = listOf(OLD_PACKAGE, "$OLD_PACKAGE.debug")

    /** The service's class, the same in every variant: the code keeps its namespace. */
    const val SERVICE_CLASS = "app.amethyst.accessibility.AmethystAccessibilityService"

    /**
     * The other Embersays and old Ambers among [enabledServices]
     * (Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES: flattened component names,
     * "package/class", joined by ':'), without [ownPackage]. Matched exactly, as Android does:
     * package names and classes are case-sensitive. A class may come short (".accessibility.X",
     * relative to the package), as ComponentName allows. Entries that are not "package/class"
     * are skipped.
     */
    fun find(ownPackage: String, enabledServices: String?): List<AmberVariant> =
        enabledServices.orEmpty()
            .split(':')
            .mapNotNull { parse(it.trim()) }
            .filter { (pkg, cls) -> isAmber(pkg) && pkg != ownPackage && cls == SERVICE_CLASS }
            .map { (pkg, _) -> AmberVariant(pkg) }
            .distinct()

    /**
     * What Home shows. The old Amber's builds in [installed] (package names Android lists, the
     * own one left out) get the card that asks to uninstall them, until it is [dismissed] - but
     * never while one of them has its service on ([servicesOn], from [find]): then there are two
     * bubbles, and the card stays. The other apps whose service is on stay in [OtherAmbersNotice.alsoOn],
     * without those the uninstall card already names.
     */
    fun notice(
        ownPackage: String,
        installed: Collection<String>,
        servicesOn: List<AmberVariant>,
        dismissed: Boolean,
    ): OtherAmbersNotice {
        val oldInstalled = installed.filter { isOld(it) && it != ownPackage }.distinct().map(::AmberVariant)
        val oldOn = servicesOn.filter { it.old }
        val uninstall = (oldInstalled + oldOn).distinct()
            .takeIf { list -> !dismissed || list.any { it in oldOn } }
            .orEmpty()
        return OtherAmbersNotice(uninstall = uninstall, alsoOn = servicesOn.filter { it !in uninstall })
    }

    /** The old Amber: app.amber, or one of its build types. */
    fun isOld(pkg: String) = pkg == OLD_PACKAGE || pkg.startsWith("$OLD_PACKAGE.")

    private fun isAmber(pkg: String) = pkg == PACKAGE || pkg.startsWith("$PACKAGE.") || isOld(pkg)

    private fun parse(entry: String): Pair<String, String>? {
        val slash = entry.indexOf('/')
        if (slash <= 0 || slash == entry.length - 1) return null
        val pkg = entry.substring(0, slash)
        val cls = entry.substring(slash + 1).let { if (it.startsWith(".")) pkg + it else it }
        if ('/' in cls) return null
        return pkg to cls
    }
}
