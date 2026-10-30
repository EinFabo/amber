package app.amethyst.ui.onboarding

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.amethyst.ui.AccessibilityDisclosure
import app.amethyst.ui.FlameMark
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.PrimaryButton
import app.amethyst.ui.RestrictedSettingsHint
import app.amethyst.ui.SetupActions
import app.amethyst.ui.SetupState
import app.amethyst.ui.StatusRow
import app.amethyst.ui.theme.AmberPalette

/** The step's main button, across the width. */
@Composable
internal fun ContinueButton(onClick: () -> Unit, label: String = "Continue", enabled: Boolean = true) {
    PrimaryButton(label, onClick, Modifier.fillMaxWidth(), enabled = enabled)
}

/** A quieter way on: "Not now", "Continue without". */
@Composable
internal fun LaterButton(label: String, onClick: () -> Unit) {
    GhostButton(label, onClick, color = AmberPalette.Muted)
}

/** A short point: a few words, a line under them. */
@Composable
private fun Point(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = AmberPalette.Text)
        Hint(text)
    }
}

@Composable
internal fun WelcomeStep(onNext: () -> Unit) {
    StepPage(
        title = "Speak instead of typing",
        lead = "Amber turns your voice into text in any app: tap the flame, speak, and the text appears where your cursor is.",
        buttons = { ContinueButton(onNext, "Set up") },
    ) {
        FlameMark(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp))
        Point("In every app", "A small flame floats over the app you are in.")
        Point("Private by default", "Your voice stays on this phone unless you pick a server.")
        Point("Only while you dictate", "The microphone is off the rest of the time.")
    }
}

/**
 * The service that shows the bubble and types the text - with everything it reads said on the
 * page before Android asks, and an explicit "I agree" (security review M6).
 */
@Composable
internal fun AccessibilityStep(setup: SetupState, actions: SetupActions, onNext: () -> Unit) {
    val on = setup.accessibilityEnabled
    StepPage(
        title = "Let Amber type for you",
        lead = "Android's accessibility service lets Amber show the flame over other apps and type into the field you are in.",
        buttons = {
            if (on) {
                ContinueButton(onNext)
            } else {
                ContinueButton(actions.openAccessibility, "I agree - open settings")
                LaterButton("Not now", onNext)
            }
        },
    ) {
        if (on) {
            Done("Accessibility service", "On - the flame can appear in every app.")
        } else {
            AccessibilityDisclosure()
            Hint("In Android's list, pick Amber and switch it on, then come back here.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RestrictedSettingsHint(onOpenAppInfo = actions.openAppInfo)
        }
    }
}

@Composable
internal fun MicrophoneStep(setup: SetupState, actions: SetupActions, onNext: () -> Unit) {
    val granted = setup.microphoneGranted
    // Android stops asking after a refusal or two; then only the app settings can grant it.
    var asked by rememberSaveable { mutableStateOf(false) }
    StepPage(
        title = "Allow the microphone",
        lead = "Amber records only while you dictate. The flame glows while it listens.",
        buttons = {
            if (granted) {
                ContinueButton(onNext)
            } else {
                ContinueButton({ asked = true; actions.requestMicrophone() }, "Allow")
                if (asked) LaterButton("Continue without", onNext)
            }
        },
    ) {
        if (granted) {
            Done("Microphone", "Allowed.")
        } else if (asked) {
            Hint(
                "Not allowed yet. If Android no longer asks, allow it in Amber's app info under Permissions → Microphone.",
                AmberPalette.Primary,
            )
            GhostButton("Open app settings", actions.openAppInfo)
        }
    }
}

/** A step that is already done: the ticked row. */
@Composable
private fun Done(label: String, description: String) {
    StatusRow(label = label, description = description, done = true, actionLabel = null, onAction = {})
}
