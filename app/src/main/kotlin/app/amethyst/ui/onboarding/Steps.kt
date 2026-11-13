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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.core.onboarding.OnboardingPath
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
internal fun ContinueButton(onClick: () -> Unit, label: String = stringResource(R.string.continue_), enabled: Boolean = true) {
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

/**
 * What Amber is, and the three ways on: the quick setup as the filled button, every choice
 * step by step, or none of it now - Home then shows what is still missing.
 */
@Composable
internal fun WelcomeStep(onPath: (OnboardingPath) -> Unit, onSkipAll: () -> Unit) {
    StepPage(
        title = stringResource(R.string.welcome_title),
        lead = stringResource(R.string.welcome_lead),
        buttons = {
            Hint(stringResource(R.string.welcome_ways), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            ContinueButton({ onPath(OnboardingPath.QUICK) }, stringResource(R.string.welcome_quick))
            GhostButton(stringResource(R.string.welcome_deep), { onPath(OnboardingPath.DEEP) })
            LaterButton(stringResource(R.string.welcome_skip), onSkipAll)
        },
    ) {
        FlameMark(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp))
        Point(stringResource(R.string.welcome_every_app), stringResource(R.string.welcome_every_app_note))
        Point(stringResource(R.string.welcome_private), stringResource(R.string.welcome_private_note))
        Point(stringResource(R.string.welcome_only_dictating), stringResource(R.string.welcome_only_dictating_note))
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
    // The beat before it moves on: Continue waits too, so a tap in it cannot move on twice and
    // skip the microphone step.
    var beat by remember { mutableStateOf(false) }
    val moveOn by rememberUpdatedState(onNext)
    LaunchedEffect(on) {
        if (on && watching) {
            watching = false
            beat = true
            delay(DONE_BEAT_MS)
            moveOn()
        }
    }
    StepPage(
        title = stringResource(R.string.access_title),
        lead = stringResource(
            when {
                locked -> R.string.access_lead_locked
                waiting -> R.string.access_lead_waiting
                else -> R.string.access_lead
            },
        ),
        buttons = {
            when {
                on -> ContinueButton(onNext, enabled = !beat)
                locked -> {
                    // The filled button is the lock's "Open app info" above; this comes after it.
                    GhostButton(stringResource(R.string.access_open_again), actions.openAccessibility)
                    LaterButton(stringResource(R.string.not_now), onNext)
                }
                waiting -> {
                    ContinueButton(actions.openAccessibility, stringResource(R.string.access_open_again))
                    LaterButton(stringResource(R.string.not_now), onNext)
                }
                else -> {
                    ContinueButton(actions.openAccessibility, stringResource(R.string.agree_and_open))
                    LaterButton(stringResource(R.string.not_now), onNext)
                }
            }
        },
    ) {
        when {
            on -> Done(stringResource(R.string.setup_accessibility), stringResource(R.string.access_done))
            waiting -> {
                if (locked) RestrictedSettingsSteps(actions.openAppInfo) else SwitchOnSteps()
                FullDisclosure(stringResource(R.string.disclosure_title))
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
        title = stringResource(R.string.mic_title),
        lead = stringResource(R.string.mic_lead),
        buttons = {
            if (granted) {
                ContinueButton(onNext)
            } else {
                ContinueButton({ asked = true; actions.requestMicrophone() }, stringResource(R.string.allow))
                if (asked) LaterButton(stringResource(R.string.continue_without), onNext)
            }
        },
    ) {
        if (granted) {
            Done(stringResource(R.string.setup_microphone), stringResource(R.string.mic_allowed))
        } else if (asked) {
            Hint(stringResource(R.string.mic_not_allowed), AmberPalette.Primary)
            GhostButton(stringResource(R.string.open_app_settings), actions.openAppInfo)
        }
    }
}

/** A step that is already done: the ticked row. */
@Composable
private fun Done(label: String, description: String) {
    StatusRow(label = label, description = description, done = true, actionLabel = null, onAction = {})
}
