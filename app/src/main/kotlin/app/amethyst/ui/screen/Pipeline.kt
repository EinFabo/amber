package app.amethyst.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.Fallback
import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.FallbackMode
import app.amethyst.core.settings.FallbackSettings
import app.amethyst.ui.ChoiceChip
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.SectionCard
import app.amethyst.ui.TrustBadge
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes
import app.amethyst.ui.theme.badgeLabel

/**
 * Who rewrites the recognized text, chosen apart from who recognizes it: local Whisper can be
 * cleaned up by a model on Tinfoil, Groq, OpenAI or an own server. Every configured server
 * engine offers its chat models; one of them is the cleanup, or none.
 */
@Composable
internal fun CleanupSection(
    settings: AmethystSettings,
    isReady: (EngineConfig) -> Boolean,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
) {
    val current = settings.cleanup
    val servers = settings.engines.filter { it.kind == EngineKind.OPENAI_COMPATIBLE }
    fun choose(choice: CleanupChoice?) = onUpdateSettings { it.copy(cleanup = choice) }

    SectionCard(
        title = "Cleanup",
        subtitle = "A chat model tidies each dictation before it is typed: punctuation, filler words, " +
            "mis-hearings - and the writing style per app. It works with any recognition engine.",
    ) {
        ChoiceChip("Off", "Text goes in as recognized", on = current == null) { choose(null) }
        if (servers.isEmpty()) Hint("Add a server engine (Tinfoil, Groq, OpenAI or your own) to use one of its models.")
        servers.forEach { engine ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(engine.displayName, style = MaterialTheme.typography.bodyLarge, color = AmberPalette.Text)
                    TrustBadge(engine.trustLevelForDisplay())
                }
                val selected = current?.takeIf { it.engineId == engine.id }?.model
                val provider = ServerProviders.forUrl(engine.baseUrl)
                when {
                    !isReady(engine) -> Hint("Set this engine up first (address, key).")
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

        val recognition = settings.activeEngine?.trustLevelForDisplay()
        val cleanupEngine = current?.let { choice -> settings.engines.firstOrNull { it.id == choice.engineId } }
        val cleanupTrust = cleanupEngine?.trustLevelForDisplay()
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FallbackSection(
    settings: AmethystSettings,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
) {
    val primary = settings.activeEngine
    val fallback = settings.fallback
    fun choose(choice: FallbackSettings) = onUpdateSettings { it.copy(fallback = choice) }

    SectionCard(
        title = "When the server fails",
        subtitle = "No connection, a timeout or a server down: who recognizes the recording instead.",
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip("Automatic", "Parakeet, then Whisper - if downloaded", on = fallback.mode == FallbackMode.AUTOMATIC) {
                choose(FallbackSettings(FallbackMode.AUTOMATIC))
            }
            ChoiceChip("Off", "The dictation fails", on = fallback.mode == FallbackMode.OFF) {
                choose(FallbackSettings(FallbackMode.OFF))
            }
            val choices = primary?.let { Fallback.choices(settings.engines, it) { engine -> engine.trustLevelForDisplay() } }.orEmpty()
            choices.forEach { engine ->
                ChoiceChip(
                    engine.displayName,
                    engine.trustLevelForDisplay().badgeLabel(),
                    on = fallback.mode == FallbackMode.ENGINE && fallback.engineId == engine.id,
                ) { choose(FallbackSettings(FallbackMode.ENGINE, engine.id)) }
            }
        }
        val chosen = fallback.engineId?.let { id -> settings.engines.firstOrNull { it.id == id } }
        if (fallback.mode == FallbackMode.ENGINE && primary != null && chosen != null &&
            Fallback.candidates(fallback, settings.engines, primary) { it.trustLevelForDisplay() }.isEmpty()
        ) {
            Hint(
                "${chosen.displayName} is less private than ${primary.displayName}, so it never takes over " +
                    "from it - a failed dictation stays failed.",
                AmberPalette.Primary,
            )
        }
        Hint("A fallback is never less private than the engine it stands in for.")
    }
}
