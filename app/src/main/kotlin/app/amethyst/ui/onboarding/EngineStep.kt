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
import androidx.compose.ui.unit.dp
import app.amethyst.core.engine.EngineAdvice
import app.amethyst.core.engine.EngineOption
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.PhoneFacts
import app.amethyst.core.onboarding.EngineCard
import app.amethyst.core.engine.whisper.ModelSlots.SlotState
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

/** Where an option's download stands. */
private sealed interface OptionState {
    data object Missing : OptionState
    data class Running(val fraction: Float) : OptionState
    data object Waiting : OptionState
    data object Ready : OptionState
    data class Failed(val message: String) : OptionState
}

/**
 * How Amber hears you: the engine this phone is best served by, on a card that says what it
 * costs - size and wait, never "model" - and starts its download. The download keeps going
 * while the remaining steps are done. Other options sit one tap away.
 */
@Composable
internal fun EngineStep(settings: AmethystSettings, content: OnboardingContent, onNext: () -> Unit) {
    val title = "How Amber hears you"
    val lead = "Pick what turns your voice into text. You can change it any time in Settings → Recognition."
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
                shown == EngineOption.ANDROID -> ContinueButton({ content.onChooseEngine(shown, false); onNext() }, "Use Android's recognizer")
                state is OptionState.Running || state is OptionState.Waiting || (state is OptionState.Ready && active) -> ContinueButton(onNext)
                state is OptionState.Ready -> ContinueButton({ content.onChooseEngine(shown, false); onNext() }, "Use it")
                content.metered -> {
                    ContinueButton({ content.onChooseEngine(shown, true) }, "Download now - ${megabytes(shown)} of mobile data")
                    LaterButton("Wait for Wi-Fi") { content.onWaitForWifi(shown) }
                }
                else -> ContinueButton({ content.onChooseEngine(shown, false) }, if (state is OptionState.Failed) "Try again" else "Download")
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
                    GhostButton("Or take ${optionName(smaller)} (${megabytes(smaller)})", { picked = smaller.name })
                }
            }
        }
        GhostButton(if (showOthers) "Hide other options" else "Other options", { showOthers = !showOthers })
        if (showOthers) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf(advice.recommended) + advice.others).forEach { option ->
                    RadioRow(
                        label = optionName(option),
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
        Text("In use now", style = MaterialTheme.typography.labelLarge, color = AmberPalette.Primary)
        Text(engine.displayName, style = MaterialTheme.typography.titleLarge, color = AmberPalette.Text)
        TrustBadge(engine.trustLevelForDisplay())
        Hint("Stays as it is. Pick another one under Other options if you like.")
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
        if (recommended) Text("Recommended for this phone", style = MaterialTheme.typography.labelLarge, color = AmberPalette.Primary)
        Text(optionName(option), style = MaterialTheme.typography.titleLarge, color = AmberPalette.Text)
        TrustBadge(trustOf(option, content.phone))
        Hint(listOfNotNull(sizeText(option), waitText(option)).joinToString(" · "), AmberPalette.Text)
        when (state) {
            OptionState.Missing -> Unit
            is OptionState.Running -> {
                ProgressLine(state.fraction)
                Hint("You can go on - it keeps downloading.")
            }
            OptionState.Waiting -> Hint("Waits for Wi-Fi, then downloads by itself. You can go on.")
            OptionState.Ready -> Hint("Downloaded and ready.", AmberPalette.TrustOnDevice)
            is OptionState.Failed -> Hint(state.message, AmberPalette.Error)
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
            is ParakeetState.Failed -> OptionState.Failed(s.message)
        }
        option.whisperModelId != null -> when (val s = content.whisper[option.whisperModelId]) {
            is SlotState.Busy -> OptionState.Running(s.total?.takeIf { it > 0 }?.let { s.received.toFloat() / it } ?: 0f)
            is SlotState.Ready -> OptionState.Ready
            is SlotState.Failed -> OptionState.Failed(s.message)
            SlotState.Missing, null -> OptionState.Missing
        }
        else -> OptionState.Missing
    }
}

private fun optionName(option: EngineOption): String = when (option) {
    EngineOption.PARAKEET -> "Parakeet"
    EngineOption.ACFT_LARGE -> "Whisper large"
    EngineOption.FUTO_244 -> "Whisper small"
    EngineOption.FUTO_74 -> "Whisper base"
    EngineOption.ANDROID -> "Android's recognizer"
    EngineOption.SERVER -> "A server with your own key"
}

private fun trustOf(option: EngineOption, phone: PhoneFacts?): TrustLevel = when (option) {
    EngineOption.ANDROID -> if (phone?.androidOnDevice != false) TrustLevel.ON_DEVICE else TrustLevel.CLOUD
    EngineOption.SERVER -> EnginePresets.TINFOIL.trustLevel
    else -> TrustLevel.ON_DEVICE
}

private fun megabytes(option: EngineOption): String = "${option.downloadBytes / 1_000_000} MB"

private fun sizeText(option: EngineOption): String? = when (option) {
    EngineOption.ANDROID -> "no download"
    EngineOption.SERVER -> "set up in Settings"
    else -> "${megabytes(option)} download"
}

private fun waitText(option: EngineOption): String? = when (option) {
    EngineOption.PARAKEET -> "text about 1 s after you stop"
    EngineOption.ACFT_LARGE -> "most exact, a few seconds' wait"
    EngineOption.FUTO_244 -> "text in about 3 s"
    EngineOption.FUTO_74 -> "quick, less exact"
    EngineOption.ANDROID, EngineOption.SERVER -> null
}
