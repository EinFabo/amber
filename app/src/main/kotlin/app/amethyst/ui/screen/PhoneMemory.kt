package app.amethyst.ui.screen

import android.app.ActivityManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.amethyst.R
import app.amethyst.ui.Hint
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes

/** The memory the phone reports (Android's totalMem) in MB, 0 when it gives none. */
@Composable
internal fun rememberTotalRamMb(): Long {
    val context = LocalContext.current
    return remember {
        runCatching {
            val info = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
            info.totalMem / 1_000_000
        }.getOrDefault(0L)
    }
}

/** A model too big for the phone's memory is not forbidden, only asked about first. */
@Composable
internal fun TooBigDialog(name: String, onUse: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AmberPalette.Surface,
        shape = AmberShapes.Card,
        title = { Text(stringResource(R.string.model_too_big_title, name), color = AmberPalette.Text) },
        text = { Hint(stringResource(R.string.model_too_big_text)) },
        confirmButton = {
            TextButton(onClick = onUse) { Text(stringResource(R.string.model_too_big_use), color = AmberPalette.Primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = AmberPalette.Muted) }
        },
    )
}
