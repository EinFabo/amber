package app.amethyst.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.amethyst.ui.theme.AmberPalette

/** Flat near-black. Nothing behind the content competes with it. */
@Composable
fun AmberBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(AmberPalette.Background),
        content = content,
    )
}
