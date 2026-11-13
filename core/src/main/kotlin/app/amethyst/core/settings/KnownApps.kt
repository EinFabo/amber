package app.amethyst.core.settings

/** What kind of writing an app is for; decides the style its profile starts with. */
enum class AppKind { MESSENGER, MAIL_AND_WORK, NOTES, TERMINAL }

/** An app Amber knows by its package, under the name its maker gives it. */
data class KnownApp(val packageName: String, val label: String, val kind: AppKind)

/**
 * Well-known apps and how a dictation should read in them: messengers casual, mail and work
 * formal, notes concise, terminals as recognized - a shell command must not be reworded. A
 * new install gets a profile for each one that is on the phone ([AppProfiles.defaultsFor]),
 * never for one that is not: nobody should find Outlook in their list without having it.
 */
object KnownApps {

    val ALL: List<KnownApp> = listOf(
        KnownApp("com.whatsapp", "WhatsApp", AppKind.MESSENGER),
        KnownApp("com.whatsapp.w4b", "WhatsApp Business", AppKind.MESSENGER),
        KnownApp("org.telegram.messenger", "Telegram", AppKind.MESSENGER),
        KnownApp("org.thoughtcrime.securesms", "Signal", AppKind.MESSENGER),
        KnownApp("ch.threema.app", "Threema", AppKind.MESSENGER),
        KnownApp("com.facebook.orca", "Messenger", AppKind.MESSENGER),
        KnownApp("com.google.android.apps.messaging", "Messages", AppKind.MESSENGER),
        KnownApp("com.samsung.android.messaging", "Samsung Messages", AppKind.MESSENGER),
        KnownApp("com.discord", "Discord", AppKind.MESSENGER),
        KnownApp("com.instagram.android", "Instagram", AppKind.MESSENGER),
        KnownApp("com.snapchat.android", "Snapchat", AppKind.MESSENGER),
        KnownApp("im.vector.app", "Element", AppKind.MESSENGER),

        KnownApp("com.google.android.gm", "Gmail", AppKind.MAIL_AND_WORK),
        KnownApp("com.microsoft.office.outlook", "Outlook", AppKind.MAIL_AND_WORK),
        KnownApp("com.samsung.android.email.provider", "Samsung Email", AppKind.MAIL_AND_WORK),
        KnownApp("ch.protonmail.android", "Proton Mail", AppKind.MAIL_AND_WORK),
        KnownApp("net.thunderbird.android", "Thunderbird", AppKind.MAIL_AND_WORK),
        KnownApp("com.fsck.k9", "K-9 Mail", AppKind.MAIL_AND_WORK),
        KnownApp("com.microsoft.teams", "Teams", AppKind.MAIL_AND_WORK),
        KnownApp("com.Slack", "Slack", AppKind.MAIL_AND_WORK),
        KnownApp("com.linkedin.android", "LinkedIn", AppKind.MAIL_AND_WORK),
        KnownApp("com.google.android.apps.docs.editors.docs", "Google Docs", AppKind.MAIL_AND_WORK),
        KnownApp("com.microsoft.office.word", "Word", AppKind.MAIL_AND_WORK),

        KnownApp("com.google.android.keep", "Keep", AppKind.NOTES),
        KnownApp("com.samsung.android.app.notes", "Samsung Notes", AppKind.NOTES),
        KnownApp("com.microsoft.office.onenote", "OneNote", AppKind.NOTES),
        KnownApp("md.obsidian", "Obsidian", AppKind.NOTES),
        KnownApp("notion.id", "Notion", AppKind.NOTES),
        KnownApp("com.todoist", "Todoist", AppKind.NOTES),
        KnownApp("com.google.android.apps.tasks", "Google Tasks", AppKind.NOTES),

        KnownApp("com.termux", "Termux", AppKind.TERMINAL),
        KnownApp("org.connectbot", "ConnectBot", AppKind.TERMINAL),
        KnownApp("com.server.auditor.ssh.client", "Termius", AppKind.TERMINAL),
        KnownApp("com.sonelli.juicessh", "JuiceSSH", AppKind.TERMINAL),
    )

    /** The profile [app] starts with. */
    fun profile(app: KnownApp): AppProfile = when (app.kind) {
        AppKind.MESSENGER -> AppProfile(app.packageName, app.label, WritingStyle.CASUAL)
        AppKind.MAIL_AND_WORK -> AppProfile(app.packageName, app.label, WritingStyle.FORMAL)
        AppKind.NOTES -> AppProfile(app.packageName, app.label, WritingStyle.CONCISE)
        AppKind.TERMINAL -> AppProfile(app.packageName, app.label, WritingStyle.AS_SPOKEN, appCleanup = AppCleanup(AppCleanupMode.OFF))
    }
}
