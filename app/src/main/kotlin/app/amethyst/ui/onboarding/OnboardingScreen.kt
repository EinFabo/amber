package app.amethyst.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.amethyst.core.engine.EngineOption
import app.amethyst.core.engine.ModelDownload
import app.amethyst.core.engine.PhoneFacts
import app.amethyst.core.engine.whisper.ModelSlots.SlotState
import app.amethyst.core.onboarding.Onboarding
import app.amethyst.core.onboarding.OnboardingStep
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.engine.ParakeetState
import app.amethyst.ui.AppSource
import app.amethyst.ui.BackGlyph
import app.amethyst.ui.GhostButton
import app.amethyst.ui.IconAction
import app.amethyst.ui.SetupActions
import app.amethyst.ui.SetupState
import app.amethyst.ui.theme.AmberPalette

/**
 * The introduction: one decision per screen, from what Amber does to the first dictation. A
 * line on top says where you are, with Back and - once the permissions and the engine are
 * done - Skip, which keeps the step's current choice. The steps write their choices as they
 * are made, so leaving halfway keeps what was chosen.
 */
@Composable
fun OnboardingScreen(
    step: OnboardingStep,
    settings: AmethystSettings,
    setup: SetupState,
    actions: SetupActions,
    content: OnboardingContent,
    onStep: (OnboardingStep) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Moves on as the settings will be once a step's choice is written - DataStore answers a
    // moment later, and the cleanup step decides whether the styles step comes next.
    val nextWith: (AmethystSettings) -> Unit = { after -> Onboarding.next(step, after)?.let(onStep) ?: onFinish() }
    val next: () -> Unit = { nextWith(settings) }
    Column(modifier.fillMaxSize()) {
        StepLine(
            step = step,
            settings = settings,
            onBack = Onboarding.previous(step, settings)?.let { previous -> { onStep(previous) } },
            onSkip = next.takeIf { Onboarding.skippable(step) },
        )
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
            label = "step",
            modifier = Modifier.weight(1f),
        ) { current ->
            when (current) {
                OnboardingStep.WELCOME -> WelcomeStep(next)
                OnboardingStep.ACCESSIBILITY -> AccessibilityStep(setup, actions, next)
                OnboardingStep.MICROPHONE -> MicrophoneStep(setup, actions, next)
                OnboardingStep.ENGINE -> EngineStep(settings, content, next)
                OnboardingStep.CLEANUP -> CleanupStep(settings, content, nextWith)
                OnboardingStep.APP_STYLES -> AppStylesStep(settings, content, next)
                OnboardingStep.BUBBLE -> BubbleStep(settings, content, next)
                OnboardingStep.RECORDING -> RecordingStep(settings, content, next)
                OnboardingStep.RELIABILITY -> ReliabilityStep(setup, actions, next)
                OnboardingStep.DONE -> DoneStep(settings, content.download, onFinish)
            }
        }
    }
}

/** What the steps after the permissions need from the app; gathered by MainActivity. */
class OnboardingContent(
    /** Null for the moment it takes to read them off the main thread. */
    val phone: PhoneFacts?,
    /** Whether a download now would go over mobile data (or there is no network at all). */
    val metered: Boolean,
    val parakeet: ParakeetState,
    val whisper: Map<String, SlotState>,
    /** The download held for Wi-Fi, if any. */
    val waitingFor: EngineOption?,
    /** Adds the engine if needed, makes it active and starts its download. */
    val onChooseEngine: (option: EngineOption, mobileDataAllowed: Boolean) -> Unit,
    /** The same, holding the download until Wi-Fi. */
    val onWaitForWifi: (EngineOption) -> Unit,
    val onOpenRecognition: () -> Unit,
    /** The active engine's download while it runs, for the last step. */
    val download: ModelDownload?,
    val apps: AppSource,
    val hasKeyFor: (alias: String?) -> Boolean,
    val onSaveKey: (alias: String, key: String) -> Unit,
    val onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
)

/** "Step 4 of 9" between Back and Skip. */
@Composable
private fun StepLine(step: OnboardingStep, settings: AmethystSettings, onBack: (() -> Unit)?, onSkip: (() -> Unit)?) {
    val (position, total) = Onboarding.position(step, settings)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            // As tall with Skip as without, so the line does not jump between steps.
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) IconAction(onClick = onBack, contentDescription = "Back", glyph = BackGlyph) else Spacer(Modifier.size(44.dp))
        Text(
            "Step $position of $total",
            style = MaterialTheme.typography.labelLarge,
            color = AmberPalette.Muted,
            modifier = Modifier.weight(1f),
        )
        if (onSkip != null) GhostButton("Skip", onSkip, color = AmberPalette.Muted)
    }
}

/**
 * One step's page: a title, a line under it, the step's own content scrolling if it must, and
 * its buttons held at the bottom where the thumb is.
 */
@Composable
internal fun StepPage(
    title: String,
    lead: String?,
    buttons: @Composable ColumnScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = AmberPalette.Text)
            if (lead != null) Text(lead, style = MaterialTheme.typography.bodyLarge, color = AmberPalette.Muted)
            body()
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = buttons,
        )
    }
}
