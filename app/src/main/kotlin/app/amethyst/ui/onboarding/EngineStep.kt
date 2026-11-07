package app.amethyst.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.core.engine.EngineAdvice
import app.amethyst.core.engine.EngineOption
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.FileProblem
import app.amethyst.core.engine.PhoneFacts
import app.amethyst.core.engine.whisper.ModelSlots.SlotState
import app.amethyst.core.onboarding.EngineCard
import app.amethyst.core.onboarding.EngineNames
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.EngineConfig
import app.amethyst.engine.ParakeetState
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.ProgressLine
import app.amethyst.ui.RadioRow
import app.amethyst.ui.TrustBadge
import app.amethyst.ui.screen.trustLevelForDisplay
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes
import app.amethyst.ui.theme.badgeLabel
import app.amethyst.ui.words

/** Where an option's download stands. */
private sealed interface OptionState {
    data object Missing : OptionState
    data class Running(val fraction: Float) : OptionState
    data object Waiting : OptionState
    data object Ready : OptionState
    data class Failed(val problem: FileProblem) : OptionState
}

/**
 * How Amber hears you: the engine this phone is best served by, on a card that says what it
 * costs - size and wait, never "model" - and starts its download. The download keeps going
 * while the remaining steps are done. Other options sit one tap away.
 */
@Composable
internal fun EngineStep(settings: AmethystSettings, content: OnboardingContent, onNext: () -> Unit) {
    val title = stringResource(R.string.engine_step_title)
    val lead = stringResource(R.string.engine_step_lead)
    // Read off the main thread when the introduction opens; a moment without the card at most.
    val phone = content.phone ?: return StepPage(title, lead, buttons = {}) {}
    val advice = remember(phone) { EngineAdvice.recommend(phone) }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var showOthers by rememberSaveable { mutableStateOf(false) }
    // A replay keeps the flag set; a fresh install has not finished the introduction yet.
    val card = EngineCard.of(picked?.let(EngineOption::valueOf), settings.activeEngine, advice, replay = settings.onboardingDone)
    val shown = (card as? EngineCard.Offer)?.option
    val state = shown?.let { stateOf(it, content) }
    val active = shown != null && settings.activeEngineId == shown.presetId &&
        (shown.whisperModelId == null || settings.activeEngine?.modelId == shown.whisperModelId)

    StepPage(
        title = title,
        lead = lead,
        buttons = {
            when {
                shown == null -> ContinueButton(onNext)
                shown == EngineOption.ANDROID -> ContinueButton({ content.onChooseEngine(shown, false); onNext() }, stringResource(R.string.engine_step_use_android))
                state is OptionState.Running || state is OptionState.Waiting || (state is OptionState.Ready && active) -> ContinueButton(onNext)
                state is OptionState.Ready -> ContinueButton({ content.onChooseEngine(shown, false); onNext() }, stringResource(R.string.engine_step_use_it))
                content.metered -> {
                    ContinueButton({ content.onChooseEngine(shown, true) }, stringResource(R.string.engine_step_download_mobile, megabytes(shown)))
                    LaterButton(stringResource(R.string.engine_step_wait_wifi)) { content.onWaitForWifi(shown) }
                }
                else -> ContinueButton(
                    { content.onChooseEngine(shown, false) },
                    stringResource(if (state is OptionState.Failed) R.string.try_again else R.string.download),
                )
            }
        },
    ) {
        if (card is EngineCard.Keep) {
            settings.engines.firstOrNull { it.id == card.engineId }?.let { KeptCard(it) }
        }
        if (shown != null && state != null) {
            OptionCard(shown, recommended = shown == advice.recommended, state = state, content = content)
            if (state is OptionState.Failed) {
                // A smaller download may still fit where this one failed for room or connection.
                advice.others.firstOrNull { it.downloadBytes in 1 until shown.downloadBytes }?.let { smaller ->
                    GhostButton(stringResource(R.string.engine_step_or_take, words(EngineNames.of(smaller)), megabytes(smaller)), { picked = smaller.name })
                }
            }
        }
        GhostButton(stringResource(if (showOthers) R.string.engine_step_hide_others else R.string.engine_step_others), { showOthers = !showOthers })
        if (showOthers) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf(advice.recommended) + advice.others).forEach { option ->
                    RadioRow(
                        label = words(EngineNames.of(option)),
                        note = listOfNotNull(trustOf(option, phone).badgeLabel(), sizeText(option), waitText(option)).joinToString(" · "),
                        selected = option == shown,
                        onSelect = {
                            if (option == EngineOption.SERVER) content.onOpenRecognition() else picked = option.name
                        },
                    )
                }
            }
        }
    }
}

/** The engine the user already has, kept as it is. */
@Composable
private fun KeptCard(engine: EngineConfig) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(AmberPalette.Surface, AmberShapes.Card)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.engine_step_in_use), style = MaterialTheme.typography.labelLarge, color = AmberPalette.Primary)
        Text(words(EngineNames.of(engine)), style = MaterialTheme.typography.titleLarge, color = AmberPalette.Text)
        TrustBadge(engine.trustLevelForDisplay())
        Hint(stringResource(R.string.engine_step_stays))
    }
}

@Composable
private fun OptionCard(option: EngineOption, recommended: Boolean, state: OptionState, content: OnboardingContent) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(AmberPalette.Surface, AmberShapes.Card)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (recommended) Text(stringResource(R.string.engine_step_recommended), style = MaterialTheme.typography.labelLarge, color = AmberPalette.Primary)
        Text(words(EngineNames.of(option)), style = MaterialTheme.typography.titleLarge, color = AmberPalette.Text)
        TrustBadge(trustOf(option, content.phone))
        Hint(listOfNotNull(sizeText(option), waitText(option)).joinToString(" · "), AmberPalette.Text)
        when (state) {
            OptionState.Missing -> Unit
            is OptionState.Running -> {
                ProgressLine(state.fraction)
                Hint(stringResource(R.string.engine_step_keeps_going))
            }
            OptionState.Waiting -> Hint(stringResource(R.string.engine_step_waits))
            OptionState.Ready -> Hint(stringResource(R.string.engine_step_ready), AmberPalette.TrustOnDevice)
            is OptionState.Failed -> Hint(words(state.problem), AmberPalette.Error)
        }
    }
}

private fun stateOf(option: EngineOption, content: OnboardingContent): OptionState {
    if (content.waitingFor == option) return OptionState.Waiting
    return when {
        option == EngineOption.PARAKEET -> when (val s = content.parakeet) {
            ParakeetState.Missing -> OptionState.Missing
            is ParakeetState.Busy -> OptionState.Running(if (s.total > 0) s.received.toFloat() / s.total else 0f)
            is ParakeetState.Ready -> OptionState.Ready
            is ParakeetState.Failed -> OptionState.Failed(s.problem)
        }
        option.whisperModelId != null -> when (val s = content.whisper[option.whisperModelId]) {
            is SlotState.Busy -> OptionState.Running(s.total?.takeIf { it > 0 }?.let { s.received.toFloat() / it } ?: 0f)
            is SlotState.Ready -> OptionState.Ready
            is SlotState.Failed -> OptionState.Failed(s.problem)
            SlotState.Missing, null -> OptionState.Missing
        }
        else -> OptionState.Missing
    }
}

private fun trustOf(option: EngineOption, phone: PhoneFacts?): TrustLevel = when (option) {
    EngineOption.ANDROID -> if (phone?.androidOnDevice != false) TrustLevel.ON_DEVICE else TrustLevel.CLOUD
    EngineOption.SERVER -> EnginePresets.TINFOIL.trustLevel
    else -> TrustLevel.ON_DEVICE
}

@Composable
private fun megabytes(option: EngineOption): String = stringResource(R.string.megabytes, (option.downloadBytes / 1_000_000).toInt())

@Composable
private fun sizeText(option: EngineOption): String? = when (option) {
    EngineOption.ANDROID -> stringResource(R.string.engine_step_no_download)
    EngineOption.SERVER -> stringResource(R.string.engine_step_in_settings)
    else -> stringResource(R.string.engine_step_download_size, megabytes(option))
}

@Composable
private fun waitText(option: EngineOption): String? = when (option) {
    EngineOption.PARAKEET -> stringResource(R.string.engine_step_wait_parakeet)
    EngineOption.ACFT_LARGE -> stringResource(R.string.engine_step_wait_large)
    EngineOption.FUTO_244 -> stringResource(R.string.engine_step_wait_small)
    EngineOption.FUTO_74 -> stringResource(R.string.engine_step_wait_base)
    EngineOption.ANDROID, EngineOption.SERVER -> null
}
