package app.amethyst.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.core.engine.CleanupRouting
import app.amethyst.core.engine.EngineRouting
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppCleanup
import app.amethyst.core.settings.AppCleanupMode
import app.amethyst.core.settings.AppEngine
import app.amethyst.core.settings.AppEngineMode
import app.amethyst.core.settings.AppProfile
import app.amethyst.core.settings.AppProfiles
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.SettingsLayout
import app.amethyst.core.settings.WritingStyle
import app.amethyst.core.text.ChatCleanup
import app.amethyst.ui.AppIcon
import app.amethyst.ui.AppSource
import app.amethyst.ui.ChoiceChip
import app.amethyst.ui.DangerAction
import app.amethyst.ui.Hint
import app.amethyst.ui.RadioRow
import app.amethyst.ui.SectionCard
import app.amethyst.ui.engineName
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes
import app.amethyst.ui.theme.badgeLabel
import kotlinx.coroutines.delay

/** Typing pauses this long before the instruction is saved. */
private const val INSTRUCTION_SAVE_MS = 600L

/**
 * One app's page: how the text reads there (style, own instruction), who tidies it - the
 * cleanup for every app, none, or a model of its own (a chat app may keep small slips, a notes
 * app gets the best model) - and, with the advanced settings, who hears it: the speech
 * recognition for every app or one of its own ([EngineRouting]).
 */
@Composable
fun AppStyleScreen(
    profile: AppProfile,
    settings: AmethystSettings,
    apps: AppSource,
    hasKeyFor: (String?) -> Boolean,
    /** Whether a speech recognition's model is on the phone (true for one elsewhere). */
    modelReady: (EngineConfig) -> Boolean,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
    onRemoved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = rememberCleanupReadiness(settings, hasKeyFor)
    // Once per settings change rather than on every recomposition.
    val decision = remember(settings) { CleanupRouting.decide(settings, profile.packageName, null, hasKeyFor) }
    // Not remembered: a model downloaded or a key stored while the page is open changes it, not the settings.
    val routing = EngineRouting.decide(settings, profile.packageName, hasKeyFor, modelReady)
    val own = profile.appCleanup

    // Edits only a profile that is still there, so a late save cannot bring back a removed one.
    fun edit(change: (AppProfile) -> AppProfile) =
        onUpdateSettings { it.copy(profiles = AppProfiles.edit(it.profiles, profile.packageName, change)) }

    SubPage(modifier) {
        Row(
            Modifier.padding(start = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppIcon(profile.packageName, apps, size = 44.dp)
            Text(profile.label, style = MaterialTheme.typography.titleLarge, color = AmberPalette.Text)
        }

        SectionCard(title = stringResource(R.string.style_title), subtitle = stringResource(R.string.style_subtitle, profile.label)) {
            StyleAndInstruction(profile, ::edit, chips = false)
        }

        if (SettingsLayout.showsAppRecognition(profile, settings.ui.advanced)) {
            RecognitionChoices(profile, settings, routing, hasKeyFor, modelReady) { own -> edit { it.copy(appEngine = own) } }
        }

        SectionCard(title = stringResource(R.string.settings_cleanup), subtitle = stringResource(R.string.style_cleanup_subtitle, profile.label)) {
            RadioRow(
                stringResource(R.string.style_default),
                settings.cleanupSummary() ?: stringResource(R.string.style_default_off),
                selected = own.mode == AppCleanupMode.DEFAULT,
                onSelect = { edit { it.copy(appCleanup = AppCleanup()) } },
            )
            RadioRow(
                stringResource(R.string.off),
                stringResource(R.string.cleanup_off_note),
                selected = own.mode == AppCleanupMode.OFF,
                onSelect = { edit { it.copy(appCleanup = AppCleanup(AppCleanupMode.OFF)) } },
            )
            CleanupModelChoices(settings, ready, selected = own.choice.takeIf { own.mode == AppCleanupMode.MODEL }) { choice ->
                edit { it.copy(appCleanup = AppCleanup(AppCleanupMode.MODEL, choice)) }
            }
            // Its own model chosen but not set up: what is used instead.
            val ownChoice = own.choice
            if (own.mode == AppCleanupMode.MODEL && ownChoice != null && !CleanupRouting.ready(settings, ownChoice, hasKeyFor)) {
                Hint(
                    stringResource(if (decision.choice != null) R.string.style_own_not_ready_default else R.string.style_own_not_ready_none),
                    AmberPalette.Error,
                )
            }
            if (decision.choice == null && profile.style != WritingStyle.AS_SPOKEN) {
                Hint(stringResource(R.string.style_needs_cleanup), AmberPalette.Primary)
            }
            CleanupLeavesPhoneHint(
                routing.engine?.trustLevelForDisplay(),
                settings.cleanupEngine(decision.choice)?.trustLevelForDisplay(),
            )
        }

        DangerAction(stringResource(R.string.style_remove, profile.label)) {
            onUpdateSettings { it.copy(profiles = it.profiles.filterNot { p -> p.packageName == profile.packageName }) }
            onRemoved()
        }
    }
}

/**
 * An app's style and its own instruction, read and saved the same way on the app's page and in
 * the quick menu's "Style here" window ([app.amethyst.ui.QuickStyleActivity]): a style at once,
 * the instruction [INSTRUCTION_SAVE_MS] after typing stops and when this leaves the screen.
 * [onEdit] changes the profile only while it is still there. [chips]: the styles as a row of
 * chips (the window) rather than a list with a note each (the page).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StyleAndInstruction(
    profile: AppProfile,
    onEdit: ((AppProfile) -> AppProfile) -> Unit,
    chips: Boolean,
    instructionModifier: Modifier = Modifier,
) {
    var instruction by remember(profile.packageName) { mutableStateOf(profile.instruction.orEmpty()) }

    fun persistInstruction() {
        val typed = instruction.trim().ifEmpty { null }
        if (typed != profile.instruction) onEdit { it.copy(instruction = typed) }
    }

    if (chips) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WritingStyle.entries.forEach { option ->
                ChoiceChip(option.label(), note = null, on = option == profile.style) {
                    if (option != profile.style) onEdit { it.copy(style = option) }
                }
            }
        }
    } else {
        WritingStyle.entries.forEach { option ->
            RadioRow(option.label(), option.note(), selected = option == profile.style, onSelect = {
                onEdit { it.copy(style = option) }
            })
        }
    }
    OutlinedTextField(
        value = instruction,
        onValueChange = { instruction = it.take(ChatCleanup.INSTRUCTION_CHARS) },
        label = { Text(stringResource(R.string.style_instruction)) },
        placeholder = { Text(stringResource(R.string.style_instruction_placeholder)) },
        supportingText = { Text(stringResource(R.string.char_count, instruction.length, ChatCleanup.INSTRUCTION_CHARS)) },
        minLines = 2,
        maxLines = 8,
        shape = AmberShapes.Inner,
        colors = fieldColors(),
        modifier = instructionModifier.fillMaxWidth(),
    )

    // Saved a moment after typing stops: Home and a dictation right away, or a rotation, must
    // not leave the old instruction in use.
    LaunchedEffect(instruction) {
        delay(INSTRUCTION_SAVE_MS)
        persistInstruction()
    }
    // Typed an instruction and went back (or closed the window) at once: keep it, as the engine page does.
    val latestPersist by rememberUpdatedState(::persistInstruction)
    DisposableEffect(profile.packageName) {
        onDispose { latestPersist() }
    }
}

/**
 * Who hears a dictation in this app: the speech recognition for every app, or one of those set
 * up. One of its own that cannot hear now is marked, and the page says what is used instead.
 */
@Composable
private fun RecognitionChoices(
    profile: AppProfile,
    settings: AmethystSettings,
    routing: EngineRouting.Decision,
    hasKeyFor: (String?) -> Boolean,
    modelReady: (EngineConfig) -> Boolean,
    onChoose: (AppEngine) -> Unit,
) {
    val own = profile.appEngine
    SectionCard(title = stringResource(R.string.settings_recognition), subtitle = stringResource(R.string.style_recognition_subtitle, profile.label)) {
        RadioRow(
            stringResource(R.string.style_recognition_default),
            settings.activeEngine?.let { engineName(it) } ?: stringResource(R.string.style_recognition_none),
            selected = own.mode == AppEngineMode.DEFAULT,
            onSelect = { onChoose(AppEngine()) },
        )
        settings.engines.forEach { engine ->
            val usable = EngineRouting.notUsable(engine, hasKeyFor, modelReady) == null
            RadioRow(
                engineName(engine),
                if (usable) engine.trustLevelForDisplay().badgeLabel() else stringResource(R.string.style_recognition_engine_not_ready),
                selected = own.mode == AppEngineMode.ENGINE && own.engineId == engine.id,
                onSelect = { onChoose(AppEngine(AppEngineMode.ENGINE, engine.id)) },
                noteColor = if (usable) AmberPalette.Muted else AmberPalette.Primary,
            )
        }
        when {
            routing.passedOver == EngineRouting.NotUsable.REMOVED ->
                Hint(stringResource(R.string.style_recognition_removed), AmberPalette.Error)
            routing.passedOver != null -> {
                val name = settings.engines.firstOrNull { it.id == own.engineId }?.let { engineName(it) } ?: routing.ownName.orEmpty()
                Hint(stringResource(R.string.style_recognition_not_ready, name), AmberPalette.Error)
            }
        }
    }
}
