package app.amethyst.core.onboarding

/**
 * Another Amber on the same phone - the debug build beside the regular one, say - whose
 * accessibility service is switched on too. Two of them each show a bubble and each get the
 * keyboard and field signals, a likely cause of rare, random misbehaviour, so Amber says so.
 */
data class AmberVariant(val packageName: String) {
    /** "Amber" for app.amber, "Amber (debug)" for app.amber.debug. */
    val label: String
        get() = packageName.removePrefix(OtherAmbers.PACKAGE).removePrefix(".")
            .let { suffix -> if (suffix.isEmpty()) "Amber" else "Amber ($suffix)" }
}

object OtherAmbers {

    /** Every Amber's application id: app.amber, plus a build type's suffix (app.amber.debug). */
    const val PACKAGE = "app.amber"

    /** The service's class, the same in every variant: the code keeps its namespace. */
    const val SERVICE_CLASS = "app.amethyst.accessibility.AmethystAccessibilityService"

    /**
     * The other Ambers among [enabledServices] (Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES:
     * flattened component names, "package/class", joined by ':'), without [ownPackage].
     * Matched exactly, as Android does: package names and classes are case-sensitive. A class
     * may come short (".accessibility.X", relative to the package), as ComponentName allows.
     * Entries that are not "package/class" are skipped.
     */
    fun find(ownPackage: String, enabledServices: String?): List<AmberVariant> =
        enabledServices.orEmpty()
            .split(':')
            .mapNotNull { parse(it.trim()) }
            .filter { (pkg, cls) -> isAmber(pkg) && pkg != ownPackage && cls == SERVICE_CLASS }
            .map { (pkg, _) -> AmberVariant(pkg) }
            .distinct()

    /** "Amber (debug) is also on - switch one off." - null when there is none. */
    fun notice(others: List<AmberVariant>): String? {
        if (others.isEmpty()) return null
        val names = others.map { it.label }
        val who = if (names.size == 1) "${names[0]} is" else "${names.dropLast(1).joinToString(", ")} and ${names.last()} are"
        val what = if (names.size == 1) "switch one off" else "keep only one on"
        return "$who also on - $what."
    }

    private fun isAmber(pkg: String) = pkg == PACKAGE || pkg.startsWith("$PACKAGE.")

    private fun parse(entry: String): Pair<String, String>? {
        val slash = entry.indexOf('/')
        if (slash <= 0 || slash == entry.length - 1) return null
        val pkg = entry.substring(0, slash)
        val cls = entry.substring(slash + 1).let { if (it.startsWith(".")) pkg + it else it }
        if ('/' in cls) return null
        return pkg to cls
    }
}
