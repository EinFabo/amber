package app.amethyst.core.settings

/** The groups of the Settings page, top to bottom. */
enum class SettingsGroupKind { MAIN, PER_APP, ADVANCED, AMBER }

/**
 * Everything the "Advanced settings" switch shows or hides: the rows of the Settings page, in
 * their order ([group] set), and parts of a page ([group] null). [simple]: shown without the
 * switch too.
 */
enum class SettingsItem(val simple: Boolean, val group: SettingsGroupKind?) {
    RECOGNITION(simple = true, SettingsGroupKind.MAIN),
    CLEANUP(simple = true, SettingsGroupKind.MAIN),
    BUBBLE(simple = true, SettingsGroupKind.MAIN),
    VOICE_COMMANDS(simple = false, SettingsGroupKind.MAIN),
    APP_STYLES(simple = true, SettingsGroupKind.PER_APP),
    FALLBACK(simple = false, SettingsGroupKind.ADVANCED),
    RECORDING(simple = false, SettingsGroupKind.ADVANCED),
    INSERTING(simple = false, SettingsGroupKind.ADVANCED),
    RELIABILITY(simple = false, SettingsGroupKind.ADVANCED),
    STATISTICS(simple = false, SettingsGroupKind.AMBER),
    DIAGNOSTICS(simple = false, SettingsGroupKind.AMBER),

    /** About Amber rather than a setting, so always there - as is the way back into the introduction. */
    UPDATES(simple = true, SettingsGroupKind.AMBER),
    LICENSES(simple = true, SettingsGroupKind.AMBER),
    INTRODUCTION(simple = true, SettingsGroupKind.AMBER),

    /** On the Cleanup page: the own addition for every app and the system prompt to read. */
    CLEANUP_INSTRUCTION(simple = false, group = null),

    /** On an app's page: the speech recognition of its own (roadmap: per-app recognition is advanced). */
    APP_RECOGNITION(simple = false, group = null),
}

/** A group of the Settings page with the rows it shows; see [SettingsLayout.groups]. */
data class SettingsGroupRows(val kind: SettingsGroupKind, val items: List<SettingsItem>)

/**
 * Which settings show: in the simple view (a new install's) only the few most people need -
 * how speech is recognized, whether it is cleaned up, the bubble and the app styles; with
 * [UiSettings.advanced] every one. Voice commands only ever with [Features.VOICE_COMMANDS].
 */
object SettingsLayout {

    fun shows(item: SettingsItem, advanced: Boolean, voiceCommands: Boolean = Features.VOICE_COMMANDS): Boolean = when {
        item == SettingsItem.VOICE_COMMANDS && !voiceCommands -> false
        else -> item.simple || advanced
    }

    /** The groups with at least one row to show, in page order. */
    fun groups(advanced: Boolean, voiceCommands: Boolean = Features.VOICE_COMMANDS): List<SettingsGroupRows> =
        SettingsGroupKind.entries.mapNotNull { kind ->
            SettingsItem.entries
                .filter { it.group == kind && shows(it, advanced, voiceCommands) }
                .takeIf { it.isNotEmpty() }
                ?.let { SettingsGroupRows(kind, it) }
        }

    /**
     * The speech recognition section on [profile]'s page: with the advanced settings, and in the
     * simple view too while the app has one of its own - what is in effect is never out of sight.
     */
    fun showsAppRecognition(profile: AppProfile, advanced: Boolean): Boolean =
        shows(SettingsItem.APP_RECOGNITION, advanced) || profile.appEngine.mode != AppEngineMode.DEFAULT

    /** How many rows of the Settings page only the advanced view shows. */
    fun hiddenRows(voiceCommands: Boolean = Features.VOICE_COMMANDS): Int =
        groups(advanced = true, voiceCommands).sumOf { it.items.size } - groups(advanced = false, voiceCommands).sumOf { it.items.size }
}
