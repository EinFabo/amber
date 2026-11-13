package app.amethyst.core.settings

import kotlinx.serialization.Serializable

/** How a dictation should read once it lands; applied by the cleanup model. */
@Serializable
enum class WritingStyle {
    /** Only tidied: punctuation, fillers, mis-hearings. The words stay the speaker's. */
    AS_SPOKEN,

    /** A chat message: relaxed and natural. */
    CASUAL,

    /** An email: complete sentences, polite and professional. */
    FORMAL,

    /** A note: short, no repetition, every fact kept. */
    CONCISE,
}

@Serializable
enum class AppCleanupMode {
    /** The cleanup chosen for every app (Settings -> Cleanup), or none if that is off. */
    DEFAULT,

    /** The text goes in as recognized. */
    OFF,

    /** The model in [AppCleanup.choice], even when the cleanup for every app is off. */
    MODEL,
}

/** Which cleanup rewrites a dictation in one app; see [app.amethyst.core.engine.CleanupRouting]. */
@Serializable
data class AppCleanup(val mode: AppCleanupMode = AppCleanupMode.DEFAULT, val choice: CleanupChoice? = null)

@Serializable
enum class AppEngineMode {
    /** The speech recognition chosen for every app (Settings -> Speech recognition). */
    DEFAULT,

    /** The engine in [AppEngine.engineId], while it is ready; else the default. */
    ENGINE,
}

/** Which speech recognition hears a dictation in one app; see [app.amethyst.core.engine.EngineRouting]. */
@Serializable
data class AppEngine(val mode: AppEngineMode = AppEngineMode.DEFAULT, val engineId: String? = null)

/**
 * How dictation behaves in one app. Styles need a cleanup model; without one the text goes in
 * as recognized.
 */
@Serializable
data class AppProfile(
    val packageName: String,
    /** Shown in the settings; resolved from the system when the user adds an app. */
    val label: String,
    val style: WritingStyle = WritingStyle.AS_SPOKEN,
    /** A free instruction on top of the style, e.g. "always sign with my first name". */
    val instruction: String? = null,
    val appCleanup: AppCleanup = AppCleanup(),
    /** Absent in settings from before it: the speech recognition for every app. */
    val appEngine: AppEngine = AppEngine(),
    /** Run the cleanup in this app: null followed the engine, false never, true whenever possible. */
    @Deprecated("Since schema 9: appCleanup. Read only to migrate.")
    val cleanup: Boolean? = null,
)

object AppProfiles {

    /**
     * What every install started with before [KnownApps] - whether the apps were on the phone
     * or not. Settings stored without profiles still decode to these; a new install starts
     * with none and gets [defaultsFor] the apps it has.
     */
    val DEFAULTS = listOf(
        AppProfile("com.whatsapp", "WhatsApp", WritingStyle.CASUAL),
        AppProfile("org.telegram.messenger", "Telegram", WritingStyle.CASUAL),
        AppProfile("org.thoughtcrime.securesms", "Signal", WritingStyle.CASUAL),
        AppProfile("com.google.android.gm", "Gmail", WritingStyle.FORMAL),
        AppProfile("com.microsoft.office.outlook", "Outlook", WritingStyle.FORMAL),
        AppProfile("com.google.android.keep", "Keep", WritingStyle.CONCISE),
    )

    /**
     * The profiles a new install starts with: one for each known app ([KnownApps]) that is
     * [installed], in the catalog's order - and none for an app that has one in [existing],
     * so what the user set is never written over.
     */
    fun defaultsFor(installed: Set<String>, existing: List<AppProfile> = emptyList()): List<AppProfile> {
        val taken = existing.mapTo(HashSet()) { it.packageName }
        return KnownApps.ALL
            .filter { it.packageName in installed && it.packageName !in taken }
            .map(KnownApps::profile)
    }

    /** [profiles] untouched, with [defaultsFor] the installed apps that have none behind them. */
    fun withDefaults(profiles: List<AppProfile>, installed: Set<String>): List<AppProfile> =
        profiles + defaultsFor(installed, profiles)

    fun forPackage(profiles: List<AppProfile>, packageName: String?): AppProfile? =
        packageName?.let { name -> profiles.firstOrNull { it.packageName == name } }

    /**
     * Changes the profile for [packageName] if it is still there. A page saving on its way out
     * must not bring back a profile the user just removed.
     */
    fun edit(profiles: List<AppProfile>, packageName: String, change: (AppProfile) -> AppProfile): List<AppProfile> =
        profiles.map { if (it.packageName == packageName) change(it) else it }

    /**
     * The cleanup switched off or on again for [packageName] from the bubble, everything else of
     * its profile kept - an own model too, so on brings it back. An app without a profile gets
     * one under [label], in the style as spoken.
     */
    fun withCleanupHere(profiles: List<AppProfile>, packageName: String, label: String, on: Boolean): List<AppProfile> {
        val profile = forPackage(profiles, packageName) ?: AppProfile(packageName, label)
        val own = profile.appCleanup.choice
        val cleanup = when {
            !on -> AppCleanup(AppCleanupMode.OFF, own)
            own != null -> AppCleanup(AppCleanupMode.MODEL, own)
            else -> AppCleanup(AppCleanupMode.DEFAULT)
        }
        return upsert(profiles, profile.copy(appCleanup = cleanup))
    }

    /**
     * [profiles] with one for [packageName]: the one there is, or a new one under [label] in
     * the style as spoken - as [withCleanupHere] makes it.
     */
    fun ensure(profiles: List<AppProfile>, packageName: String, label: String): List<AppProfile> =
        if (forPackage(profiles, packageName) != null) profiles else profiles + AppProfile(packageName, label)

    /** The style after [style], round: as spoken, casual, formal, concise, as spoken. */
    fun nextStyle(style: WritingStyle): WritingStyle = WritingStyle.entries[(style.ordinal + 1) % WritingStyle.entries.size]

    /**
     * The style of [packageName] one step on ([nextStyle]), everything else of its profile kept;
     * an app without a profile gets one under [label] ([ensure]) and goes from as spoken to casual.
     */
    fun withNextStyle(profiles: List<AppProfile>, packageName: String, label: String): List<AppProfile> =
        edit(ensure(profiles, packageName, label), packageName) { it.copy(style = nextStyle(it.style)) }

    /** Replaces the profile for its package, or adds it. */
    fun upsert(profiles: List<AppProfile>, profile: AppProfile): List<AppProfile> =
        if (profiles.any { it.packageName == profile.packageName }) {
            profiles.map { if (it.packageName == profile.packageName) profile else it }
        } else {
            profiles + profile
        }
}
