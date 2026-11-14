package app.amethyst.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.amethyst.R
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.SettingsSummaries
import app.amethyst.core.update.ReleaseChannel
import app.amethyst.core.update.UpdatePolicy
import app.amethyst.ui.AmberIcons
import app.amethyst.ui.NavRow
import app.amethyst.ui.RowDivider
import app.amethyst.ui.SettingsGroup
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.words

/**
 * What is about Amber rather than a setting: its version and the way to a newer one, what it
 * counted and recorded of the dictations (to look at - their pages hold the one or two switches
 * that go with them), the licenses, and the introduction once more. The same in the simple and
 * the advanced view: nothing here is a setting the switch could hide.
 */
@Composable
fun AboutScreen(
    settings: AmethystSettings,
    diagnosticsCount: Int,
    version: String,
    /** Which build this is - only the GitHub build checks for updates itself; null until known. */
    releaseChannel: ReleaseChannel?,
    onOpen: (SettingsPage) -> Unit,
    onReplayIntroduction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val updateAvailable = releaseChannel?.let { UpdatePolicy.available(it, settings.updates, version) } != null
    SubPage(modifier) {
        SettingsGroup(label = null) {
            NavRow(
                stringResource(R.string.updates_this_version), version.ifBlank { stringResource(R.string.unknown) },
                icon = AmberIcons.Info, trailing = null, onClick = null,
            )
            RowDivider()
            NavRow(
                stringResource(R.string.settings_updates), releaseChannel?.let { words(UpdatePolicy.summary(it, settings.updates, version)) }, icon = AmberIcons.Update,
                summaryColor = if (updateAvailable) AmberPalette.Primary else AmberPalette.Muted,
                // "From where you installed Amber" is a line and a half in German.
                summaryMaxLines = 2,
            ) { onOpen(SettingsPage.UPDATES) }
        }
        SettingsGroup(stringResource(R.string.about_group_dictations)) {
            NavRow(stringResource(R.string.settings_statistics), words(SettingsSummaries.onOff(settings.stats.keep)), icon = AmberIcons.BarChart) { onOpen(SettingsPage.STATS) }
            RowDivider()
            NavRow(stringResource(R.string.settings_diagnostics), words(SettingsSummaries.diagnostics(diagnosticsCount)), icon = AmberIcons.Bug) { onOpen(SettingsPage.DIAGNOSTICS) }
        }
        SettingsGroup(label = null) {
            NavRow(
                stringResource(R.string.settings_licenses), stringResource(R.string.settings_licenses_summary), icon = AmberIcons.Subject, summaryMaxLines = 2,
            ) { onOpen(SettingsPage.LICENSES) }
            RowDivider()
            NavRow(
                stringResource(R.string.settings_introduction), stringResource(R.string.settings_introduction_summary), icon = AmberIcons.Replay,
                summaryMaxLines = 2, onClick = onReplayIntroduction,
            )
        }
    }
}
