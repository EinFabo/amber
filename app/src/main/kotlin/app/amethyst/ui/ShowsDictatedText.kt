package app.amethyst.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import app.amethyst.SecureWindow

/**
 * Part of a screen that shows dictated words while [active]: its window is kept out of
 * screenshots and the Recents picture for as long as it does ([SecureWindow]), and shown again
 * once no part does or the screen is left.
 */
@Composable
fun ShowsDictatedText(active: Boolean) {
    val activity = SecureWindow.activityOf(LocalContext.current) ?: return
    DisposableEffect(activity, active) {
        val release = if (active) SecureWindow.showsDictatedText(activity) else null
        onDispose { release?.invoke() }
    }
}
