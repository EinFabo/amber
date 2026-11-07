package app.amethyst.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.amethyst.R
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
import app.amethyst.ui.words

/** What the check sends and what GitHub learns from it - said where it is switched on or off. */

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
                    stringResource(R.string.settings_updates), stringResource(R.string.updates_elsewhere),
                    icon = AmberIcons.Update, summaryMaxLines = Int.MAX_VALUE, trailing = null, onClick = null,
                )
                RowDivider()
                NavRow(stringResource(R.string.updates_this_version), installedVersion.ifBlank { stringResource(R.string.unknown) }, icon = AmberIcons.Info, trailing = null, onClick = null)
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
        label = stringResource(R.string.updates_check),
        summary = stringResource(R.string.updates_check_summary),
        checked = updates.enabled,
        onChange = { on -> onUpdateSettings { it.copy(updates = it.updates.copy(enabled = on)) } },
    )
    GroupFootnote(stringResource(R.string.updates_what_it_asks), AmberIcons.Lock)

    SettingsGroup(stringResource(R.string.updates_status)) {
        NavRow(stringResource(R.string.updates_this_version), installedVersion.ifBlank { stringResource(R.string.unknown) }, icon = AmberIcons.Info, trailing = null, onClick = null)
        RowDivider()
        if (available != null) {
            NavRow(
                stringResource(R.string.updates_amber_version, available.version), stringResource(R.string.installs_over),
                icon = AmberIcons.Download, summaryMaxLines = 2,
                trailing = { GhostButton(stringResource(R.string.download), { onOpenUrl(available.url) }) },
                onClick = { onOpenUrl(available.url) },
            )
            RowDivider()
        }
        NavRow(
            stringResource(R.string.updates_last_check), words(UpdatePolicy.lastCheck(updates, installedVersion, now)),
            icon = AmberIcons.Update, summaryMaxLines = 2,
            trailing = {
                GhostButton(stringResource(if (checking) R.string.updates_checking else R.string.updates_check_now), onCheckNow, enabled = updates.enabled && !checking)
            },
            onClick = null,
        )
    }

    SettingsGroup(label = null) {
        NavRow(
            stringResource(R.string.updates_all_releases), stringResource(R.string.updates_all_releases_summary), icon = AmberIcons.OpenInNew,
        ) { onOpenUrl(GithubReleases.RELEASES_URL) }
    }
}
