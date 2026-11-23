package app.amethyst.core.settings

/**
 * One app on the Apps page: its [profile] if it has one (style, own instruction, own cleanup or
 * speech recognition) and whether the bubble stays out of it.
 */
data class AppRow(val packageName: String, val profile: AppProfile?, val bubbleHidden: Boolean)

/**
 * The Apps page: every app Amber treats in a way of its own. The profiles
 * ([AmethystSettings.profiles]) and the apps the bubble is hidden in
 * ([BubbleSettings.hiddenIn]) stay two stores - each is read where a dictation needs it; the
 * page only shows them side by side and writes each to its own.
 */
object AppRows {

    /**
     * The apps with a profile, in their order, then those where only the bubble is hidden, in
     * the order they were hidden - each app once.
     */
    fun of(profiles: List<AppProfile>, hiddenIn: List<String>): List<AppRow> {
        val hidden = hiddenIn.toSet()
        val withProfile = profiles.distinctBy { it.packageName }
        val styled = withProfile.mapTo(HashSet()) { it.packageName }
        return withProfile.map { AppRow(it.packageName, it, it.packageName in hidden) } +
            hiddenIn.distinct().filterNot { it in styled }.map { AppRow(it, profile = null, bubbleHidden = true) }
    }

    /** How many apps have a profile, and in how many the bubble is hidden: the Apps row's two numbers. */
    fun counts(profiles: List<AppProfile>, hiddenIn: List<String>): Pair<Int, Int> =
        profiles.distinctBy { it.packageName }.size to hiddenIn.distinct().size

    /** [bubble] shown in [packageName] or hidden there; everything else of it kept. */
    fun withBubble(bubble: BubbleSettings, packageName: String, shown: Boolean): BubbleSettings =
        bubble.copy(hiddenIn = if (shown) bubble.hiddenIn - packageName else (bubble.hiddenIn + packageName).distinct())

    /**
     * A change made on [packageName]'s page. An app that is listed only for its hidden bubble has
     * no profile yet: the first change that makes a difference gives it one under [label], in
     * the style as spoken. (A page whose app was just removed must not call this - see
     * [AppProfiles.edit].)
     */
    fun edited(profiles: List<AppProfile>, packageName: String, label: String, change: (AppProfile) -> AppProfile): List<AppProfile> {
        if (AppProfiles.forPackage(profiles, packageName) != null) return AppProfiles.edit(profiles, packageName, change)
        val plain = AppProfile(packageName, label)
        val changed = change(plain)
        return if (changed == plain) profiles else profiles + changed
    }

    /** [settings] without [packageName] on the Apps page: its profile gone, and the bubble back in it. */
    fun removed(settings: AmethystSettings, packageName: String): AmethystSettings =
        settings.copy(
            profiles = settings.profiles.filterNot { it.packageName == packageName },
            bubble = withBubble(settings.bubble, packageName, shown = true),
        )

    /**
     * The Apps page's Undo after [removed]: [packageName]'s profile from [before] back where it
     * stood, and the bubble hidden there again if it was. What changed meanwhile stays - a profile
     * the app's page made again since is the newer one and is kept.
     */
    fun restored(settings: AmethystSettings, before: AmethystSettings, packageName: String): AmethystSettings {
        val profile = AppProfiles.forPackage(before.profiles, packageName)
        val profiles = if (profile == null || AppProfiles.forPackage(settings.profiles, packageName) != null) {
            settings.profiles
        } else {
            settings.profiles.toMutableList().apply { add(before.profiles.indexOf(profile).coerceAtMost(size), profile) }
        }
        val hiddenAt = before.bubble.hiddenIn.indexOf(packageName)
        val hiddenIn = if (hiddenAt < 0 || packageName in settings.bubble.hiddenIn) {
            settings.bubble.hiddenIn
        } else {
            settings.bubble.hiddenIn.toMutableList().apply { add(hiddenAt.coerceAtMost(size), packageName) }
        }
        return settings.copy(profiles = profiles, bubble = settings.bubble.copy(hiddenIn = hiddenIn))
    }
}
