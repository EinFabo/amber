package app.amethyst.core.onboarding

import app.amethyst.core.settings.AppKind
import app.amethyst.core.settings.KnownApps
import app.amethyst.core.stats.DictationStat

/**
 * After the introduction, one line until the first dictation that went into another app:
 * "Open WhatsApp and tap a text field - the bubble appears there". [appName] names an app that
 * is on the phone, by the name its launcher shows; null: "any app".
 */
data class RealAppNudge(val appName: String?)

/** Whether to nudge toward a real app, and which one ([RealAppNudge]). */
object RealAppNudges {

    /** The kinds of app to name, in order: a messenger first, where a dictation is most at home. Never a terminal. */
    private val KINDS = listOf(AppKind.MESSENGER, AppKind.MAIL_AND_WORK, AppKind.NOTES)

    /**
     * The nudge, or null when there is none to show: once a dictation went into another app
     * (the statistics name the app only then - Amber's own field and an unknown one name none),
     * while the statistics are not kept (nothing would ever end it), where the bubble cannot
     * come up at all, and while the apps on the phone are still being read ([installed] null:
     * no "any app" that turns into WhatsApp a moment later).
     *
     * [installed]: package to the name its launcher shows.
     */
    fun of(
        stats: List<DictationStat>,
        statsKept: Boolean,
        bubbleCanShow: Boolean,
        installed: Map<String, String>?,
    ): RealAppNudge? {
        if (installed == null || !statsKept || !bubbleCanShow || stats.any { it.app != null }) return null
        return RealAppNudge(appName(installed))
    }

    /** The first known app on the phone, messengers first and in the known list's order; null when none is. */
    fun appName(installed: Map<String, String>): String? {
        val app = KINDS.firstNotNullOfOrNull { kind -> KnownApps.ALL.firstOrNull { it.kind == kind && it.packageName in installed } }
            ?: return null
        return installed[app.packageName]?.takeIf { it.isNotBlank() } ?: app.label
    }
}
