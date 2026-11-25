package app.amethyst.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.amethyst.R
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.SettingsSummaries
import app.amethyst.core.ui.SettingsPage
import app.amethyst.core.update.ReleaseChannel
import app.amethyst.core.update.UpdatePolicy
import app.amethyst.ui.AmberIcons
import app.amethyst.ui.NavRow
import app.amethyst.ui.RowDivider
import app.amethyst.ui.SettingsGroup
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.words

/**
 * What is about Amber rather than a setting: its version and the way to a newer one, the
 * privacy policy, what it recorded of the dictations for finding faults (the diagnostics), the
 * licenses, and the introduction once more. The statistics are under Privacy, beside the history.
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
        SettingsGroup(label = null) {
            NavRow(
                stringResource(R.string.settings_privacy_policy), stringResource(R.string.settings_privacy_policy_summary), icon = AmberIcons.Description,
                summaryMaxLines = 2,
            ) { onOpen(SettingsPage.PRIVACY_POLICY) }
            RowDivider()
            NavRow(stringResource(R.string.settings_diagnostics), words(SettingsSummaries.diagnostics(diagnosticsCount)), icon = AmberIcons.Bug) { onOpen(SettingsPage.DIAGNOSTICS) }
            RowDivider()
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
