package app.amethyst.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import app.amethyst.ui.ExpandChevron
import kotlinx.coroutines.launch
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.core.bubble.BubbleEvent
import app.amethyst.core.engine.EngineOption
import app.amethyst.core.engine.ModelDownload
import app.amethyst.core.engine.PhoneFacts
import app.amethyst.core.engine.whisper.ModelSlots.SlotState
import app.amethyst.core.onboarding.Onboarding
import app.amethyst.core.onboarding.OnboardingPath
import app.amethyst.core.onboarding.OnboardingStep
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.engine.ParakeetState
import app.amethyst.ui.AppSource
import app.amethyst.ui.BackGlyph
import app.amethyst.ui.GhostButton
import app.amethyst.ui.ICON_ACTION_SIZE
import app.amethyst.ui.IconAction
import app.amethyst.ui.PendingModel
import app.amethyst.ui.SetupActions
import app.amethyst.ui.SetupState
import app.amethyst.ui.Wordmark
import app.amethyst.ui.screen.CleanupAccess
import app.amethyst.ui.theme.AmberPalette
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The introduction: one decision per screen, from what Amber does to the first dictation. The
 * welcome step chooses the way ([OnboardingPath]): quick - the two permissions and a try, the
 * rest decided for the phone - or step by step, or not at all ([onSkipAll]). A line on top says
 * where you are, with Back and - once the permissions and the engine are done - Skip, which
 * keeps the step's current choice. The steps write their choices as they are made, so leaving
 * halfway keeps what was chosen.
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
    path: OnboardingPath = OnboardingPath.DEEP,
    /** The way chosen on the welcome step; the caller decides what it sets up and moves on. */
    onPath: (OnboardingPath) -> Unit = {},
    /** "Skip the introduction": straight into the app. */
    onSkipAll: () -> Unit = onFinish,
) {
    // Moves on as the settings will be once a step's choice is written - DataStore answers a
    // moment later, and the cleanup step decides whether the styles step comes next. Each step
    // moves on from itself: a step still fading out that calls it late only repeats the move
    // already made, never makes a second one past the step that came in.
    val nextFrom: (OnboardingStep, AmethystSettings) -> Unit = { from, after -> Onboarding.next(from, after, path)?.let(onStep) ?: onFinish() }
    val next: () -> Unit = { nextFrom(step, settings) }
    Column(modifier.fillMaxSize()) {
        StepLine(
            wordmark = step == OnboardingStep.WELCOME,
            position = Onboarding.position(step, settings, path),
            onBack = Onboarding.previous(step, settings, path)?.let { previous -> { onStep(previous) } },
            onSkip = next.takeIf { Onboarding.skippable(step) },
        )
        val shift = with(LocalDensity.current) { STEP_SHIFT.roundToPx() }
        AnimatedContent(
            targetState = step,
            // The next step comes a short way from the right, the one before from the left -
            // each settling into place while the step it replaces is gone at once.
            transitionSpec = {
                val from = if (Onboarding.forward(initialState, targetState)) 1 else -1
                val arrive = tween<Float>(STEP_IN_MS, easing = LinearOutSlowInEasing)
                val settle = tween<IntOffset>(STEP_IN_MS, easing = LinearOutSlowInEasing)
                (fadeIn(arrive) + slideInHorizontally(settle) { from * shift }) togetherWith
                    (fadeOut(tween(100)) + slideOutHorizontally(tween(100)) { -from * shift / 3 })
            },
            label = "step",
            modifier = Modifier.weight(1f),
        ) { current ->
            val onward: () -> Unit = { nextFrom(current, settings) }
            when (current) {
                OnboardingStep.WELCOME -> WelcomeStep(content.phone, onPath, onSkipAll)
                OnboardingStep.ACCESSIBILITY -> AccessibilityStep(setup, actions, onward)
                OnboardingStep.MICROPHONE -> MicrophoneStep(setup, actions, onward)
                OnboardingStep.ENGINE -> EngineStep(settings, content, onward)
                // Blank the moment the secret store may still be building after a cold start.
                OnboardingStep.CLEANUP -> if (content.secretsReady) CleanupStep(settings, content) { after -> nextFrom(current, after) }
                OnboardingStep.APP_STYLES -> AppStylesStep(settings, content, onward)
                OnboardingStep.BUBBLE -> BubbleStep(settings, content, onward)
                OnboardingStep.PRACTICE -> PracticeStep(settings, setup, actions, content, { onStep(OnboardingStep.ACCESSIBILITY) }, onward)
                OnboardingStep.RELIABILITY -> ReliabilityStep(setup, actions, onward)
                OnboardingStep.DONE -> DoneStep(settings, content.download, content.pending, content.nudge, content.onOpenRecognition, onFinish)
            }
        }
    }
}

/** How far a step travels as it comes in, and how long it takes to settle. */
private val STEP_SHIFT = 30.dp
private const val STEP_IN_MS = 280

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
    val onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
    /** What the real bubble does, for the practice step; see [app.amethyst.Graph.bubbleEvents]. */
    val bubbleEvents: Flow<BubbleEvent> = emptyFlow(),
    /** There is a last dictation for the quick menu's "Copy last", for the practice step. */
    val hasLastDictation: Boolean = false,
    /** What the cleanup step's service list needs: keys, settings, a server's models, a key's test. */
    val cleanup: CleanupAccess,
    /** The secret store is built, so the cleanup step may ask it which keys are stored without building it on the main thread. */
    val secretsReady: Boolean = true,
    /** The recognition chosen for this phone that still waits for its model, for the last step. */
    val pending: PendingModel? = null,
    /** Where to try the bubble for real, for the last step; null once a dictation went into another app. */
    val nudge: app.amethyst.core.onboarding.RealAppNudge? = null,
)

/**
 * "Step 4 of 10" between Back and Skip - no number on the welcome step, where the way is chosen:
 * the app's name stands there instead ([wordmark]), in the room the line takes anyway, so a
 * small phone loses nothing to it.
 */
@Composable
private fun StepLine(position: Pair<Int, Int>?, onBack: (() -> Unit)?, onSkip: (() -> Unit)?, wordmark: Boolean = false) {
    if (wordmark) {
        Row(
            Modifier
                .fillMaxWidth()
                // In line with the page's text below (20 dp), as tall as the line on the other steps.
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Wordmark(style = MaterialTheme.typography.titleLarge)
        }
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            // As tall with Skip as without, so the line does not jump between steps.
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) IconAction(onClick = onBack, contentDescription = stringResource(R.string.back), glyph = BackGlyph) else Spacer(Modifier.size(ICON_ACTION_SIZE))
        Text(
            position?.let { (at, total) -> stringResource(R.string.step_of, at, total) }.orEmpty(),
            style = MaterialTheme.typography.labelLarge,
            color = AmberPalette.Muted,
            modifier = Modifier.weight(1f),
        )
        if (onSkip != null) GhostButton(stringResource(R.string.skip), onSkip, color = AmberPalette.Muted)
    }
}

/**
 * One step's page: a title - with [top] above it, for the one step that has a picture there -
 * a line under it, the step's own content scrolling if it must, and its buttons held at the
 * bottom where the thumb is. Content that goes on below the fold fades out above a button that
 * shows more, so nothing is cut off without a sign (a small phone cut the welcome step's last
 * point). The button has a strip of its own under the content while the content is taller
 * than the page - over it, it covered the practice step's hint (Pixel 9a, 2026-11-22). A
 * [stage] - a picture between the line and the content - gives up height first: it shrinks
 * towards [stageHeights]' start before the page has to scroll. [inset] keeps the content
 * clear of something on the page's edge: the practice step's bubble.
 */
@Composable
internal fun StepPage(
    title: String,
    lead: String?,
    top: (@Composable ColumnScope.() -> Unit)? = null,
    stage: (@Composable (height: Dp) -> Unit)? = null,
    stageHeights: ClosedRange<Dp> = 0.dp..0.dp,
    inset: PaddingValues = PaddingValues(0.dp),
    buttons: @Composable ColumnScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // The visible height, the content's own and the stage's as last laid out: the rest of the
    // page does not depend on the stage, so the stage's height follows from them in one step.
    var viewport by remember { mutableIntStateOf(0) }
    var content by remember { mutableIntStateOf(0) }
    var stageLaidOut by remember { mutableIntStateOf(-1) }
    // The height between the line at the top and the buttons, strip included. Content counts
    // as taller only by more than a rounding: a stage that fills the page lands on its height
    // to the pixel, and the strip must not come and go with every layout.
    var area by remember { mutableIntStateOf(0) }
    val overflows = area > 0 && content - area > with(density) { OVERFLOW_SLACK.roundToPx() }
    val stageHeight = with(density) {
        if (stage == null || viewport == 0 || stageLaidOut < 0) {
            stageHeights.endInclusive
        } else {
            (viewport - (content - stageLaidOut)).toDp().coerceIn(stageHeights.start, stageHeights.endInclusive)
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { area = it.height }) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = if (overflows) MORE_STRIP else 0.dp)
                    .onSizeChanged { viewport = it.height }
                    .verticalScroll(scroll)
                    // The content's own height, not stretched to the screen.
                    .wrapContentHeight(Alignment.Top)
                    .onSizeChanged { content = it.height }
                    .padding(top = 8.dp, bottom = CONTENT_BOTTOM)
                    .padding(inset),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                top?.invoke(this)
                Text(title, style = MaterialTheme.typography.headlineMedium, color = AmberPalette.Text)
                if (lead != null) Text(lead, style = MaterialTheme.typography.bodyLarge, color = AmberPalette.Muted)
                if (stage != null) Box(Modifier.onSizeChanged { stageLaidOut = it.height }) { stage(stageHeight) }
                body()
            }
            MoreBelow(
                // Only once a layout said the content goes on below: before the first one the
                // scroll range is Int.MAX_VALUE, and the cue would flash on a step that fits.
                // Not for the content's own space at its end.
                shown = viewport > 0 && content > 0 && scroll.maxValue != Int.MAX_VALUE &&
                    scroll.maxValue - scroll.value > with(density) { CONTENT_BOTTOM.roundToPx() },
                scroll = scroll,
                viewport = viewport,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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

/**
 * The sign that a step goes on below: the content fades into the page, and under it, in a
 * strip of its own, a small button scrolls on by most of a screen. Gone once the end is in view.
 */
@Composable
private fun MoreBelow(shown: Boolean, scroll: ScrollState, viewport: Int, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.step_more)
    AnimatedVisibility(shown, modifier, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(MORE_FADE)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, AmberPalette.Background))),
            )
            // A finger's size to hit, the round button inside it as small as before.
            Box(
                Modifier
                    .size(MORE_STRIP)
                    .clip(CircleShape)
                    .clickable(onClickLabel = label) { scope.launch { scroll.animateScrollBy(viewport * 0.7f) } }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .background(AmberPalette.Elevated, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    ExpandChevron(expanded = false, color = AmberPalette.Text)
                }
            }
        }
    }
}

/** The space under a step's content, before its buttons. */
private val CONTENT_BOTTOM = 16.dp

/** How tall the fade over content that goes on below is. */
private val MORE_FADE = 32.dp

/** The strip under content taller than the page, for the button that shows more: a finger's size. */
private val MORE_STRIP = 48.dp

/** More than a layout's rounding: content taller than the page by this much needs the strip. */
private val OVERFLOW_SLACK = 2.dp
