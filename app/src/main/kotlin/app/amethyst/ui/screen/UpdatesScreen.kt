package app.amethyst.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.update.GithubReleases
import app.amethyst.core.update.ReleaseChannel
import app.amethyst.core.update.UpdatePolicy
import app.amethyst.ui.AmberIcons
import app.amethyst.ui.GhostButton
import app.amethyst.ui.GroupFootnote
import app.amethyst.ui.MainSwitch
import app.amethyst.ui.NavRow
import app.amethyst.ui.RowDivider
import app.amethyst.ui.SettingsGroup

/** What the check sends and what GitHub learns from it - said where it is switched on or off. */
private const val WHAT_IT_ASKS =
    "Checking asks GitHub which Amber release is the latest, and nothing else: no account, no ID, nothing " +
        "about your dictations. GitHub sees your IP address, that Amber asks, and which version."

/** What installing a newer release does, said the same way on Home and on the Updates page. */
internal const val INSTALLS_OVER = "Installs over this one; your settings stay."

/**
 * The update check of the GitHub build: the main switch, what it asks, how the last check went
 * and "Check now". Any other build gets its updates from where it was installed and only says so.
 */
@Composable
fun UpdatesScreen(
    settings: AmethystSettings,
    channel: ReleaseChannel,
    installedVersion: String,
    checking: Boolean,
    /** For "3 hours ago"; passed in so the page does not read the clock itself. */
    now: Long,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
    onCheckNow: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SubPage(modifier) {
        if (channel.checksForUpdates) {
            CheckedUpdates(settings, installedVersion, checking, now, onUpdateSettings, onCheckNow, onOpenUrl)
        } else {
            SettingsGroup(label = null) {
                NavRow(
                    "Updates", "This Amber gets its updates from where you installed it. It never looks for them itself.",
                    icon = AmberIcons.Update, summaryMaxLines = Int.MAX_VALUE, trailing = null, onClick = null,
                )
                RowDivider()
                NavRow("This version", installedVersion.ifBlank { "Unknown" }, icon = AmberIcons.Info, trailing = null, onClick = null)
            }
        }
    }
}

@Composable
private fun CheckedUpdates(
    settings: AmethystSettings,
    installedVersion: String,
    checking: Boolean,
    now: Long,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
    onCheckNow: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val updates = settings.updates
    val available = UpdatePolicy.available(ReleaseChannel.GITHUB, updates, installedVersion)
    MainSwitch(
        label = "Check for updates",
        summary = "Once a day, on GitHub.",
        checked = updates.enabled,
        onChange = { on -> onUpdateSettings { it.copy(updates = it.updates.copy(enabled = on)) } },
    )
    GroupFootnote(WHAT_IT_ASKS, AmberIcons.Lock)

    SettingsGroup("Status") {
        NavRow("This version", installedVersion.ifBlank { "Unknown" }, icon = AmberIcons.Info, trailing = null, onClick = null)
        RowDivider()
        if (available != null) {
            NavRow(
                "Amber ${available.version}", INSTALLS_OVER,
                icon = AmberIcons.Download, summaryMaxLines = 2,
                trailing = { GhostButton("Download", { onOpenUrl(available.url) }) },
                onClick = { onOpenUrl(available.url) },
            )
            RowDivider()
        }
        NavRow(
            "Last check", UpdatePolicy.lastCheck(updates, installedVersion, now),
            icon = AmberIcons.Update, summaryMaxLines = 2,
            trailing = {
                GhostButton(if (checking) "Checking …" else "Check now", onCheckNow, enabled = updates.enabled && !checking)
            },
            onClick = null,
        )
    }

    SettingsGroup(label = null) {
        NavRow(
            "All releases", "What changed in each version", icon = AmberIcons.OpenInNew,
        ) { onOpenUrl(GithubReleases.RELEASES_URL) }
    }
}
