package app.amethyst.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.amethyst.core.dictation.DictationRecord
import app.amethyst.ui.DangerAction
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.SectionCard
import app.amethyst.ui.TrustBadge
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes

/**
 * The first thing to try after installing: dictate into a field inside Amber itself.
 *
 * This exists so a failure is attributable. The bubble path involves the microphone, the
 * engine, the normalizer, the accessibility service, the insertion chain and a foreign app's
 * text field - six things that can break. Here the app is in the foreground, where microphone
 * access is never refused, and the target is a plain Compose field. If this works and
 * dictating into WhatsApp does not, the problem is the insertion path, not the engine.
 */
@Composable
fun TestFieldCard(focusRequester: FocusRequester, modifier: Modifier = Modifier) {
    var testText by rememberSaveable { mutableStateOf("") }

    SectionCard(
        title = "Test field",
        subtitle = "The test dictation lands here, just like it will in other apps.",
        modifier = modifier,
        trailing = if (testText.isNotEmpty()) {
            { GhostButton("Clear", { testText = "" }) }
        } else {
            null
        },
    ) {
        OutlinedTextField(
            value = testText,
            onValueChange = { testText = it },
            placeholder = { Text("Tap the flame and speak …", color = AmberPalette.Muted) },
            minLines = 3,
            shape = AmberShapes.Inner,
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = fieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
    }
}

/**
 * Filled fields without a frame; only the focused one gets a thin amber edge. Raised a step
 * above the group surface they mostly sit on.
 */
@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = AmberPalette.Elevated,
    unfocusedContainerColor = AmberPalette.Elevated,
    focusedBorderColor = AmberPalette.Primary,
    unfocusedBorderColor = Color.Transparent,
    cursorColor = AmberPalette.Primary,
    focusedTextColor = AmberPalette.Text,
    unfocusedTextColor = AmberPalette.Text,
    focusedLabelColor = AmberPalette.Accent,
    unfocusedLabelColor = AmberPalette.Muted,
)

/**
 * What actually happened on the last dictations, so a bug report can be precise.
 */
@Composable
fun DiagnosticsScreen(
    records: List<DictationRecord>,
    keyboardLog: List<String>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SubPage(modifier) {
        SectionCard(
            title = if (records.isEmpty()) null else "The last ${minOf(records.size, MAX_SHOWN)} dictations",
        ) {
            if (keyboardLog.isNotEmpty()) {
                // What the bubble saw of the keyboard, newest first: the evidence when it misbehaves.
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Keyboard", style = MaterialTheme.typography.bodySmall, color = AmberPalette.Text)
                    keyboardLog.forEach { Hint(it) }
                }
            }

            if (records.isEmpty()) {
                Text("Nothing dictated yet", style = MaterialTheme.typography.titleMedium, color = AmberPalette.Text)
                Hint("After the first dictation, engine, duration and insertion method show up here.")
            }

            records.take(MAX_SHOWN).forEach { record ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        record.trustLevel?.let { TrustBadge(it) }
                        Hint(
                            text = record.summary(),
                            color = if (record.failed) AmberPalette.Error else AmberPalette.Muted,
                        )
                    }
                    record.timing?.let { Hint(it) }
                    if (record.text.isNotBlank()) {
                        Text(
                            text = record.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = AmberPalette.Text,
                        )
                    }
                }
            }
        }

        if (records.isNotEmpty() || keyboardLog.isNotEmpty()) DangerAction("Clear diagnostics", onClear)
    }
}

private const val MAX_SHOWN = 5
