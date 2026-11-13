package app.amethyst.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.core.engine.CleanupRouting
import app.amethyst.core.engine.EngineRouting
import app.amethyst.core.engine.Servers
import app.amethyst.core.onboarding.CleanupServices
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppCleanup
import app.amethyst.core.settings.AppCleanupMode
import app.amethyst.core.settings.AppEngine
import app.amethyst.core.settings.AppEngineMode
import app.amethyst.core.settings.AppProfile
import app.amethyst.core.settings.AppProfiles
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.InstructionDraft
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
import app.amethyst.ui.Unfold
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
    /** Keys, settings and servers, for the cleanup's service list. */
    access: CleanupAccess,
    /** Whether a speech recognition's model is on the phone (true for one elsewhere). */
    modelReady: (EngineConfig) -> Boolean,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
    onRemoved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasKeyFor = access.hasKeyFor
    val services = rememberServiceRows(settings, access)
    // "Own model" tapped, none chosen yet: the list is open, the choice still as it was.
    var pickOwn by rememberSaveable { mutableStateOf(false) }
    val sheet = remember { mutableStateOf<ServiceSheet?>(null) }
    // Once per settings change rather than on every recomposition.
    val decision = remember(settings, services.keysSaved) { CleanupRouting.decide(settings, profile.packageName, null, hasKeyFor) }
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
                onSelect = {
                    pickOwn = false
                    edit { it.copy(appCleanup = AppCleanup()) }
                },
            )
            RadioRow(
                stringResource(R.string.off),
                stringResource(R.string.cleanup_off_note),
                selected = own.mode == AppCleanupMode.OFF,
                onSelect = {
                    pickOwn = false
                    edit { it.copy(appCleanup = AppCleanup(AppCleanupMode.OFF)) }
                },
            )
            // A model of its own: the same service list as Settings → Cleanup, unfolded here; a
            // service's sheet writes this app's choice.
            val ownChoice = own.choice.takeIf { own.mode == AppCleanupMode.MODEL }
            RadioRow(
                stringResource(R.string.style_own_model),
                settings.cleanupSummary(ownChoice) ?: stringResource(R.string.style_own_model_note),
                selected = own.mode == AppCleanupMode.MODEL,
                onSelect = { pickOwn = true },
            )
            Unfold(own.mode == AppCleanupMode.MODEL || pickOwn) {
                Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CleanupServiceList(
                        settings, access, services,
                        inUse = ownChoice,
                        sheet = sheet,
                        write = { all, setup -> CleanupServices.forApp(all, setup, profile.packageName) },
                    )
                }
            }
            // Its own model chosen but not set up: what is used instead.
            if (ownChoice != null && !CleanupRouting.ready(settings, ownChoice, hasKeyFor)) {
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
) {
    // Kept over a rotation; saves only what was typed here, so the page left open in Amber never
    // writes back what the window changed meanwhile, nor the other way round.
    var draft by rememberSaveable(profile.packageName, stateSaver = DraftSaver) { mutableStateOf(InstructionDraft.of(profile.instruction)) }

    fun persistInstruction() {
        val now = draft
        if (now.needsSave(profile.instruction)) onEdit { it.copy(instruction = now.instruction) }
        draft = now.saved()
    }

    // Changed elsewhere (the other editor, or this one's own save): followed while nothing is typed here.
    LaunchedEffect(profile.instruction) { draft = draft.stored(profile.instruction) }

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
        value = draft.text,
        onValueChange = { draft = draft.typed(it) },
        label = { Text(stringResource(R.string.style_instruction)) },
        placeholder = { Text(stringResource(R.string.style_instruction_placeholder)) },
        supportingText = { Text(stringResource(R.string.char_count, draft.text.length, ChatCleanup.INSTRUCTION_CHARS)) },
        minLines = 2,
        maxLines = 8,
        shape = AmberShapes.Inner,
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )

    // Saved a moment after typing stops: Home and a dictation right away, or a rotation, must
    // not leave the old instruction in use.
    val latestPersist by rememberUpdatedState(::persistInstruction)
    LaunchedEffect(draft.text) {
        delay(INSTRUCTION_SAVE_MS)
        latestPersist()
    }
    // Typed an instruction and went back (or closed the window) at once: keep it, as the engine page does.
    DisposableEffect(profile.packageName) {
        onDispose { latestPersist() }
    }
}

/** The draft over a rotation: its text and whether it was typed here. */
private val DraftSaver = listSaver<InstructionDraft, Any>(
    save = { listOf(it.text, it.edited) },
    restore = { InstructionDraft(it[0] as String, it[1] as Boolean) },
)

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
        // Not a server for the cleanup only: it recognizes nothing.
        Servers.recognizers(settings).forEach { engine ->
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
