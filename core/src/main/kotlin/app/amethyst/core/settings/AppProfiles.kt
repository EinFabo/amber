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

/**
 * How dictation behaves in one app. Styles need a cleanup model (the engine's "Cleanup");
 * without one the text goes in as recognized.
 */
@Serializable
data class AppProfile(
    val packageName: String,
    /** Shown in the settings; resolved from the system when the user adds an app. */
    val label: String,
    val style: WritingStyle = WritingStyle.AS_SPOKEN,
    /** A free instruction on top of the style, e.g. "always sign with my first name". */
    val instruction: String? = null,
    /** Run the cleanup in this app: null follows the engine, false never, true whenever possible. */
    val cleanup: Boolean? = null,
)

object AppProfiles {

    /** What a fresh install starts with - common messengers casual, mail formal, notes concise. */
    val DEFAULTS = listOf(
        AppProfile("com.whatsapp", "WhatsApp", WritingStyle.CASUAL),
        AppProfile("org.telegram.messenger", "Telegram", WritingStyle.CASUAL),
        AppProfile("org.thoughtcrime.securesms", "Signal", WritingStyle.CASUAL),
        AppProfile("com.google.android.gm", "Gmail", WritingStyle.FORMAL),
        AppProfile("com.microsoft.office.outlook", "Outlook", WritingStyle.FORMAL),
        AppProfile("com.google.android.keep", "Keep", WritingStyle.CONCISE),
    )

    fun forPackage(profiles: List<AppProfile>, packageName: String?): AppProfile? =
        packageName?.let { name -> profiles.firstOrNull { it.packageName == name } }

    /** Replaces the profile for its package, or adds it. */
    fun upsert(profiles: List<AppProfile>, profile: AppProfile): List<AppProfile> =
        if (profiles.any { it.packageName == profile.packageName }) {
            profiles.map { if (it.packageName == profile.packageName) profile else it }
        } else {
            profiles + profile
        }
}
