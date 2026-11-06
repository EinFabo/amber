package app.amethyst.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import app.amethyst.core.engine.DownloadNotice
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.ModelDownload
import app.amethyst.core.engine.whisper.ModelSlots.SlotState
import app.amethyst.core.engine.whisper.WhisperModels
import app.amethyst.core.onboarding.Practice
import app.amethyst.core.onboarding.PracticeBlock
import app.amethyst.core.onboarding.PracticeReadiness
import app.amethyst.core.onboarding.PracticeTask
import app.amethyst.core.onboarding.Recognition
import app.amethyst.core.onboarding.TaskState
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.engine.ModelDownloads
import app.amethyst.engine.ParakeetState
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.SetupActions
import app.amethyst.ui.SetupState
import app.amethyst.ui.StatusDot
import app.amethyst.ui.screen.fieldColors
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes

/**
 * Tap, hold and drag, tried once with the real flame: a field at the top takes focus, so the
 * flame comes up above the keyboard, and each task ticks when Amber sees it happen ([Practice],
 * from the bubble's events). Each can be skipped, and nothing here holds anyone up. Without a
 * flame, a microphone or a recognition the step says so in plain words instead.
 */
@Composable
internal fun PracticeStep(
    settings: AmethystSettings,
    setup: SetupState,
    actions: SetupActions,
    content: OnboardingContent,
    /** To the accessibility step, for a flame that cannot show without the service. */
    onSwitchServiceOn: () -> Unit,
    onNext: () -> Unit,
) {
    // Starts over each time the step opens, from the side the flame is on then; kept across a rotation.
    var practice by rememberSaveable(stateSaver = PracticeSaver) { mutableStateOf(Practice.start(settings.bubble.side)) }
    LaunchedEffect(content.bubbleEvents) {
        content.bubbleEvents.collect { event -> practice = practice.on(event) }
    }
    val readiness = readiness(settings, setup, content)
    val noFlame = Practice.stepBlock(readiness)

    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // The field takes focus as the step opens: the keyboard comes up, the flame above it.
    LaunchedEffect(noFlame == null) {
        if (noFlame != null) return@LaunchedEffect
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }

    // Above the keyboard, so the tasks and the buttons can still be reached while it is up.
    Box(Modifier.imePadding()) {
        StepPage(
            title = "Try it",
            lead = "The real flame, above the keyboard. Each try ticks off when Amber sees it.",
            buttons = {
                when {
                    noFlame == PracticeBlock.NO_SERVICE -> {
                        ContinueButton(onSwitchServiceOn, "Switch it on")
                        LaterButton("Not now", onNext)
                    }
                    noFlame == PracticeBlock.BUBBLE_OFF -> {
                        ContinueButton({ content.onUpdateSettings { it.copy(bubble = it.bubble.copy(enabled = true)) } }, "Show the flame")
                        LaterButton("Not now", onNext)
                    }
                    practice.finished -> ContinueButton(onNext)
                    else -> LaterButton("Not now", onNext)
                }
            },
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Your words land here …", color = AmberPalette.Muted) },
                minLines = 2,
                shape = AmberShapes.Inner,
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            when (noFlame) {
                PracticeBlock.NO_SERVICE -> Hint(
                    "No flame yet: Amber's accessibility service is off. Switch it on, or skip this for now.",
                    AmberPalette.Primary,
                )
                PracticeBlock.BUBBLE_OFF -> Hint("No flame: it is switched off in Settings → Bubble.", AmberPalette.Primary)
                else -> Unit
            }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PracticeTask.entries.forEach { task ->
                    TaskRow(
                        task = task,
                        state = practice.state(task),
                        // The flame's own absence is said once, above.
                        block = Practice.blocked(task, readiness, practice).takeIf { noFlame == null },
                        download = content.download,
                        onSkip = { practice = practice.skip(task) }.takeIf { noFlame == null && task == practice.current },
                        onAllowMicrophone = actions.requestMicrophone,
                    )
                }
            }
            if (practice.allDone) {
                Text("All three done - that is all the flame needs.", style = MaterialTheme.typography.bodyLarge, color = AmberPalette.Text)
            }
        }
    }
}

/** One try: the dot that ticks, what to do, and Skip on the one up next. */
@Composable
private fun TaskRow(
    task: PracticeTask,
    state: TaskState,
    block: PracticeBlock?,
    download: ModelDownload?,
    onSkip: (() -> Unit)?,
    onAllowMicrophone: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StatusDot(state == TaskState.DONE)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(task.title(), style = MaterialTheme.typography.bodyLarge, color = if (state == TaskState.SKIPPED) AmberPalette.Muted else AmberPalette.Text)
            when {
                state == TaskState.DONE && task == PracticeTask.DRAG -> Hint("Done. Put it back wherever you like.")
                state == TaskState.DONE -> Hint("Done.")
                state == TaskState.SKIPPED -> Hint("Skipped.")
                block != null -> Hint(block.say(download), AmberPalette.Primary)
                else -> Hint(task.how())
            }
            if (block == PracticeBlock.NO_MICROPHONE && state != TaskState.DONE) {
                GhostButton("Allow the microphone", onAllowMicrophone, Modifier.padding(top = 2.dp))
            }
        }
        if (onSkip != null) GhostButton("Skip", onSkip, color = AmberPalette.Muted)
    }
}

private fun PracticeTask.title(): String = when (this) {
    PracticeTask.TAP -> "Tap to dictate"
    PracticeTask.HOLD -> "Hold for the quick menu"
    PracticeTask.DRAG -> "Drag to the other edge"
}

// Amber's own screen greys out "Hide here" and the cleanup switch: the hold points at Copy last.
private fun PracticeTask.how(): String = when (this) {
    PracticeTask.TAP -> "Tap the flame, say a few words, tap it again. The text lands in the field above."
    PracticeTask.HOLD -> "Hold the flame, slide to \"Copy last\" and let go."
    PracticeTask.DRAG -> "Drag the flame across to the other side and let go - or throw it there."
}

private fun PracticeBlock.say(download: ModelDownload?): String = when (this) {
    PracticeBlock.NO_MICROPHONE -> "Amber may not use the microphone yet."
    PracticeBlock.DOWNLOADING -> listOfNotNull(download?.let(DownloadNotice::short), "Try this once the download is done.").joinToString(". ")
    PracticeBlock.NO_RECOGNITION -> "No speech recognition is ready yet. Try this once it is."
    PracticeBlock.NEEDS_DICTATION -> "Needs a dictation first - try it after the tap."
    PracticeBlock.NO_SERVICE, PracticeBlock.BUBBLE_OFF -> "No flame yet."
}

/** What the phone allows right now: the service, the flame's switch, the microphone, a recognition. */
private fun readiness(settings: AmethystSettings, setup: SetupState, content: OnboardingContent): PracticeReadiness {
    val engine = settings.activeEngine
    val modelReady = when (engine?.kind) {
        EngineKind.LOCAL_SHERPA -> content.parakeet is ParakeetState.Ready
        EngineKind.WHISPER_CPP -> content.whisper[engine.modelId ?: WhisperModels.default.id] is SlotState.Ready
        else -> true
    }
    return PracticeReadiness(
        serviceOn = setup.accessibilityEnabled,
        bubbleOn = settings.bubble.enabled,
        microphone = setup.microphoneGranted,
        recognition = Recognition.of(
            engineChosen = engine != null,
            downloading = content.download != null,
            needsModel = engine?.let(ModelDownloads::downloadsModel) == true,
            modelReady = modelReady,
        ),
    )
}

private val PracticeSaver = Saver<Practice, ArrayList<String>>(
    save = { ArrayList(it.save()) },
    restore = { Practice.restore(it) },
)

