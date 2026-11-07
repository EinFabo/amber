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
import app.amethyst.core.settings.Features
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes

/*
 * The setup pieces Home's setup card and the introduction share: what the accessibility
 * service reads and does, what to do in Android's settings, and the way past Android's lock
 * on it.
 */


/**
 * What the accessibility service reads and does, said row by row before Android asks, with
 * the full text one tap away (security review M6).
 */
@Composable
fun AccessibilityDisclosure(modifier: Modifier = Modifier) {
    Column(modifier) {
        SettingsGroup(stringResource(R.string.disclosure_title)) { DisclosureRows() }
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
    // Voice commands are out of this build (Features.VOICE_COMMANDS): the description says
    // nothing of them, so neither do the rows. With them back, both say it again.
    val commands = Features.VOICE_COMMANDS
    Fact(
        AmberIcons.Apps,
        stringResource(R.string.disclosure_app),
        stringResource(if (commands) R.string.disclosure_app_note_commands else R.string.disclosure_app_note),
    )
    RowDivider()
    Fact(AmberIcons.TextFields, stringResource(R.string.disclosure_field), stringResource(R.string.disclosure_field_note))
    RowDivider()
    Fact(AmberIcons.Keyboard, stringResource(R.string.disclosure_keyboard), stringResource(R.string.disclosure_keyboard_note))
    RowDivider()
    Fact(
        AmberIcons.Subject,
        stringResource(R.string.disclosure_text),
        stringResource(if (commands) R.string.disclosure_text_note_commands else R.string.disclosure_text_note),
    )
    RowDivider()
    Fact(
        AmberIcons.Replay,
        stringResource(R.string.disclosure_back),
        stringResource(R.string.disclosure_back_note),
    )
    if (commands) CommandDisclosureRows()
}

/** What the service reads and does for voice commands; shown while they are in the build. */
@Composable
private fun CommandDisclosureRows() {
    RowDivider()
    Fact(
        AmberIcons.VoiceCommand,
        stringResource(R.string.disclosure_commands),
        stringResource(R.string.disclosure_commands_note),
    )
    RowDivider()
    Fact(
        AmberIcons.Tune,
        stringResource(R.string.disclosure_acts),
        stringResource(R.string.disclosure_acts_note),
    )
}

@Composable
private fun Fact(icon: ImageVector, title: String, detail: String) {
    NavRow(title, detail, icon = icon, summaryMaxLines = Int.MAX_VALUE, trailing = null, onClick = null)
}

@Composable
private fun NothingStoredLine() {
    GroupFootnote(
        stringResource(R.string.disclosure_nothing_stored),
        AmberIcons.Lock,
    )
}

/** The description Android shows too, word for word. */
@Composable
fun FullDisclosure(label: String = stringResource(R.string.full_text)) {
    LearnMore(stringResource(R.string.accessibility_description), label = label)
}

/** What to do once Android's settings are open, said before they open. */
@Composable
fun SwitchOnNote() {
    val name = stringResource(R.string.accessibility_label)
    GroupFootnote(
        stringResource(R.string.switch_on_note, name),
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
            stringResource(R.string.restricted_note),
            style = MaterialTheme.typography.bodyMedium,
            color = AmberPalette.Text,
        )
    }
}

/** The same three taps as numbered steps, for someone who came back without the service on. */
@Composable
fun SwitchOnSteps() {
    val name = stringResource(R.string.accessibility_label)
    SettingsGroup(stringResource(R.string.switch_on_group)) {
        Step(1, stringResource(R.string.switch_on_step1, name), stringResource(R.string.switch_on_step1_note))
        RowDivider()
        Step(2, stringResource(R.string.switch_on_step2, name), null)
        RowDivider()
        Step(3, stringResource(R.string.switch_on_step3), stringResource(R.string.switch_on_step3_note))
    }
}

/**
 * Since Android 13, accessibility is locked for apps installed from a file until the user
 * allows it in the app info. The way past it, step by step, with the button for the first.
 */
@Composable
fun RestrictedSettingsSteps(onOpenAppInfo: () -> Unit, modifier: Modifier = Modifier) {
    SettingsGroup(
        stringResource(R.string.restricted_group),
        modifier,
        note = stringResource(R.string.restricted_group_note),
    ) {
        Step(1, stringResource(R.string.restricted_step1), stringResource(R.string.restricted_step1_note))
        RowDivider()
        Step(2, stringResource(R.string.restricted_step2), stringResource(R.string.restricted_step2_note))
        RowDivider()
        Step(3, stringResource(R.string.restricted_step3), stringResource(R.string.restricted_step3_note))
        RowDivider()
        Step(4, stringResource(R.string.restricted_step4), stringResource(R.string.restricted_step4_note))
        PrimaryButton(
            stringResource(R.string.open_app_info),
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
                    Text(stringResource(R.string.disclosure_title), style = MaterialTheme.typography.titleLarge, color = AmberPalette.Text)
                    Hint(stringResource(R.string.consent_intro))
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
                GhostButton(stringResource(R.string.not_now), onDismiss, color = AmberPalette.Muted)
                GhostButton(stringResource(R.string.agree_and_open), onAgree)
            }
        }
    }
}
