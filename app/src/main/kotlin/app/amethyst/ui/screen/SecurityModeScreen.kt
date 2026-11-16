package app.amethyst.ui.screen

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.amethyst.R
import app.amethyst.core.privacy.SecurityMode
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.ui.AmberIcons
import app.amethyst.ui.MainSwitch
import app.amethyst.ui.NavRow
import app.amethyst.ui.RowDivider
import app.amethyst.ui.SettingsGroup

/**
 * The security mode ([SecurityMode]): one switch, what it does when on - in plain words, row by
 * row - and what it cannot do. Switching it on deletes what is kept now ([onChange] true does).
 */
@Composable
fun SecurityModeScreen(
    settings: AmethystSettings,
    onChange: (Boolean) -> Unit,
) {
    val on = SecurityMode.on(settings)
    SubPage {
        MainSwitch(
            label = stringResource(R.string.settings_security_mode),
            checked = on,
            onChange = onChange,
            summary = stringResource(R.string.security_switch_note),
        )
        SettingsGroup(stringResource(R.string.security_on_title), note = stringResource(R.string.security_wipes)) {
            Fact(AmberIcons.Lock, R.string.security_nothing_kept, R.string.security_nothing_kept_note)
            RowDivider()
            Fact(AmberIcons.Bug, R.string.security_diagnostics, R.string.security_diagnostics_note)
            RowDivider()
            Fact(AmberIcons.BarChart, R.string.security_stats, R.string.security_stats_note)
            RowDivider()
            Fact(AmberIcons.Subject, R.string.security_clipboard, R.string.security_clipboard_note)
            RowDivider()
            Fact(AmberIcons.Contrast, R.string.security_screens, R.string.security_screens_note)
        }
        SettingsGroup(stringResource(R.string.security_limits_title)) {
            Fact(AmberIcons.Apps, R.string.security_installed, R.string.security_installed_note)
            RowDivider()
            Fact(AmberIcons.TextFields, R.string.security_other_apps, R.string.security_other_apps_note)
            RowDivider()
            Fact(AmberIcons.CloudOff, R.string.security_servers, R.string.security_servers_note)
        }
    }
}

/** A row that states a fact: its icon, a title and the whole explanation under it. */
@Composable
private fun Fact(icon: ImageVector, @StringRes title: Int, @StringRes detail: Int) {
    NavRow(stringResource(title), stringResource(detail), icon = icon, summaryMaxLines = Int.MAX_VALUE, trailing = null, onClick = null)
}
