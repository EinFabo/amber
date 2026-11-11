package app.amethyst.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amethyst.R
import app.amethyst.Texts
import app.amethyst.core.engine.EngineConfigValidator
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.Fallback
import app.amethyst.core.engine.ReadyFallback
import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.engine.Servers
import app.amethyst.core.onboarding.CleanupPick
import app.amethyst.core.onboarding.CleanupService
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.FallbackMode
import app.amethyst.core.settings.FallbackSettings
import app.amethyst.core.settings.SettingsItem
import app.amethyst.core.settings.SettingsLayout
import app.amethyst.core.settings.SettingsSummaries
import app.amethyst.core.settings.WritingStyle
import app.amethyst.core.text.ChatCleanup
import app.amethyst.data.PhoneLocales
import app.amethyst.ui.ExpandChevron
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.RadioRow
import app.amethyst.ui.SectionCard
import app.amethyst.ui.ToggleRow
import app.amethyst.ui.TrustBadge
import app.amethyst.ui.Unfold
import app.amethyst.ui.catalog
import app.amethyst.ui.engineName
import app.amethyst.ui.foldState
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes
import app.amethyst.ui.theme.badgeLabel
import app.amethyst.ui.words
import kotlinx.coroutines.delay

/**
 * Who rewrites the recognized text, chosen apart from who recognizes it: local Whisper can be
 * cleaned up by a model on Tinfoil, Groq, OpenAI, Claude or an own server. Every configured server
 * engine lists its chat models under its own heading - an Amber PC or Server the ones it
 * offers; one of them is the cleanup, or none.
 */
@Composable
fun CleanupScreen(
    settings: AmethystSettings,
    hasKeyFor: (String?) -> Boolean,
    onSaveKey: (alias: String, key: String) -> Unit,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
    modifier: Modifier = Modifier,
    // For the screenshots: Claude's row open, a key typed.
    initialClaudeKey: String? = null,
) {
    val current = settings.cleanup
    val ready = rememberCleanupReadiness(settings, hasKeyFor)
    fun choose(choice: CleanupChoice?) = onUpdateSettings { it.copy(cleanup = choice) }

    SubPage(modifier) {
        SectionCard(
            title = stringResource(R.string.cleanup_who),
            subtitle = stringResource(R.string.cleanup_who_subtitle),
            learnMore = stringResource(R.string.cleanup_who_more),
        ) {
            RadioRow(stringResource(R.string.off), stringResource(R.string.cleanup_off_note), selected = current == null, onSelect = { choose(null) })
            CleanupModelChoices(settings, ready, current, ::choose)
            // Claude has no speech recognition, so it is added here rather than there.
            if (settings.engines.none { ServerProviders.forUrl(it.baseUrl) === ServerProviders.ANTHROPIC }) {
                AddClaude(settings, initialClaudeKey, onSaveKey, onUpdateSettings)
            }
            CleanupLeavesPhoneHint(settings.activeEngine?.trustLevelForDisplay(), settings.cleanupEngine()?.trustLevelForDisplay())
        }
        if (SettingsLayout.shows(SettingsItem.CLEANUP_INSTRUCTION, settings.ui.advanced)) CleanupInstructionCard(settings, onUpdateSettings)
    }
}

/** Typing pauses this long before the addition is saved. */
private const val ADDITION_SAVE_MS = 600L

/**
 * The user's own addition for every app, and the prompt it ends up in - read-only, so what the
 * cleanup model is told is never a secret. The fixed rules come first and win over the addition.
 */
@Composable
private fun CleanupInstructionCard(
    settings: AmethystSettings,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
) {
    var addition by remember { mutableStateOf(settings.cleanupInstruction.orEmpty()) }
    var showPrompt by rememberSaveable { mutableStateOf(false) }

    fun persist() {
        val typed = addition.trim().ifEmpty { null }
        if (typed != settings.cleanupInstruction) onUpdateSettings { it.copy(cleanupInstruction = typed) }
    }

    SectionCard(
        title = stringResource(R.string.cleanup_addition),
        subtitle = stringResource(R.string.cleanup_addition_subtitle),
        learnMore = stringResource(R.string.cleanup_addition_more),
    ) {
        OutlinedTextField(
            value = addition,
            onValueChange = { addition = it.take(ChatCleanup.INSTRUCTION_CHARS) },
            label = { Text(stringResource(R.string.cleanup_addition_label)) },
            placeholder = { Text(stringResource(R.string.cleanup_addition_placeholder)) },
            supportingText = { Text(stringResource(R.string.char_count, addition.length, ChatCleanup.INSTRUCTION_CHARS)) },
            minLines = 2,
            maxLines = 8,
            shape = AmberShapes.Inner,
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(AmberShapes.Inner)
                .clickable { showPrompt = !showPrompt }
                .foldState(showPrompt)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.cleanup_view_prompt), style = MaterialTheme.typography.bodyLarge, color = AmberPalette.Text, modifier = Modifier.weight(1f))
            ExpandChevron(showPrompt)
        }
        Unfold(showPrompt) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // As sent for an app with the default style; the app's own style and
                // instruction take their places in it, and the language is named when known.
                val prompt = remember(settings.cleanupInstruction) {
                    ChatCleanup.systemPrompt(WritingStyle.AS_SPOKEN, null, settings.cleanupInstruction)
                }
                Text(
                    prompt,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
                    color = AmberPalette.Text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(AmberShapes.Inner)
                        .background(AmberPalette.Elevated)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
                Hint(stringResource(R.string.cleanup_prompt_note, ChatCleanup.EXAMPLES.size))
            }
        }
    }

    // Saved a moment after typing stops, and when the page goes, as an app's instruction is.
    LaunchedEffect(addition) {
        delay(ADDITION_SAVE_MS)
        persist()
    }
    val latestPersist by rememberUpdatedState(::persist)
    DisposableEffect(Unit) {
        onDispose { latestPersist() }
    }
}

/** Which server engines are set up (address, key) - once per settings change, not on every recomposition. */
@Composable
internal fun rememberCleanupReadiness(settings: AmethystSettings, hasKeyFor: (String?) -> Boolean): Map<String, Boolean> =
    remember(settings) {
        settings.engines.filter { it.kind == EngineKind.OPENAI_COMPATIBLE }.associate { engine ->
            engine.id to EngineConfigValidator.readyForCleanup(engine, hasApiKey = hasKeyFor(engine.apiKeyAlias))
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
        Hint(stringResource(R.string.cleanup_no_server))
    }
    servers.forEach { engine ->
        val model = selected?.takeIf { it.engineId == engine.id }?.model
        var open by rememberSaveable(engine.id) { mutableStateOf(model != null) }
        val provider = ServerProviders.forEngine(engine, settings)
        val listed = provider.hasModelList
        val isReady = ready[engine.id] == true
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            EngineFold(
                name = engineName(engine),
                trust = engine.trustLevelForDisplay(),
                note = when {
                    !isReady -> stringResource(R.string.cleanup_set_up_first)
                    listed && provider.cleanupModels.isEmpty() -> stringResource(R.string.cleanup_none_offered)
                    // Named, since the engine is still called "Own server".
                    provider.listsItself -> "${provider.name} · ${words(SettingsSummaries.models(provider.cleanupModels.size))}"
                    listed -> words(SettingsSummaries.models(provider.cleanupModels.size))
                    else -> model ?: stringResource(R.string.cleanup_own_model)
                },
                open = open,
                onToggle = { open = !open },
            )
            Unfold(open) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when {
                        !isReady -> Hint(stringResource(R.string.cleanup_set_up_engine))
                        listed -> {
                            ModelPicker(
                                choices = provider.cleanupModels,
                                selected = model,
                                noneLabel = null,
                                notOffered = provider.notOfferedBy(model, provider.cleanupModels)?.let { Texts.notOffered(LocalContext.current, it) },
                                onSelect = { id -> id?.let { onChoose(CleanupChoice(engine.id, it)) } },
                            )
                            if (provider.cleanupModels.isEmpty()) Hint(stringResource(R.string.cleanup_no_model, catalog(provider.name)))
                        }
                        else -> OwnCleanupModel(model) { typed -> onChoose(CleanupChoice(engine.id, typed)) }
                    }
                }
            }
        }
    }
}

/**
 * Claude, from its API key: the same server setup's cleanup step makes ([CleanupService]),
 * cleaning up with its recommended model at once. The key is never kept in saved state.
 */
@Composable
private fun AddClaude(
    settings: AmethystSettings,
    initialKey: String?,
    onSaveKey: (alias: String, key: String) -> Unit,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(initialKey != null) }
    var key by remember { mutableStateOf(initialKey.orEmpty()) }
    // Written once: a second tap must not add a second Claude.
    var written by remember { mutableStateOf(false) }
    val claudeKey = Servers.isClaudeKey(key)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EngineFold(
            name = ServerProviders.ANTHROPIC.name,
            trust = TrustLevel.CLOUD,
            note = stringResource(R.string.cleanup_claude_add),
            open = open,
            onToggle = { open = !open },
        )
        Unfold(open) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(stringResource(R.string.engine_api_key)) },
                    placeholder = { Text("sk-ant-…") }, // English: the key's shape
                    singleLine = true,
                    shape = AmberShapes.Inner,
                    colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    // Masked: off screenshots, recordings and other eyes.
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (key.isNotBlank() && !claudeKey) {
                    Hint(stringResource(R.string.cleanup_claude_not_a_key), AmberPalette.Error)
                } else {
                    Hint(stringResource(R.string.engine_key_stored))
                }
                GhostButton(
                    stringResource(R.string.cleanup_claude_use),
                    {
                        if (written) return@GhostButton
                        written = true
                        val phone = PhoneLocales.tags()
                        val setup = CleanupService.setup(settings, CleanupService.claude(), phone)
                        setup.engine.apiKeyAlias?.let { onSaveKey(it, key.trim()) }
                        onUpdateSettings { CleanupPick.SERVICE.applyTo(it, keyReady = true, phone, setup) }
                    },
                    enabled = claudeKey && !written,
                )
            }
        }
    }
}

/** An engine's models folded to one row: "Groq", where it sends the text, how many to pick from. */
@Composable
private fun EngineFold(name: String, trust: TrustLevel, note: String, open: Boolean, onToggle: () -> Unit) {
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
            Text(name, style = MaterialTheme.typography.bodyLarge, color = AmberPalette.Text)
            TrustBadge(trust, detail = note)
        }
        ExpandChevron(open)
    }
}

/** Said wherever the cleanup is chosen: a server cleanup after local recognition sends the text out. */
@Composable
internal fun CleanupLeavesPhoneHint(recognition: TrustLevel?, cleanup: TrustLevel?) {
    if (recognition != null && cleanup != null && cleanup.strictness > recognition.strictness) {
        Hint(stringResource(R.string.cleanup_leaves_phone), AmberPalette.Primary)
    }
}

/** An own server's chat model, typed: it names its models itself. */
@Composable
private fun OwnCleanupModel(selected: String?, onUse: (String) -> Unit) {
    var model by remember(selected) { mutableStateOf(selected.orEmpty()) }
    OutlinedTextField(
        value = model,
        onValueChange = { model = it },
        label = { Text(stringResource(R.string.cleanup_chat_model)) },
        placeholder = { Text("llama3.2") },
        singleLine = true,
        shape = AmberShapes.Inner,
        colors = fieldColors(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    val trimmed = model.trim()
    if (trimmed.isNotEmpty() && trimmed != selected) GhostButton(stringResource(R.string.cleanup_use_model, trimmed), { onUse(trimmed) })
    if (selected != null && trimmed == selected) Hint(stringResource(R.string.cleanup_in_use), AmberPalette.TrustOnDevice)
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
    // Who steps in changes; whether it prepares stays the user's.
    fun choose(choice: FallbackSettings) = onUpdateSettings { it.copy(fallback = choice.copy(prepare = it.fallback.prepare)) }

    SubPage(modifier) {
        SectionCard(
            title = stringResource(R.string.fallback_who),
            subtitle = stringResource(R.string.fallback_who_subtitle),
            learnMore = stringResource(R.string.fallback_who_more),
        ) {
            if (primary != null && primary.runsOnPhone()) {
                Hint(stringResource(R.string.fallback_runs_on_phone, engineName(primary)), AmberPalette.Primary)
            }
            RadioRow(
                stringResource(R.string.fallback_automatic), stringResource(R.string.fallback_automatic_note),
                selected = fallback.mode == FallbackMode.AUTOMATIC,
                onSelect = { choose(FallbackSettings(FallbackMode.AUTOMATIC)) },
            )
            RadioRow(
                stringResource(R.string.off), stringResource(R.string.fallback_off_note),
                selected = fallback.mode == FallbackMode.OFF,
                onSelect = { choose(FallbackSettings(FallbackMode.OFF)) },
            )
            val choices = primary?.let { Fallback.choices(Servers.recognizers(settings), it) { engine -> engine.trustLevelForDisplay() } }.orEmpty()
            choices.forEach { engine ->
                RadioRow(
                    engineName(engine),
                    engine.trustLevelForDisplay().badgeLabel(),
                    selected = fallback.mode == FallbackMode.ENGINE && fallback.engineId == engine.id,
                    onSelect = { choose(FallbackSettings(FallbackMode.ENGINE, engine.id)) },
                )
            }
            val chosen = fallback.engineId?.let { id -> settings.engines.firstOrNull { it.id == id } }
            if (fallback.mode == FallbackMode.ENGINE && primary != null && chosen != null && chosen !in choices) {
                Hint(stringResource(R.string.fallback_less_private, engineName(chosen), engineName(primary)), AmberPalette.Primary)
            }
        }
        if (ReadyFallback.preparable(fallback, settings.engines) && (primary == null || !primary.runsOnPhone())) {
            SectionCard(
                title = stringResource(R.string.fallback_ready),
                subtitle = stringResource(R.string.fallback_ready_subtitle),
                learnMore = stringResource(R.string.fallback_ready_more),
            ) {
                ToggleRow(
                    stringResource(R.string.fallback_prepare),
                    stringResource(R.string.fallback_prepare_note),
                    checked = fallback.prepare,
                    onChange = { on -> onUpdateSettings { it.copy(fallback = it.fallback.copy(prepare = on)) } },
                )
                if (fallback.prepare) Hint(stringResource(R.string.fallback_prepare_on))
            }
        }
    }
}

/** The engine whose chat model does [choice] - by default the cleanup for every app - if any. */
internal fun AmethystSettings.cleanupEngine(choice: CleanupChoice? = cleanup): EngineConfig? =
    choice?.let { engines.firstOrNull { engine -> engine.id == it.engineId } }

/** A cleanup in a few words - "DeepSeek V4.1 Flash · Tinfoil (Enclave)" - or null when off. */
@Composable
internal fun AmethystSettings.cleanupSummary(choice: CleanupChoice? = cleanup): String? {
    if (choice == null) return null
    val engine = cleanupEngine(choice) ?: return null
    val model = ServerProviders.forEngine(engine, this).cleanupModel(choice.model)?.label ?: choice.model
    return "$model · ${engineName(engine)}"
}

/** A recording that never leaves the phone has no server that could fail. */
private fun EngineConfig.runsOnPhone(): Boolean = trustLevelForDisplay() == TrustLevel.ON_DEVICE
