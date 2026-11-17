package app.amethyst.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Where Amber's screens say that one of their text fields has focus: the Graph's
 * `ownFieldFocused`, provided by [MainActivity]. Null where nobody listens - previews and
 * screenshots.
 */
val LocalOwnField = staticCompositionLocalOf<MutableStateFlow<Boolean>?> { null }

/**
 * For a text field of Amber's own that the bubble comes for: tells the accessibility service
 * while the field has focus and its screen is in front. Below Android 13 nothing else would:
 * a Compose field gives Android no focused node to name (Samsung Galaxy A20e, Android 11).
 * Leaving the screen - another app, the screen going off - counts as losing the focus.
 */
fun Modifier.reportsOwnField(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val resumed by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val report = LocalOwnField.current
    val reporting = focused && resumed.isAtLeast(Lifecycle.State.RESUMED)
    DisposableEffect(report, reporting) {
        if (reporting) report?.value = true
        onDispose { if (reporting) report?.value = false }
    }
    Modifier.onFocusChanged { focused = it.isFocused }
}
