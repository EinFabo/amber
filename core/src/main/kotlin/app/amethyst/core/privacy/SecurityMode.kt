package app.amethyst.core.privacy

import app.amethyst.core.history.HistoryKeep
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.PrivacySettings
import app.amethyst.core.stats.DictationStat

/**
 * The security mode, for whoever does not want anyone who takes their phone to read what they
 * dictated: every "may this be kept?" Amber asks, answered here.
 *
 * On, nothing of a dictation is kept beyond its insertion - no recording for "Try again", no
 * last dictation for Home, Insert again or Copy last, no text for Undo, no words in Diagnostics
 * (only their shape), no history; the statistics keep counts and times without the app; the
 * insertion method per app is not written down; Amber never writes the clipboard; its own
 * windows are kept out of screenshots and Recents. Switching it on forgets what is kept now
 * ([wipesOnSwitch]): everything [app.amethyst.core.dictation.DictationMemory] holds, the app
 * names in the statistics ([withoutApps]) and the insertion methods per app.
 *
 * What it cannot do, and its page says so: hide that Amber is installed, reach text already in
 * other apps, or keep a recording or a text from the server or cleanup the user chose.
 */
object SecurityMode {

    fun on(privacy: PrivacySettings): Boolean = privacy.securityMode

    /**
     * Whether the security mode is on. Every question below takes the settings as they are now,
     * asked at the moment something would be kept - not a copy from when a dictation began, which
     * may be from before the mode went on. Null settings are not read yet (a process just
     * started): until they are, Amber acts as if the mode were on, so nothing slips through in
     * the moment before.
     */
    fun on(settings: AmethystSettings?): Boolean = settings == null || on(settings.privacy)

    /** Whether a dictation's words, its recording or its text for Undo are kept after it ends. */
    fun keepsWords(settings: AmethystSettings?): Boolean = !on(settings)

    /**
     * Whether a dictation may go through the clipboard - a paste, or the copy when the field
     * took nothing: only with the user's clipboard switch on, never in the security mode.
     */
    fun mayUseClipboard(settings: AmethystSettings?): Boolean = !on(settings) && settings?.insertion?.allowClipboard == true

    /** Whether Amber may put anything on the clipboard at all: Copy last, a history entry, a Diagnostics report. */
    fun mayCopy(settings: AmethystSettings?): Boolean = !on(settings)

    /** The app a dictation went into, as the statistics keep it: none in the security mode. */
    fun statApp(settings: AmethystSettings?, app: String?): String? = app.takeUnless { on(settings) }

    /** [stat] as the statistics may write it now: without its app in the security mode ([statApp]). */
    fun statToKeep(settings: AmethystSettings?, stat: DictationStat): DictationStat =
        if (stat.app == null || statApp(settings, stat.app) != null) stat else stat.copy(app = null)

    /** Whether the insertion method that worked is written down per app. */
    fun remembersApps(settings: AmethystSettings?): Boolean = !on(settings)

    /** How long the history keeps a dictation: as chosen, and not at all in the security mode. */
    fun historyKeep(settings: AmethystSettings?): HistoryKeep = if (settings == null || on(settings)) HistoryKeep.OFF else settings.privacy.history

    /** Whether Amber's own windows are kept out of screenshots, screen recordings and the Recents thumbnail. */
    fun hidesWindows(settings: AmethystSettings?): Boolean = on(settings)

    /**
     * Whether, on reading the settings at a start ([before] null: nothing read before), what the
     * mode keeps from being written is looked for again - app names in the statistics, insertion
     * methods per app - and removed if found. The process may have died between switching the
     * mode on and forgetting, or the forgetting failed; looking at every start with the mode on
     * finds what is left. Removing nothing changes nothing, so asking again is harmless.
     */
    fun repairsAtStart(before: PrivacySettings?, current: AmethystSettings): Boolean = before == null && on(current)

    /** Whether [stats] still name an app - left over from before the mode went on ([repairsAtStart]). */
    fun namesApps(stats: List<DictationStat>): Boolean = stats.any { it.app != null }

    /**
     * Whether a change from [before] to [after] switched the security mode on - the moment to
     * forget what is kept. Null [before]: the settings were only just read, nothing switched.
     */
    fun wipesOnSwitch(before: PrivacySettings?, after: PrivacySettings): Boolean =
        before != null && !before.securityMode && after.securityMode

    /** [stats] without the app each went into, for the file written again when the mode goes on. */
    fun withoutApps(stats: List<DictationStat>): List<DictationStat> = stats.map { if (it.app == null) it else it.copy(app = null) }
}
