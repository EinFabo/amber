package app.amethyst.core.settings

/** The groups of the Settings page, top to bottom. */
enum class SettingsGroupKind { MAIN, PER_APP, AMBER }

/**
 * The rows of the Settings page, in their order. Every page is always there - nothing hides
 * behind a switch (Fabi, 2026-11-19: "ich suche oft nach Settings"); what is rarely needed sits
 * further down on its own page.
 */
enum class SettingsItem(val group: SettingsGroupKind) {
    RECOGNITION(SettingsGroupKind.MAIN),
    CLEANUP(SettingsGroupKind.MAIN),

    /**
     * The names and terms to spell right ([app.amethyst.core.text.OwnWords]): a list people come
     * back to, so a row of its own (as Wispr Flow's Dictionary, Superwhisper's Vocabulary).
     */
    OWN_WORDS(SettingsGroupKind.MAIN),

    /** The Text page: what changes the text by fixed rules - its words and commas, and how it is inserted. */
    INSERTING(SettingsGroupKind.MAIN),

    /** When a recording ends, the tones and pausing music while it runs, long dictations. */
    RECORDING(SettingsGroupKind.MAIN),
    BUBBLE(SettingsGroupKind.MAIN),

    /**
     * What is kept of a dictation, in one row: the history of recent dictations
     * ([app.amethyst.core.history.DictationHistory]), the statistics, and the security mode that
     * keeps nothing - one page, as turning the security mode on changes the history.
     */
    PRIVACY(SettingsGroupKind.MAIN),
    VOICE_COMMANDS(SettingsGroupKind.MAIN),

    /** The Apps page: each app's style, and whether the bubble is hidden in it ([AppRows]). */
    APP_STYLES(SettingsGroupKind.PER_APP),

    /** What keeps Android from cutting the microphone off: done once, then rarely looked at. */
    RELIABILITY(SettingsGroupKind.AMBER),

    /** Light or dark: how Amber looks, for everyone. */
    APPEARANCE(SettingsGroupKind.AMBER),

    /**
     * The page with what is about Amber rather than a setting: its version and updates, the
     * diagnostics to look at, the licenses, the way back into the introduction.
     */
    ABOUT(SettingsGroupKind.AMBER),
}

/** A group of the Settings page with the rows it shows; see [SettingsLayout.groups]. */
data class SettingsGroupRows(val kind: SettingsGroupKind, val items: List<SettingsItem>)

/** Which rows the Settings page shows: all of them, voice commands only ever with [Features.VOICE_COMMANDS]. */
object SettingsLayout {

    fun shows(item: SettingsItem, voiceCommands: Boolean = Features.VOICE_COMMANDS): Boolean =
        item != SettingsItem.VOICE_COMMANDS || voiceCommands

    /** The groups with at least one row to show, in page order. */
    fun groups(voiceCommands: Boolean = Features.VOICE_COMMANDS): List<SettingsGroupRows> =
        SettingsGroupKind.entries.mapNotNull { kind ->
            SettingsItem.entries
                .filter { it.group == kind && shows(it, voiceCommands) }
                .takeIf { it.isNotEmpty() }
                ?.let { SettingsGroupRows(kind, it) }
        }
}
