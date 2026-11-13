package app.amethyst.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.core.engine.EngineOption
import app.amethyst.core.engine.ModelDownload
import app.amethyst.ui.theme.AmberPalette

/**
 * The speech recognition chosen for this phone while its model is still on its way and
 * Android's recognition stands in ([app.amethyst.core.onboarding.FirstRun]).
 */
class PendingModel(
    val option: EngineOption,
    /** Running, held for Wi-Fi, or null: about to start, or [failed]. */
    val download: ModelDownload?,
    /** The download gave up; only another try helps. */
    val failed: Boolean,
    /** Fetch it now; true to go over mobile data as well. */
    val onDownload: (mobileData: Boolean) -> Unit,
)

/**
 * One line that says what is happening - "Downloading the speech model - until then Android's
 * speech recognition is used" - with the progress, or the way to start it without Wi-Fi.
 */
@Composable
fun PendingModelNote(pending: PendingModel, modifier: Modifier = Modifier) {
    val megabytes = stringResource(R.string.megabytes, (pending.option.downloadBytes / 1_000_000).toInt())
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val download = pending.download
        when {
            download == ModelDownload.WaitingForWifi -> {
                Hint(stringResource(R.string.pending_wifi, megabytes), AmberPalette.Text)
                GhostButton(stringResource(R.string.engine_step_download_mobile, megabytes), { pending.onDownload(true) })
            }
            pending.failed -> {
                Hint(stringResource(R.string.pending_stopped), AmberPalette.Text)
                GhostButton(stringResource(R.string.try_again), { pending.onDownload(false) })
            }
            // Also for the moment between the choice and the first byte.
            else -> {
                Hint(stringResource(R.string.pending_downloading), AmberPalette.Text)
                ProgressLine((download as? ModelDownload.Running)?.let { if (it.total > 0) it.received.toFloat() / it.total else 0f })
            }
        }
    }
}
