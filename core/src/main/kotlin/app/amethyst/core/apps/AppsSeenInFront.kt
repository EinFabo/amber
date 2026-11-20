package app.amethyst.core.apps

/**
 * The apps the accessibility service has seen in front, the latest [MAX] of them.
 *
 * Android's package manager answers for Embersay's own profile only: an app in another one
 * (a work profile, the private space - GrapheneOS users keep WhatsApp or Signal there) counts as
 * "not installed" to it, and some profiles stay hidden from every app but the launcher. Yet the
 * accessibility service sees those apps' windows, and a package it saw in front is a real app
 * the user was in - which no other app can put on this list, unlike an intent's extra.
 */
class AppsSeenInFront {

    private val seen = LinkedHashSet<String>()

    @Synchronized
    fun add(packageName: String?) {
        if (packageName.isNullOrBlank()) return
        // Re-added, so the latest stays the longest.
        seen.remove(packageName)
        seen.add(packageName)
        if (seen.size > MAX) seen.remove(seen.first())
    }

    @Synchronized
    operator fun contains(packageName: String?): Boolean = packageName != null && packageName in seen

    companion object {
        /** More apps than anyone switches between in a session; the oldest one goes first. */
        const val MAX = 64
    }
}
