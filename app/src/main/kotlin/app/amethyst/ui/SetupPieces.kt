package app.amethyst.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes

/*
 * The setup pieces Home's setup card and the introduction share: what the accessibility
 * service reads, and the way past Android's lock on it.
 */

/** What the accessibility service reads, said in full before Android asks (security review M6). */
@Composable
fun AccessibilityDisclosure(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.accessibility_description),
        style = MaterialTheme.typography.bodyMedium,
        color = AmberPalette.Text,
        modifier = modifier
            .fillMaxWidth()
            .background(AmberPalette.Surface, AmberShapes.Inner)
            .padding(16.dp),
    )
}

/**
 * Since Android 13, accessibility is locked for apps installed from a file. There is no API
 * to ask whether the lock applies to us, so the way out is shown whenever the service is off.
 */
@Composable
fun RestrictedSettingsHint(onOpenAppInfo: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(AmberPalette.Surface, AmberShapes.Inner)
            .clickable { open = !open }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Greyed out or \"Restricted setting\"?",
            style = MaterialTheme.typography.bodyLarge,
            color = AmberPalette.Text,
        )
        AnimatedVisibility(visible = open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint(
                    "Android locks accessibility for apps that do not come from a store. " +
                        "This lifts the lock once:",
                    AmberPalette.Text,
                )
                Hint("1. Tap \"Open app info\" below.")
                Hint("2. Top right ⋮ → \"Allow restricted settings\", then confirm.")
                Hint("3. Go back and turn on the accessibility service.")
                Hint("The menu item only appears after you have tried to turn it on once.")
                Spacer(Modifier.height(2.dp))
                GhostButton("Open app info", onOpenAppInfo)
            }
        }
        if (!open) Hint("Tap for the fix.")
    }
}
