package app.amethyst.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import app.amethyst.R
import app.amethyst.Texts
import app.amethyst.core.settings.Attention
import app.amethyst.core.settings.ReliabilityItem
import app.amethyst.core.settings.Summary
import app.amethyst.core.ui.SettingsPage
import app.amethyst.ui.AmberIcons
import app.amethyst.ui.NavRow
import app.amethyst.ui.SettingsGroup
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.words

/**
 * The row on top of the Settings page while something needs a look
 * ([app.amethyst.core.settings.SettingsAttention]): what, in a few words, and a tap leads to
 * the page that puts it right.
 */
@Composable
internal fun AttentionRow(attention: Attention, onOpen: (SettingsPage) -> Unit) {
    SettingsGroup(label = null) {
        when (attention) {
            is Attention.Reliability -> NavRow(
                title = words(Summary.ReliabilityOpen(attention.open.size)),
                summary = attention.open.map { stringResource(it.label()) }.joinToString(", "),
                icon = AmberIcons.Warning,
                summaryColor = AmberPalette.Primary,
                summaryMaxLines = 2,
            ) { onOpen(SettingsPage.RELIABILITY) }
            is Attention.Cleanup -> NavRow(
                title = stringResource(R.string.attention_cleanup),
                summary = Texts.cleanupTrouble(LocalContext.current, attention.trouble),
                icon = AmberIcons.Warning,
                summaryColor = AmberPalette.Primary,
            ) { onOpen(SettingsPage.CLEANUP) }
            is Attention.Update -> NavRow(
                title = stringResource(R.string.update_available_title, attention.version),
                summary = stringResource(R.string.settings_updates),
                icon = AmberIcons.Warning,
                summaryColor = AmberPalette.Primary,
            ) { onOpen(SettingsPage.UPDATES) }
        }
    }
}

/** The thing by the name the Reliability page gives it. */
private fun ReliabilityItem.label(): Int = when (this) {
    ReliabilityItem.NOTIFICATIONS -> R.string.reliability_notifications
    ReliabilityItem.BATTERY -> R.string.reliability_battery
}
