package app.amethyst.core.ui

/**
 * Where a settings row leads. A plain value, so the screen stack ([Navigation]) can be saved
 * and read back; the app words each page's title.
 */
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
