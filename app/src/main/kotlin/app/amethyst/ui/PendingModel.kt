package app.amethyst.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.core.engine.DownloadNotice
import app.amethyst.core.engine.EngineOption
import app.amethyst.core.engine.ModelDownload
import app.amethyst.core.onboarding.PendingStage
import app.amethyst.ui.theme.AmberPalette

/**
 * A model the first run still fetches for this phone ([app.amethyst.core.onboarding.FirstRun.wanted]):
 * the small one that stands in, the better one that takes over from it, or the active
 * recognition's own.
 */
class PendingModel(
    /** The model on its way now. */
    val option: EngineOption,
    /** Running, held for Wi-Fi, between two tries, or null: about to start, or [failed]. */
    val download: ModelDownload?,
    /** The download gave up; only another try helps. */
    val failed: Boolean,
    /** What it is for ([app.amethyst.core.onboarding.FirstRun.stage]). */
    val stage: PendingStage,
    /** The recognition that takes over by itself once its model is here; null when the active one stays. */
    val takesOver: EngineOption? = null,
    /** Fetch it now; true to go over mobile data as well. */
    val onDownload: (mobileData: Boolean) -> Unit,
) {
    /** Another recognition hears the dictations meanwhile; otherwise a dictation waits for this model. */
    val standIn: Boolean get() = stage == PendingStage.SMALL_STANDS_IN || stage == PendingStage.ANDROID_STANDS_IN
}

/**
 * One line that says what is happening - "A small speech model comes first, the better one
 * follows on Wi-Fi", "Downloading the better speech model - until then the small one is used" -
 * with the progress, or the way to start it without Wi-Fi. Without [progress] the line stands
 * alone: Home shows the active recognition's download below the flame already.
 */
@Composable
fun PendingModelNote(pending: PendingModel, modifier: Modifier = Modifier, progress: Boolean = pending.standIn) {
    val megabytes = stringResource(R.string.megabytes, (pending.option.downloadBytes / 1_000_000).toInt())
    val words = PendingWords.of(pending.stage)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val download = pending.download
        when {
            download == ModelDownload.WaitingForWifi -> {
                Hint(stringResource(words.wifi, megabytes), AmberPalette.Text)
                GhostButton(stringResource(R.string.engine_step_download_mobile, megabytes), { pending.onDownload(true) })
            }
            // Nothing to do but wait; "Try again" for someone who would rather not.
            download == ModelDownload.Retrying -> {
                Hint(stringResource(words.retrying), AmberPalette.Text)
                GhostButton(stringResource(R.string.try_again), { pending.onDownload(false) })
            }
            pending.failed -> {
                Hint(stringResource(words.stopped), AmberPalette.Text)
                GhostButton(stringResource(R.string.try_again), { pending.onDownload(false) })
            }
            // Also for the moment between the choice and the first byte.
            else -> {
                Hint(stringResource(words.downloading, megabytes), AmberPalette.Text)
                // Moving while it has not started or its size is not known yet.
                if (progress) ProgressLine((download as? ModelDownload.Running)?.let(DownloadNotice::fraction))
            }
        }
    }
}

/** The line's words for each state of the download, by what the model is for. */
private class PendingWords(
    @StringRes val downloading: Int,
    @StringRes val wifi: Int,
    @StringRes val retrying: Int,
    @StringRes val stopped: Int,
) {
    companion object {
        fun of(stage: PendingStage): PendingWords = when (stage) {
            PendingStage.SMALL_FIRST -> PendingWords(R.string.small_first_downloading, R.string.small_first_wifi, R.string.small_first_retrying, R.string.small_first_stopped)
            PendingStage.SMALL_STANDS_IN -> PendingWords(R.string.better_downloading, R.string.better_wifi, R.string.better_retrying, R.string.better_stopped)
            PendingStage.ANDROID_STANDS_IN -> PendingWords(R.string.pending_downloading, R.string.pending_wifi, R.string.pending_retrying, R.string.pending_stopped)
            PendingStage.OWN -> PendingWords(R.string.wanted_downloading, R.string.wanted_wifi, R.string.wanted_retrying, R.string.wanted_stopped)
        }
    }
}
