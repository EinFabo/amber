package app.amethyst.ui.screen

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.amethyst.core.commands.VoiceCommands
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.ui.Hint
import app.amethyst.ui.SectionCard
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes

/**
 * Voice commands from the bubble: off unless switched on here, and switching on asks first -
 * it is the one feature that acts for the user, which the accessibility service otherwise
 * promises never to do.
 */
@Composable
fun VoiceCommandsScreen(
    settings: AmethystSettings,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var asking by remember { mutableStateOf(false) }
    fun switch(on: Boolean) = onUpdateSettings { it.copy(commands = it.commands.copy(enabled = on)) }

    SubPage(modifier) {
        SectionCard(
            title = "Voice commands",
            subtitle = "Double-tap the bubble and say what to do. Where no text field is, one tap is enough.",
        ) {
            ToggleRow(
                label = "Use voice commands",
                description = "Off: Amber never taps, swipes or acts for you.",
                checked = settings.commands.enabled,
                onChange = { on -> if (on) asking = true else switch(false) },
            )
            if (settings.commands.enabled && !settings.bubble.enabled) {
                Hint("The bubble is off - switch it on under Bubble to give commands.", AmberPalette.Primary)
            }
        }

        SectionCard(title = "What you can say", subtitle = "German and English both work.") {
            VoiceCommands.EXAMPLES.forEach { example ->
                Text(example, style = MaterialTheme.typography.bodyMedium, color = AmberPalette.Text)
            }
        }

        SectionCard(title = "Good to know") {
            Hint("Nothing is read from the screen and nothing is tapped: Amber only looks for what can scroll.")
            Hint("A command opens, goes back or scrolls. It never sends, deletes, pays or confirms anything.")
            Hint(
                "Your engine under Recognition hears the command like a dictation - a server engine " +
                    "gets the recording and may bill it.",
            )
            Hint("While voice commands are on, the bubble shows in every app, also without a text field.")
        }
    }

    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            containerColor = AmberPalette.Surface,
            shape = AmberShapes.Card,
            title = { Text("Let Amber act for you?", color = AmberPalette.Text) },
            text = {
                Hint(
                    "With voice commands on, Amber carries out what you say after a double-tap on the bubble: " +
                        "go back, go home, show recent apps, notifications or quick settings, lock the screen, " +
                        "take a screenshot, open an app, scroll or swipe. For that it reads your list of apps and " +
                        "which parts of the screen can scroll - not what they show - on this phone only. It never " +
                        "taps buttons, types, sends or deletes. You can switch it off here at any time.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    switch(true)
                }) { Text("I agree", color = AmberPalette.Primary) }
            },
            dismissButton = {
                TextButton(onClick = { asking = false }) { Text("Not now", color = AmberPalette.Muted) }
            },
        )
    }
}
