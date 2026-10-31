package app.amethyst.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.amethyst.ui.AGREE_AND_OPEN
import app.amethyst.ui.AccessibilityDisclosure
import app.amethyst.ui.FlameMark
import app.amethyst.ui.FullDisclosure
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.PrimaryButton
import app.amethyst.ui.RestrictedSettingsNote
import app.amethyst.ui.RestrictedSettingsSteps
import app.amethyst.ui.SetupActions
import app.amethyst.ui.SetupState
import app.amethyst.ui.StatusRow
import app.amethyst.ui.SwitchOnNote
import app.amethyst.ui.SwitchOnSteps
import app.amethyst.ui.theme.AmberPalette
import kotlinx.coroutines.delay

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
 * The service that shows the bubble and types the text - with everything it reads and does
 * said on the page before Android asks, and an explicit "I agree" (security review M6). The
 * button goes as close to Amber's switch as Android allows, and once the service is on Amber
 * comes back here by itself and moves on. Someone who comes back without it on gets the steps
 * again - and on an install Android may lock, the way past the lock.
 */
@Composable
internal fun AccessibilityStep(setup: SetupState, actions: SetupActions, onNext: () -> Unit) {
    val on = setup.accessibilityEnabled
    val waiting = !on && setup.accessibilityTried
    val locked = waiting && setup.restrictedSettings
    // Moves on by itself only when the switch was turned on while this step was up - not when
    // Back leads here with it already on.
    var watching by rememberSaveable { mutableStateOf(!on) }
    val moveOn by rememberUpdatedState(onNext)
    LaunchedEffect(on) {
        if (on && watching) {
            watching = false
            delay(DONE_BEAT_MS)
            moveOn()
        }
    }
    StepPage(
        title = "Let Amber type for you",
        lead = when {
            locked -> "Not on yet. If Android blocked the switch, this is the way past it."
            waiting -> "Not on yet. It takes three taps."
            else -> "Android's accessibility service lets Amber show the flame over other apps and type into the field you are in."
        },
        buttons = {
            when {
                on -> ContinueButton(onNext)
                locked -> {
                    // The filled button is the lock's "Open app info" above; this comes after it.
                    GhostButton("Open settings again", actions.openAccessibility)
                    LaterButton("Not now", onNext)
                }
                waiting -> {
                    ContinueButton(actions.openAccessibility, "Open settings again")
                    LaterButton("Not now", onNext)
                }
                else -> {
                    ContinueButton(actions.openAccessibility, AGREE_AND_OPEN)
                    LaterButton("Not now", onNext)
                }
            }
        },
    ) {
        when {
            on -> Done("Accessibility service", "On - the flame can appear in every app.")
            waiting -> {
                if (locked) RestrictedSettingsSteps(actions.openAppInfo) else SwitchOnSteps()
                FullDisclosure("What Amber reads and does")
            }
            else -> Column {
                if (setup.restrictedSettings) RestrictedSettingsNote()
                AccessibilityDisclosure()
                SwitchOnNote()
            }
        }
    }
}

/** How long the ticked row shows before the step moves on by itself. */
private const val DONE_BEAT_MS = 900L

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
