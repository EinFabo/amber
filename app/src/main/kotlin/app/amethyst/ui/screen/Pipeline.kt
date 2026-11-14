package app.amethyst.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amethyst.R
import app.amethyst.core.engine.Fallback
import app.amethyst.core.engine.ReadyFallback
import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.engine.Servers
import app.amethyst.core.onboarding.CleanupServices
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.FallbackMode
import app.amethyst.core.settings.FallbackSettings
import app.amethyst.core.settings.SettingsItem
import app.amethyst.core.settings.SettingsLayout
import app.amethyst.core.settings.WritingStyle
import app.amethyst.core.text.ChatCleanup
import app.amethyst.ui.ExpandChevron
import app.amethyst.ui.Hint
import app.amethyst.ui.RadioRow
import app.amethyst.ui.SectionCard
import app.amethyst.ui.ToggleRow
import app.amethyst.ui.Unfold
import app.amethyst.ui.engineName
import app.amethyst.ui.foldState
import app.amethyst.ui.recognitionRowName
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes
import app.amethyst.ui.theme.badgeLabel
import app.amethyst.ui.words
import kotlinx.coroutines.delay

/**
 * Who rewrites the recognized text, chosen apart from who recognizes it: local Whisper can be
 * cleaned up by a model on Tinfoil, Claude, Groq, OpenAI or an own server. One row per
 * service ([CleanupServiceList]), each opening a sheet with its key and its models; the one in
 * use is marked and says its model.
 */
@Composable
fun CleanupScreen(
    settings: AmethystSettings,
    access: CleanupAccess,
    modifier: Modifier = Modifier,
    // For the screenshots: a sheet open.
    initialSheet: ServiceSheet? = null,
) {
    val services = rememberServiceRows(settings, access)
    // A cleanup whose server was removed runs nowhere: shown as off, which is what happens.
    val current = settings.cleanup
    val row = CleanupServices.rowOf(services.rows, current)
    val sheet = remember { mutableStateOf(initialSheet) }

    SubPage(modifier) {
        SectionCard(
            title = stringResource(R.string.cleanup_who),
            subtitle = stringResource(R.string.cleanup_who_subtitle),
            learnMore = stringResource(R.string.cleanup_who_more),
        ) {
            RadioRow(
                stringResource(R.string.off),
                stringResource(R.string.cleanup_off_note),
                selected = current == null || row == null,
                onSelect = { access.onUpdateSettings { it.copy(cleanup = null) } },
            )
            CleanupServiceList(settings, access, services, inUse = current, sheet = sheet, write = CleanupServices::forEveryApp)
            CleanupLeavesPhoneHint(settings.activeEngine?.trustLevelForDisplay(), row?.engine?.trustLevelForDisplay())
        }
        if (SettingsLayout.shows(SettingsItem.CLEANUP_INSTRUCTION, settings.ui.advanced)) CleanupInstructionCard(settings, access.onUpdateSettings)
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

/** Said wherever the cleanup is chosen: a server cleanup after local recognition sends the text out. */
@Composable
internal fun CleanupLeavesPhoneHint(recognition: TrustLevel?, cleanup: TrustLevel?) {
    if (recognition != null && cleanup != null && cleanup.strictness > recognition.strictness) {
        Hint(stringResource(R.string.cleanup_leaves_phone), AmberPalette.Primary)
    }
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
                    recognitionRowName(engine),
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
