package app.amethyst.ui.screen

import androidx.annotation.StringRes
import app.amethyst.R

/** Where a settings row leads. */
enum class SettingsPage {
    RECOGNITION,
    CLEANUP,
    OWN_WORDS,
    BUBBLE,
    PRIVACY,
    HISTORY,
    SECURITY_MODE,
    QUICK_MENU,
    COMMANDS,
    APP_STYLES,
    FALLBACK,
    RECORDING,
    INSERTING,
    RELIABILITY,
    STATS,
    DIAGNOSTICS,
    UPDATES,
    LICENSES,
    PRIVACY_POLICY,
    ABOUT,
}

/** The title over a page: the label of the row that leads to it. */
@StringRes
fun SettingsPage.title(): Int = when (this) {
    SettingsPage.RECOGNITION -> R.string.settings_recognition
    SettingsPage.CLEANUP -> R.string.settings_cleanup
    SettingsPage.OWN_WORDS -> R.string.own_words
    SettingsPage.BUBBLE -> R.string.settings_bubble
    SettingsPage.PRIVACY -> R.string.settings_privacy
    SettingsPage.HISTORY -> R.string.settings_history
    SettingsPage.SECURITY_MODE -> R.string.settings_security_mode
    SettingsPage.QUICK_MENU -> R.string.settings_quick_menu
    SettingsPage.COMMANDS -> R.string.settings_voice_commands
    SettingsPage.APP_STYLES -> R.string.settings_apps
    SettingsPage.FALLBACK -> R.string.settings_fallback
    SettingsPage.RECORDING -> R.string.settings_recording
    SettingsPage.INSERTING -> R.string.settings_text
    SettingsPage.RELIABILITY -> R.string.settings_reliability
    SettingsPage.STATS -> R.string.settings_statistics
    SettingsPage.DIAGNOSTICS -> R.string.settings_diagnostics
    SettingsPage.UPDATES -> R.string.settings_updates
    SettingsPage.LICENSES -> R.string.settings_licenses
    SettingsPage.PRIVACY_POLICY -> R.string.settings_privacy_policy
    SettingsPage.ABOUT -> R.string.settings_about
}
