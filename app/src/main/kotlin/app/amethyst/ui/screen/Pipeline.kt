package app.amethyst.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.amethyst.core.engine.EngineConfigValidator
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.Fallback
import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.FallbackMode
import app.amethyst.core.settings.FallbackSettings
import app.amethyst.ui.GhostButton
import app.amethyst.ui.GroupLabel
import app.amethyst.ui.Hint
import app.amethyst.ui.RadioRow
import app.amethyst.ui.SectionCard
import app.amethyst.ui.TrustBadge
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes
import app.amethyst.ui.theme.badgeLabel

/**
 * Who rewrites the recognized text, chosen apart from who recognizes it: local Whisper can be
 * cleaned up by a model on Tinfoil, Groq, OpenAI or an own server. Every configured server
 * engine lists its chat models under its own heading; one of them is the cleanup, or none.
 */
@Composable
fun CleanupScreen(
    settings: AmethystSettings,
    hasKeyFor: (String?) -> Boolean,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = settings.cleanup
    val servers = settings.engines.filter { it.kind == EngineKind.OPENAI_COMPATIBLE }
    // hasKeyFor() decrypts, so once per settings change rather than on every recomposition.
    val ready = remember(settings) {
        servers.associate { engine ->
            engine.id to EngineConfigValidator.validate(engine, hasApiKey = hasKeyFor(engine.apiKeyAlias)).isEmpty()
        }
    }
    fun choose(choice: CleanupChoice?) = onUpdateSettings { it.copy(cleanup = choice) }

    SubPage(modifier) {
        SectionCard(
            title = "Who tidies the text",
            subtitle = "A chat model fixes punctuation, filler words and mis-hearings before the text is typed, " +
                "and writes in each app's style.",
        ) {
            RadioRow("Off", "Text goes in as recognized", selected = current == null, onSelect = { choose(null) })
            if (servers.isEmpty()) {
                Hint("Add a server engine under Recognition (Tinfoil, Groq, OpenAI or your own) to use one of its models.")
            }
            servers.forEach { engine ->
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    GroupLabel(engine.displayName) { TrustBadge(engine.trustLevelForDisplay()) }
                    val selected = current?.takeIf { it.engineId == engine.id }?.model
                    val provider = ServerProviders.forUrl(engine.baseUrl)
                    when {
                        ready[engine.id] != true -> Hint("Set this engine up first (address, key).")
                        provider.known && provider.cleanupModels.isNotEmpty() -> ModelPicker(
                            choices = provider.cleanupModels,
                            selected = selected,
                            noneLabel = null,
                            onSelect = { id -> id?.let { choose(CleanupChoice(engine.id, it)) } },
                        )
                        else -> OwnCleanupModel(selected) { model -> choose(CleanupChoice(engine.id, model)) }
                    }
                }
            }
        }

        val recognition = settings.activeEngine?.trustLevelForDisplay()
        val cleanupTrust = settings.cleanupEngine()?.trustLevelForDisplay()
        if (recognition != null && cleanupTrust != null && cleanupTrust.strictness > recognition.strictness) {
            Hint(
                "The recognized text leaves the phone for the cleanup - your recording does not. " +
                    "Dictations are labelled with where the text went.",
                AmberPalette.Primary,
            )
        }
        Hint("If the cleanup fails or changes too much, the dictation goes in as recognized.")
    }
}

/** An own server's chat model, typed: it names its models itself. */
@Composable
private fun OwnCleanupModel(selected: String?, onUse: (String) -> Unit) {
    var model by remember(selected) { mutableStateOf(selected.orEmpty()) }
    OutlinedTextField(
        value = model,
        onValueChange = { model = it },
        label = { Text("Chat model") },
        placeholder = { Text("llama3.2") },
        singleLine = true,
        shape = AmberShapes.Inner,
        colors = fieldColors(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    val trimmed = model.trim()
    if (trimmed.isNotEmpty() && trimmed != selected) GhostButton("Use $trimmed", { onUse(trimmed) })
    if (selected != null && trimmed == selected) Hint("In use for cleanup.", AmberPalette.TrustOnDevice)
}

/**
 * Who takes a recording the engine's server could not: the best model on the phone, nobody,
 * or an engine the user picks - only ever one at least as private as the recognition.
 */
@Composable
fun FallbackScreen(
    settings: AmethystSettings,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = settings.activeEngine
    val fallback = settings.fallback
    fun choose(choice: FallbackSettings) = onUpdateSettings { it.copy(fallback = choice) }

    SubPage(modifier) {
        SectionCard(
            title = "Who steps in",
            subtitle = "No connection, a timeout or a server down: who recognizes the recording instead. " +
                "Never an engine less private than the one it stands in for.",
        ) {
            if (primary != null && primary.runsOnPhone()) {
                Hint(
                    "${primary.displayName} runs on this phone, so there is no server to fail. " +
                        "This applies once you recognize with a server engine.",
                    AmberPalette.Primary,
                )
            }
            RadioRow(
                "Automatic", "Parakeet, then Whisper - if downloaded",
                selected = fallback.mode == FallbackMode.AUTOMATIC,
                onSelect = { choose(FallbackSettings(FallbackMode.AUTOMATIC)) },
            )
            RadioRow(
                "Off", "The dictation fails",
                selected = fallback.mode == FallbackMode.OFF,
                onSelect = { choose(FallbackSettings(FallbackMode.OFF)) },
            )
            val choices = primary?.let { Fallback.choices(settings.engines, it) { engine -> engine.trustLevelForDisplay() } }.orEmpty()
            choices.forEach { engine ->
                RadioRow(
                    engine.displayName,
                    engine.trustLevelForDisplay().badgeLabel(),
                    selected = fallback.mode == FallbackMode.ENGINE && fallback.engineId == engine.id,
                    onSelect = { choose(FallbackSettings(FallbackMode.ENGINE, engine.id)) },
                )
            }
            val chosen = fallback.engineId?.let { id -> settings.engines.firstOrNull { it.id == id } }
            if (fallback.mode == FallbackMode.ENGINE && primary != null && chosen != null && chosen !in choices) {
                Hint(
                    "${chosen.displayName} is less private than ${primary.displayName}, so it never takes over " +
                        "from it - a failed dictation stays failed.",
                    AmberPalette.Primary,
                )
            }
        }
    }
}

/** The engine whose chat model does the cleanup, if any. */
internal fun AmethystSettings.cleanupEngine(): EngineConfig? =
    cleanup?.let { choice -> engines.firstOrNull { it.id == choice.engineId } }

/** The cleanup in a few words - "DeepSeek V4.1 Flash · Tinfoil (Enclave)" - or null when off. */
internal fun AmethystSettings.cleanupSummary(): String? {
    val choice = cleanup ?: return null
    val engine = cleanupEngine() ?: return null
    val model = ServerProviders.forUrl(engine.baseUrl).cleanupModel(choice.model)?.label ?: choice.model
    return "$model · ${engine.displayName}"
}

/** The fallback in a few words, for the settings list. */
internal fun AmethystSettings.fallbackSummary(): String {
    val primary = activeEngine
    if (primary != null && primary.runsOnPhone()) return "Not needed - recognition runs on this phone"
    return when (fallback.mode) {
        FallbackMode.AUTOMATIC -> "Automatic: Parakeet, then Whisper"
        FallbackMode.OFF -> "Off: the dictation fails"
        FallbackMode.ENGINE -> engines.firstOrNull { it.id == fallback.engineId }?.displayName ?: "Off: the dictation fails"
    }
}

/** A recording that never leaves the phone has no server that could fail. */
private fun EngineConfig.runsOnPhone(): Boolean = trustLevelForDisplay() == TrustLevel.ON_DEVICE
