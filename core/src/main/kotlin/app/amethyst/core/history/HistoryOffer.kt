package app.amethyst.core.history

import app.amethyst.core.privacy.SecurityMode
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.PrivacySettings

/**
 * The history for an install from before it: its user agreed to an accessibility disclosure
 * that did not name it, so it is not switched on for them. It starts off, and Home offers it
 * once - "Turn on" keeps 24 hours, "No thanks" ends the offer for good. A new install's
 * disclosure names it, and keeps 24 hours from the start.
 */
object HistoryOffer {

    /** [privacy] as an install from before the history gets it (settings schema 15): off, and offered. */
    fun forEarlierInstall(privacy: PrivacySettings): PrivacySettings = privacy.copy(history = HistoryKeep.OFF, historyOffer = true)

    /**
     * Whether Home shows the offer: until it is answered, and not while it has nothing to offer -
     * the history already on (from its page), or the security mode, which keeps none.
     */
    fun shows(settings: AmethystSettings): Boolean =
        settings.privacy.historyOffer && settings.privacy.history == HistoryKeep.OFF && !SecurityMode.on(settings)

    /** "Turn on": 24 hours, and the offer is over. */
    fun accept(settings: AmethystSettings): AmethystSettings =
        settings.copy(privacy = settings.privacy.copy(history = HistoryKeep.DAY, historyOffer = false))

    /** "No thanks": the history stays off, and the offer is over for good. */
    fun decline(settings: AmethystSettings): AmethystSettings =
        settings.copy(privacy = settings.privacy.copy(historyOffer = false))
}
