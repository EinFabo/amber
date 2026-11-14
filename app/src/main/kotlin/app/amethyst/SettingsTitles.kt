package app.amethyst

import android.content.Context
import app.amethyst.core.settings.SettingsItem

/** The Settings page's rows by name, for a text that lists them (the folded "Advanced settings" row). */
object SettingsTitles {

    /** A settings row's title, as the Settings page shows it; a part of a page goes by its page. */
    fun of(context: Context, item: SettingsItem): String = context.getString(
        when (item) {
            SettingsItem.RECOGNITION, SettingsItem.APP_RECOGNITION -> R.string.settings_recognition
            SettingsItem.CLEANUP, SettingsItem.CLEANUP_INSTRUCTION -> R.string.settings_cleanup
            SettingsItem.BUBBLE, SettingsItem.QUICK_MENU -> R.string.settings_bubble
            SettingsItem.VOICE_COMMANDS -> R.string.settings_voice_commands
            SettingsItem.APP_STYLES -> R.string.settings_app_styles
            SettingsItem.FALLBACK -> R.string.settings_fallback
            SettingsItem.RECORDING -> R.string.settings_recording
            SettingsItem.INSERTING -> R.string.settings_inserting
            SettingsItem.RELIABILITY -> R.string.settings_reliability
            SettingsItem.APPEARANCE -> R.string.settings_appearance
            SettingsItem.ABOUT -> R.string.settings_about
        },
    )
}
