package app.amethyst.ui

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes

/**
 * A change made with one tap that a second tap takes back: [offer] says what happened, with
 * Undo beside it, at the bottom of sayEmber's screens. The change is made at once; [offer]'s
 * `onUndo` runs only if Undo is tapped before the message goes. One host for the whole app
 * ([UndoHost]), so a page that closes with the change - an app's page after "Remove" - still
 * shows it, and a second change replaces the first one's message.
 */
fun interface UndoOffer {
    fun offer(message: String, onUndo: () -> Unit)
}

/** The app's [UndoOffer]; nothing outside sayEmber's screens (a preview) offers an Undo. */
val LocalUndo = staticCompositionLocalOf { UndoOffer { _, _ -> } }

/** Where [UndoOffer]'s messages show: one Snackbar at a time, in the app's colours. */
@Composable
fun UndoHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        Snackbar(
            data,
            shape = AmberShapes.Inner,
            containerColor = AmberPalette.Elevated,
            contentColor = AmberPalette.Text,
            actionColor = AmberPalette.Primary,
        )
    }
}
