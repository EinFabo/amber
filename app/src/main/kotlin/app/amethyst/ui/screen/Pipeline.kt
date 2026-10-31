package app.amethyst.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import app.amethyst.core.settings.SettingsSummaries
import app.amethyst.ui.ExpandChevron
import app.amethyst.ui.foldState
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.RadioRow
import app.amethyst.ui.SectionCard
import app.amethyst.ui.TrustBadge
import app.amethyst.ui.Unfold
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
    val ready = rememberCleanupReadiness(settings, hasKeyFor)
    fun choose(choice: CleanupChoice?) = onUpdateSettings { it.copy(cleanup = choice) }

    SubPage(modifier) {
        SectionCard(
            title = "Who tidies the text",
            subtitle = "A chat model tidies the text before it is typed.",
            learnMore = "It fixes punctuation, filler words and mis-hearings, and writes in each app's style. " +
                "If the cleanup fails or changes too much, the dictation goes in as recognized.",
        ) {
            RadioRow("Off", "Text goes in as recognized", selected = current == null, onSelect = { choose(null) })
            CleanupModelChoices(settings, ready, current, ::choose)
            CleanupLeavesPhoneHint(settings.activeEngine?.trustLevelForDisplay(), settings.cleanupEngine()?.trustLevelForDisplay())
        }
    }
}

/** Which server engines are set up (address, key) - once per settings change: [hasKeyFor] decrypts. */
@Composable
internal fun rememberCleanupReadiness(settings: AmethystSettings, hasKeyFor: (String?) -> Boolean): Map<String, Boolean> =
    remember(settings) {
        settings.engines.filter { it.kind == EngineKind.OPENAI_COMPATIBLE }.associate { engine ->
            engine.id to EngineConfigValidator.validate(engine, hasApiKey = hasKeyFor(engine.apiKeyAlias)).isEmpty()
        }
    }

/**
 * Every configured server engine's chat models, [selected] ticked - the cleanup for every app,
 * or one app's own. Each engine folds to one row; only the one holding the choice starts open.
 */
@Composable
internal fun CleanupModelChoices(
    settings: AmethystSettings,
    ready: Map<String, Boolean>,
    selected: CleanupChoice?,
    onChoose: (CleanupChoice) -> Unit,
) {
    val servers = settings.engines.filter { it.kind == EngineKind.OPENAI_COMPATIBLE }
    if (servers.isEmpty()) {
        Hint("Add a server engine under Recognition (Tinfoil, Groq, OpenAI or your own) to use one of its models.")
    }
    servers.forEach { engine ->
        val model = selected?.takeIf { it.engineId == engine.id }?.model
        var open by rememberSaveable(engine.id) { mutableStateOf(model != null) }
        val provider = ServerProviders.forUrl(engine.baseUrl)
        val listed = provider.known && provider.cleanupModels.isNotEmpty()
        val isReady = ready[engine.id] == true
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            EngineFold(
                engine = engine,
                note = when {
                    !isReady -> "Set up first"
                    listed -> SettingsSummaries.models(provider.cleanupModels.size)
                    else -> model ?: "Your own model"
                },
                open = open,
                onToggle = { open = !open },
            )
            Unfold(open) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when {
                        !isReady -> Hint("Set this engine up first (address, key).")
                        listed -> ModelPicker(
                            choices = provider.cleanupModels,
                            selected = model,
                            noneLabel = null,
                            onSelect = { id -> id?.let { onChoose(CleanupChoice(engine.id, it)) } },
                        )
                        else -> OwnCleanupModel(model) { typed -> onChoose(CleanupChoice(engine.id, typed)) }
                    }
                }
            }
        }
    }
}

/** An engine's models folded to one row: "Groq", where it sends the text, how many to pick from. */
@Composable
private fun EngineFold(engine: EngineConfig, note: String, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(AmberShapes.Inner)
            .clickable(onClick = onToggle)
            .foldState(open)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(engine.displayName, style = MaterialTheme.typography.bodyLarge, color = AmberPalette.Text)
            TrustBadge(engine.trustLevelForDisplay(), detail = note)
        }
        ExpandChevron(open)
    }
}

/** Said wherever the cleanup is chosen: a server cleanup after local recognition sends the text out. */
@Composable
internal fun CleanupLeavesPhoneHint(recognition: TrustLevel?, cleanup: TrustLevel?) {
    if (recognition != null && cleanup != null && cleanup.strictness > recognition.strictness) {
        Hint(
            "The recognized text leaves the phone for the cleanup - your recording does not. " +
                "Dictations are labelled with where the text went.",
            AmberPalette.Primary,
        )
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
            subtitle = "When the server can't take a recording.",
            learnMore = "No connection, a timeout or a server down: the engine chosen here recognizes the " +
                "recording instead. Never one less private than the one it stands in for.",
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

/** The engine whose chat model does [choice] - by default the cleanup for every app - if any. */
internal fun AmethystSettings.cleanupEngine(choice: CleanupChoice? = cleanup): EngineConfig? =
    choice?.let { engines.firstOrNull { engine -> engine.id == it.engineId } }

/** A cleanup in a few words - "DeepSeek V4.1 Flash · Tinfoil (Enclave)" - or null when off. */
internal fun AmethystSettings.cleanupSummary(choice: CleanupChoice? = cleanup): String? {
    if (choice == null) return null
    val engine = cleanupEngine(choice) ?: return null
    val model = ServerProviders.forUrl(engine.baseUrl).cleanupModel(choice.model)?.label ?: choice.model
    return "$model · ${engine.displayName}"
}

/** A recording that never leaves the phone has no server that could fail. */
private fun EngineConfig.runsOnPhone(): Boolean = trustLevelForDisplay() == TrustLevel.ON_DEVICE
