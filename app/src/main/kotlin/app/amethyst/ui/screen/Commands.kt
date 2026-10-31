package app.amethyst.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.amethyst.core.commands.VoiceCommands
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.ui.AmberIcons
import app.amethyst.ui.GroupFootnote
import app.amethyst.ui.Hint
import app.amethyst.ui.LearnMore
import app.amethyst.ui.MainSwitch
import app.amethyst.ui.NavRow
import app.amethyst.ui.RowDivider
import app.amethyst.ui.SettingsGroup
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes

/**
 * Said wherever voice commands are explained: a command is a short recording, and it goes
 * where every recording goes.
 */
private const val ENGINE_HEARS =
    "The engine you chose under Recognition hears each command like a dictation - a server engine gets the recording and may bill it."

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
        MainSwitch(
            label = "Use voice commands",
            summary = "Double-tap the bubble and speak.",
            checked = settings.commands.enabled,
            onChange = { on -> if (on) asking = true else switch(false) },
        )
        if (settings.commands.enabled && !settings.bubble.enabled) {
            Hint(
                "The bubble is off - switch it on under Bubble to give commands.",
                AmberPalette.Primary,
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
        GroupFootnote(ENGINE_HEARS, AmberIcons.Mic)
        LearnMore(
            "Where no text field is, one tap on the bubble is enough; in a text field, double-tap. While voice " +
                "commands are on, the bubble shows in every app, also without a text field.\n\n" +
                "Amber reads nothing the screen shows except the field a text command changes - the one you " +
                "started the command in, never a password field. To scroll, it looks only for what can scroll and, " +
                "in the part it scrolls, where the items sit, to tell whether it moved. Settings pages only open: " +
                "Amber never switches Wi-Fi or Bluetooth itself. It never taps buttons or confirms dialogs, and it " +
                "deletes only the words you name - but a swipe does what your own swipe would do in that app: in a " +
                "mail list it may archive a mail.\n\n" +
                "Add a number for more - “zehnmal runter”, “swipe 5 times left” - up to 20. " +
                "“Ganz nach unten” scrolls until nothing moves.",
        )

        SettingsGroup("What you can say", note = "German and English both work.") {
            VoiceCommands.GROUPS.forEachIndexed { i, group ->
                if (i > 0) RowDivider()
                NavRow(
                    group.title,
                    group.examples.joinToString("\n"),
                    icon = iconOf(group.title),
                    summaryMaxLines = Int.MAX_VALUE,
                    trailing = null,
                    onClick = null,
                )
            }
        }
        GroupFootnote("Text commands work in the field you start them in - double-tap the bubble there.", AmberIcons.TextFields)
    }

    if (asking) {
        VoiceCommandsConsent(
            onAgree = {
                asking = false
                switch(true)
            },
            onDismiss = { asking = false },
        )
    }
}

/**
 * What switching voice commands on means, before it is on: every kind of command, what is read
 * for them, and where the recording goes.
 */
@Composable
fun VoiceCommandsConsent(onAgree: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AmberPalette.Surface,
        shape = AmberShapes.Card,
        title = { Text("Let Amber act for you?", color = AmberPalette.Text) },
        text = {
            Column(
                Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Hint(
                    "With voice commands on, Amber carries out what you say after a double-tap on the bubble: " +
                        "go back or home, show recent apps or quick settings, show or close notifications, lock the " +
                        "screen, take a screenshot, split the screen, show the power menu; open an app or a settings " +
                        "page (Wi-Fi, Bluetooth, sound, display, battery); scroll, page or swipe; play, pause, skip, " +
                        "change or mute the volume; switch the flashlight; and in the field you started the command " +
                        "in, delete words, select all, copy, cut, paste or start a new line.",
                )
                Hint(
                    "For that it reads your list of apps and which app came to the front after it opened one, " +
                        "which parts of the screen can scroll and where the items sit in the one it scrolls - to tell " +
                        "whether it moved, never what they show - the volume and whether the flashlight is on, and, " +
                        "for a text command, the text of that field, never in a password field. On this phone only. " +
                        "It never taps buttons or confirms dialogs; a swipe does what your own swipe would do in that " +
                        "app - in a mail list it may archive a mail.",
                )
                Hint(ENGINE_HEARS, AmberPalette.Text)
                Hint("You can switch it off here at any time.")
            }
        },
        confirmButton = { TextButton(onClick = onAgree) { Text("I agree", color = AmberPalette.Primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now", color = AmberPalette.Muted) } },
    )
}

private fun iconOf(group: String): ImageVector = when (group) {
    "Navigate" -> AmberIcons.Home
    "Apps" -> AmberIcons.Apps
    "Scroll & swipe" -> AmberIcons.UnfoldMore
    "Media" -> AmberIcons.MusicNote
    "System" -> AmberIcons.Tune
    "Text" -> AmberIcons.TextFields
    else -> AmberIcons.VoiceCommand
}
