package app.amethyst.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.amethyst.R
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes

/*
 * The setup pieces Home's setup card and the introduction share: what the accessibility
 * service reads and does, what to do in Android's settings, and the way past Android's lock
 * on it.
 */

/** The consent button's words, the same wherever the service is asked for. */
const val AGREE_AND_OPEN = "I agree - open settings"

/**
 * What the accessibility service reads and does, said row by row before Android asks, with
 * the full text one tap away (security review M6).
 */
@Composable
fun AccessibilityDisclosure(modifier: Modifier = Modifier) {
    Column(modifier) {
        SettingsGroup("What Amber reads and does") { DisclosureRows() }
        NothingStoredLine()
        FullDisclosure()
    }
}

/**
 * One row per thing [R.string.accessibility_description] names, in its words; the line under
 * them is [NothingStoredLine]. Keep the two in step: what the description adds, a row says.
 */
@Composable
private fun DisclosureRows() {
    Fact(AmberIcons.Apps, "Which app is in front", "To show or hide the bubble - and, with voice commands on, to see that an app it opened came up.")
    RowDivider()
    Fact(AmberIcons.TextFields, "Whether a text field has focus", "To show the bubble when you can type.")
    RowDivider()
    Fact(AmberIcons.Keyboard, "Where the keyboard is", "To keep the bubble above it.")
    RowDivider()
    Fact(
        AmberIcons.Subject,
        "The text in the field",
        "Only while inserting a dictation, so the new words fit in at the cursor - and, with voice commands on, for a command that edits that field.",
    )
    RowDivider()
    Fact(
        AmberIcons.Replay,
        "Brings you back to Amber",
        "Right after you switch on what Amber sent you to Android's settings for: this service, battery use without limits.",
    )
    RowDivider()
    Fact(
        AmberIcons.VoiceCommand,
        "With voice commands on",
        "It reads your list of apps, which parts of the screen can scroll and where the items sit in the one it scrolls - to tell " +
            "whether it moved, never what they show - the volume and whether the flashlight is on.",
    )
    RowDivider()
    Fact(
        AmberIcons.Tune,
        "Acts on the command you spoke",
        "Only that one: back, home, recent apps, quick settings, notifications, lock, screenshot, split screen, power " +
            "menu; open an app or a settings page; scroll, page, swipe; media keys and volume; the flashlight; edit the " +
            "field you started the command in, never a password field. It never taps buttons or confirms dialogs; a " +
            "swipe does what your own swipe would do in that app.",
    )
}

@Composable
private fun Fact(icon: ImageVector, title: String, detail: String) {
    NavRow(title, detail, icon = icon, summaryMaxLines = Int.MAX_VALUE, trailing = null, onClick = null)
}

@Composable
private fun NothingStoredLine() {
    GroupFootnote(
        "Nothing is stored or sent - only your recording, and with cleanup its text, goes to the engine you chose.",
        AmberIcons.Lock,
    )
}

/** The description Android shows too, word for word. */
@Composable
fun FullDisclosure(label: String = "Full text") {
    LearnMore(stringResource(R.string.accessibility_description), label = label)
}

/** What to do once Android's settings are open, said before they open. */
@Composable
fun SwitchOnNote() {
    val name = stringResource(R.string.accessibility_label)
    GroupFootnote(
        "In Android's settings, tap $name, switch it on and allow it. Amber then comes back here by itself.",
        AmberIcons.OpenInNew,
    )
}

/**
 * For an install Android may lock (see SetupState.restrictedSettings), said before the
 * settings open - on amber, so it is read before the button rather than found after it.
 */
@Composable
fun RestrictedSettingsNote(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(AmberShapes.Card)
            .background(AmberPalette.Deep)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(AmberIcons.Info, contentDescription = null, tint = AmberPalette.Primary, modifier = Modifier.size(20.dp))
        Text(
            "If Android blocks the switch, come back here for the way past it - it may do that for an app it did not get from a store it knows.",
            style = MaterialTheme.typography.bodyMedium,
            color = AmberPalette.Text,
        )
    }
}

/** The same three taps as numbered steps, for someone who came back without the service on. */
@Composable
fun SwitchOnSteps() {
    val name = stringResource(R.string.accessibility_label)
    SettingsGroup("In Android's settings") {
        Step(1, "Tap $name", "In the list, if Android shows one.")
        RowDivider()
        Step(2, "Switch on \"Use $name\"", null)
        RowDivider()
        Step(3, "Tap \"Allow\"", "Amber comes back here by itself.")
    }
}

/**
 * Since Android 13, accessibility is locked for apps installed from a file until the user
 * allows it in the app info. The way past it, step by step, with the button for the first.
 */
@Composable
fun RestrictedSettingsSteps(onOpenAppInfo: () -> Unit, modifier: Modifier = Modifier) {
    SettingsGroup(
        "If Android said \"Restricted setting\"",
        modifier,
        note = "Android locks it for apps installed from a file. To lift the lock, once:",
    ) {
        Step(1, "Open Amber's app info", "With the button below.")
        RowDivider()
        Step(2, "Tap ⋮ at the top right", "It has the next item only after you tried the switch.")
        RowDivider()
        Step(3, "Tap \"Allow restricted settings\"", "And confirm if Android asks.")
        RowDivider()
        Step(4, "Then open the settings again", "From here - now the switch turns on.")
        PrimaryButton(
            "Open app info",
            onOpenAppInfo,
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        )
    }
}

@Composable
private fun Step(number: Int, title: String, detail: String?) {
    NavRow(title, detail, leading = { NumberTile(number) }, summaryMaxLines = Int.MAX_VALUE, trailing = null, onClick = null)
}

/**
 * Home's way to the service: the same rows as the introduction, then the consent. Wider than
 * a stock dialog so the rows keep their room; the rows scroll, the buttons stay.
 */
@Composable
fun AccessibilityConsentDialog(restricted: Boolean, onAgree: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = PageGutter, vertical = 24.dp)
                .fillMaxWidth()
                .clip(AmberShapes.Card)
                .background(AmberPalette.Surface),
        ) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 20.dp),
            ) {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("What Amber reads and does", style = MaterialTheme.typography.titleLarge, color = AmberPalette.Text)
                    Hint("The accessibility service shows the bubble and types your dictation into the field you are in. Here is what it reads and does:")
                }
                DisclosureRows()
                NothingStoredLine()
                FullDisclosure()
                SwitchOnNote()
                // About what may happen in Android's settings: next to the note on them, read before the button.
                if (restricted) RestrictedSettingsNote(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GhostButton("Not now", onDismiss, color = AmberPalette.Muted)
                GhostButton(AGREE_AND_OPEN, onAgree)
            }
        }
    }
}
