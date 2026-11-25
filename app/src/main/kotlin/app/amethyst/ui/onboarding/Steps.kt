package app.amethyst.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.amethyst.R
import app.amethyst.core.engine.EngineAdvice
import app.amethyst.core.engine.PhoneFacts
import app.amethyst.core.engine.WelcomePrivacy
import app.amethyst.core.onboarding.OnboardingPath
import app.amethyst.core.onboarding.WelcomeNote
import app.amethyst.ui.AccessibilityDisclosure
import app.amethyst.ui.FullDisclosure
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.PrimaryButton
import app.amethyst.ui.RestrictedSettingsNote
import app.amethyst.ui.SetupActions
import app.amethyst.ui.SetupState
import app.amethyst.ui.StatusRow
import app.amethyst.ui.SwitchOnNote
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
private fun Point(title: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = AmberPalette.Text)
        Hint(text)
    }
}

/**
 * What the app is - under its name in the line on top ([StepLine]), shown once on a small
 * stage ([WelcomeDemo]), then said in three points - and the three ways on: the quick setup as
 * the filled button, every choice step by step, or none of it now - Home then shows what is still missing. On a short screen
 * the stage gets lower first, so the three points still fit.
 *
 * "Your voice stays on this phone" is promised only where the quick setup's pick keeps it there
 * ([EngineAdvice.welcomePrivacy]); where it is Android's recognition without an on-device
 * recognizer, the point says that instead, and on a phone where nothing recognizes yet (no
 * recognizer of Android's, no model that fits) it says so. Until the [phone]'s facts are read - a moment, off
 * the main thread - the point keeps its room but shows nothing, so no promise flashes up.
 * A [note] says what Quick setup downloads ([FirstRun.welcomeNote]), before the tap.
 */
@Composable
internal fun WelcomeStep(phone: PhoneFacts?, onPath: (OnboardingPath) -> Unit, onSkipAll: () -> Unit, note: WelcomeNote? = null) {
    StepPage(
        title = stringResource(R.string.welcome_title),
        lead = stringResource(R.string.welcome_lead),        buttons = {
            Hint(stringResource(R.string.welcome_ways), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            when (note) {
                is WelcomeNote.DownloadsSmallModel ->
                    Hint(stringResource(R.string.welcome_downloads, stringResource(R.string.megabytes, note.sizeMb)), AmberPalette.Primary, Modifier.fillMaxWidth().padding(bottom = 8.dp))
                null -> Unit
            }
            ContinueButton({ onPath(OnboardingPath.QUICK) }, stringResource(R.string.welcome_quick))
            GhostButton(stringResource(R.string.welcome_deep), { onPath(OnboardingPath.DEEP) })
            LaterButton(stringResource(R.string.welcome_skip), onSkipAll)
        },
        stage = { height -> WelcomeDemo(height = height) },
        stageHeights = WELCOME_STAGE_MIN..STAGE_HEIGHT,
    ) {
        Point(stringResource(R.string.welcome_every_app), stringResource(R.string.welcome_every_app_note))
        val privacy = phone?.let(EngineAdvice::welcomePrivacy) ?: WelcomePrivacy.ON_PHONE
        Point(
            stringResource(if (privacy == WelcomePrivacy.ON_PHONE) R.string.welcome_private else R.string.welcome_android),
            stringResource(
                when (privacy) {
                    WelcomePrivacy.ON_PHONE -> R.string.welcome_private_note
                    WelcomePrivacy.ANDROID_MAY_SEND -> R.string.welcome_android_note
                    // The same words as the last step and the practice step: no recognition of Android's to name.
                    WelcomePrivacy.NOTHING_YET -> R.string.no_recognition_note
                },
            ),
            Modifier.alpha(if (phone == null) 0f else 1f),
        )
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
    // Switched on, yet the service never connected: not "On" - the way to switch it off and on
    // again, as on the practice step and Home, so the last step's "Go to the missing step" ends here.
    val stuck = on && setup.serviceNotRunning
    val waiting = !on && setup.accessibilityTried
    val locked = waiting && setup.restrictedSettings
    // Moves on by itself only when the switch was turned on while this step was up - not when
    // Back leads here with it already on.
    var watching by rememberSaveable { mutableStateOf(!on) }
    // The beat before it moves on: Continue waits too, so a tap in it cannot move on twice and
    // skip the microphone step.
    var beat by remember { mutableStateOf(false) }
    val moveOn by rememberUpdatedState(onNext)
    LaunchedEffect(on, stuck) {
        if (on && !stuck && watching) {
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
                stuck -> {
                    ContinueButton(actions.openAccessibilityList, stringResource(R.string.open_accessibility_settings))
                    LaterButton(stringResource(R.string.not_now), onNext)
                }
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
            stuck -> Hint(stringResource(R.string.service_not_running), AmberPalette.Primary)
            // "Just now" from the first frame it is on, before the beat below has begun.
            on -> ServiceOn(justNow = watching || beat)
            waiting -> {
                AfreshOnReturn { WayToTheSwitch(locked, actions) }
                FullDisclosure(stringResource(R.string.disclosure_title))
            }
            else -> BeforeTheSettings(setup)
        }
    }
}

/** How long the ticked row shows before the step moves on by itself. */
private const val DONE_BEAT_MS = 900L

/** The service is on: the bubble's tick - drawn and with a burst if it came on [justNow] - over the ticked row. */
@Composable
private fun ServiceOn(justNow: Boolean) {
    ServiceOnMark(justNow, DONE_BEAT_MS.toInt())
    Done(stringResource(R.string.setup_accessibility), stringResource(R.string.access_done))
}

/**
 * Back from Android's settings without the service on: the taps to make there - or, on an
 * install Android may lock ([SetupState.restrictedSettings]), the way past the lock. Each as
 * a picture, with the steps in words under it.
 */
@Composable
private fun WayToTheSwitch(locked: Boolean, actions: SetupActions) {
    if (locked) RestrictedWay(actions.openAppInfo) else SwitchOnWay()
}

/**
 * Before the settings open: what the service reads and does first - it is what "I agree" below
 * agrees to, so it is on screen with that button and not under a picture - then where to tap
 * in Android's settings, and the same taps in words.
 */
@Composable
private fun BeforeTheSettings(setup: SetupState) {
    Column {
        AccessibilityDisclosure()
        SwitchOnPicture(Modifier.padding(top = 16.dp))
        if (setup.restrictedSettings) RestrictedSettingsNote()
        SwitchOnNote()
    }
}

@Composable
internal fun MicrophoneStep(setup: SetupState, actions: SetupActions, onNext: () -> Unit) {
    val granted = setup.microphoneGranted
    // Android stops asking after a refusal or two; then only the app settings can grant it.
    var asked by rememberSaveable { mutableStateOf(false) }
    // Android's question is a window over this one: while it is up the page is not resumed, and
    // "not allowed yet" would show behind a question the user has not answered.
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val refused = asked && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    StepPage(
        title = stringResource(R.string.mic_title),
        lead = stringResource(R.string.mic_lead),
        buttons = {
            if (granted) {
                ContinueButton(onNext)
            } else {
                ContinueButton({ asked = true; actions.requestMicrophone() }, stringResource(R.string.allow))
                // Android's own wording of the choice that grants it, so "Allow" is not followed by a guess.
                Hint(stringResource(R.string.mic_choose_while_using))
                if (refused) LaterButton(stringResource(R.string.continue_without_mic), onNext)
            }
        },
    ) {
        if (granted) {
            Done(stringResource(R.string.setup_microphone), stringResource(R.string.mic_allowed))
        } else if (refused) {
            Hint(stringResource(R.string.mic_not_allowed), AmberPalette.Primary)
            GhostButton(stringResource(R.string.open_app_settings), actions.openAppInfo)
        }
        // Where the recording and its text go, and what is kept of them: said here, with the
        // microphone, rather than on the accessibility step, whose disclosure Play wants to be
        // about the service alone.
        Hint(stringResource(R.string.mic_where))
    }
}

/** A step that is already done: the ticked row. */
@Composable
private fun Done(label: String, description: String) {
    StatusRow(label = label, description = description, done = true, actionLabel = null, onAction = {})
}
