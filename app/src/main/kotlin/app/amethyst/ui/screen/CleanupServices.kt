package app.amethyst.ui.screen

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.Texts
import app.amethyst.core.engine.AmberMachine
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.EnginePresets.ModelChoice
import app.amethyst.core.engine.ModelList
import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.onboarding.CleanupService
import app.amethyst.core.onboarding.CleanupServices
import app.amethyst.core.onboarding.KeyVerdict
import app.amethyst.core.onboarding.ServiceAnswer
import app.amethyst.core.onboarding.ServiceFields
import app.amethyst.core.onboarding.ServicePlan
import app.amethyst.core.onboarding.ServiceRow
import app.amethyst.core.onboarding.ServiceSetup
import app.amethyst.core.onboarding.ServiceSlot
import app.amethyst.core.onboarding.ServiceState
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.EngineConfig
import app.amethyst.data.PhoneLocales
import app.amethyst.ui.AmberIcons
import app.amethyst.ui.ChevronRight
import app.amethyst.ui.ConnectionTester
import app.amethyst.ui.GhostButton
import app.amethyst.ui.GroupLabel
import app.amethyst.ui.Hint
import app.amethyst.ui.PrimaryButton
import app.amethyst.ui.ProgressLine
import app.amethyst.ui.RadioRow
import app.amethyst.ui.TrustBadge
import app.amethyst.ui.catalog
import app.amethyst.ui.engineName
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes
import app.amethyst.ui.words
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What the cleanup's service list and its sheet need from the app; gathered by MainActivity. */
class CleanupAccess(
    val hasKeyFor: (alias: String?) -> Boolean,
    val onSaveKey: (alias: String, key: String) -> Unit,
    val onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
    /** Asks a server at an address, with a key typed for it, which models it has. */
    val fetchModels: suspend (baseUrl: String, key: String) -> ModelList = { _, _ -> ModelList.Failed("not asked") }, // English: a diagnostic detail, never shown
    /** The same for a server that is set up, with the key stored for it. */
    val fetchModelsOf: suspend (EngineConfig) -> ModelList = { ModelList.Failed("not asked") }, // English: a diagnostic detail, never shown
    /** The test dictation tidied by a model on a server, with a key typed just now and not stored yet. */
    val testKey: suspend (engine: EngineConfig, model: String, key: String) -> ConnectionTester.Result = { _, _, _ -> ConnectionTester.Result.Ok("") },
    /** Opens a server's own page (address, removal); null where there is no way there. */
    val onOpenEngine: ((engineId: String) -> Unit)? = null,
)

/**
 * Which sheet is open: a row's, or the one that adds an own server. [key] is a key typed in
 * another sheet and taken along ("this looks like a Groq key") - held in memory only, never in
 * saved state.
 */
sealed interface ServiceSheet {
    val key: String

    data class Of(val rowId: String, override val key: String = "") : ServiceSheet
    data class NewServer(override val key: String = "") : ServiceSheet
}

/**
 * The list's rows for the settings as they are, counted again once a key is stored: a key
 * changes what a row says without the settings changing (the same server, the same model).
 * One per page, so whatever else the page says about a row says the same as the list.
 */
internal class ServiceRows(val rows: List<ServiceRow>, val keysSaved: Int, val onKeySaved: () -> Unit)

@Composable
internal fun rememberServiceRows(settings: AmethystSettings, access: CleanupAccess): ServiceRows {
    var keysSaved by remember { mutableIntStateOf(0) }
    val rows = remember(settings, keysSaved) { CleanupServices.rows(settings, access.hasKeyFor) }
    return ServiceRows(rows, keysSaved) { keysSaved++ }
}

/**
 * The cleanup's services, one row each - Tinfoil, Claude, Groq, OpenAI, every own server, and
 * "Add own server" ([CleanupServices.rows]). A row says where the text goes and how far it is
 * from cleaning up; the one [inUse] is marked. Tapping a row opens its sheet: key, model, Use.
 *
 * The same list serves the cleanup for every app, the introduction's step and one app's own
 * model: [write] puts a chosen service where that choice lives. [onWritten] gets the settings as
 * they will be - the store answers a moment later.
 */
@Composable
internal fun CleanupServiceList(
    settings: AmethystSettings,
    access: CleanupAccess,
    services: ServiceRows,
    inUse: CleanupChoice?,
    sheet: MutableState<ServiceSheet?>,
    write: (AmethystSettings, ServiceSetup) -> AmethystSettings,
    onWritten: (AmethystSettings) -> Unit = {},
) {
    val rows = services.rows
    // A key's test takes a moment: Use goes by the settings as they are when it ends.
    val latest by rememberUpdatedState(settings)
    rows.forEach { row -> ServiceLine(row, inUse) { sheet.value = ServiceSheet.Of(row.id) } }
    AddServerLine { sheet.value = ServiceSheet.NewServer() }

    val open = sheet.value ?: return
    // The row as last seen: Use gives a new service its server, and with it another id, while
    // the sheet is still sliding away.
    val seen = remember(open) { arrayOfNulls<ServiceRow>(1) }
    (open as? ServiceSheet.Of)?.let { of -> rows.firstOrNull { it.id == of.rowId } }?.let { seen[0] = it }
    val row = seen[0]
    if (open is ServiceSheet.Of && row == null) {
        // Its server is gone: nothing to show.
        LaunchedEffect(open) { sheet.value = null }
        return
    }
    val switch: (ServiceSlot, String) -> Unit = { slot, key ->
        sheet.value = rows.firstOrNull { it.slot == slot }?.let { ServiceSheet.Of(it.id, key) }
    }
    SheetFrame(onDismiss = { sheet.value = null }) { close ->
        val use: (ServiceSetup, String?) -> Unit = { setup, key ->
            // The key only with a write that applies: a server removed or pointed elsewhere
            // meanwhile gets neither ([CleanupServices.added]).
            if (CleanupServices.added(latest, setup) != null) {
                if (key != null) setup.engine.apiKeyAlias?.let { access.onSaveKey(it, key) }
                access.onUpdateSettings { write(it, setup) }
                onWritten(write(latest, setup))
                services.onKeySaved()
            }
            close()
        }
        key(open) {
            if (row != null) {
                // Its own page for an own server (address, removal), and for any service that
                // only that page can put right.
                val editable = row.slot == ServiceSlot.SERVER || row.state == ServiceState.NEEDS_SETUP
                val openEngine = access.onOpenEngine?.takeIf { editable }
                RowSheetBody(
                    row = row,
                    settings = settings,
                    access = access,
                    inUse = inUse,
                    carriedKey = open.key,
                    onSwitch = switch,
                    onUse = use,
                    onEdit = row.engine?.let { engine -> openEngine?.let { { sheet.value = null; it(engine.id) } } },
                )
            } else {
                NewServerSheetBody(settings = settings, access = access, carriedKey = open.key, onSwitch = switch, onUse = use)
            }
        }
    }
}

/** Where a row's text goes: its server's, or - before it is set up - what the service is. */
internal fun ServiceRow.trust(): TrustLevel =
    engine?.trustLevelForDisplay() ?: if (slot == ServiceSlot.TINFOIL) TrustLevel.ENCLAVE_VERIFIED else TrustLevel.CLOUD

/** A row's name: the service's, or an own server's as every page names it. */
@Composable
internal fun ServiceRow.name(): String {
    val own = engine?.takeIf { slot == ServiceSlot.SERVER }
    return if (own != null) engineName(own) else catalog(provider.name)
}

/** How far a row is from cleaning up, in a word or two. */
@Composable
internal fun ServiceRow.stateWords(): String = stringResource(
    when (state) {
        ServiceState.READY -> R.string.cleanup_ready
        ServiceState.NEEDS_KEY -> R.string.cleanup_needs_key
        ServiceState.NEEDS_SETUP -> R.string.cleanup_set_up_first
    },
)

@Composable
private fun ServiceLine(row: ServiceRow, inUse: CleanupChoice?, onClick: () -> Unit) {
    val chosen = inUse?.takeIf { row.engine != null && it.engineId == row.engine?.id }
    val ready = row.state == ServiceState.READY
    Row(
        Modifier
            .fillMaxWidth()
            .clip(AmberShapes.Inner)
            .selectable(selected = chosen != null, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RadioButton(
            selected = chosen != null,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = AmberPalette.Primary, unselectedColor = AmberPalette.Outline),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.name(), style = MaterialTheme.typography.bodyLarge, color = AmberPalette.Text)
            // The one in use says its model; the others how far they are from being used.
            if (chosen != null) {
                val model = row.provider.cleanupModel(chosen.model)
                Hint(model?.label ?: chosen.model, AmberPalette.Primary)
                // What the model is like and costs, so the choice reads without opening the sheet.
                model?.note?.takeIf { it.isNotBlank() }?.let { Hint(catalog(it)) }
                if (ready) TrustBadge(row.trust()) else Hint(row.stateWords(), AmberPalette.Error)
            } else {
                TrustBadge(row.trust(), detail = row.stateWords())
            }
        }
        ChevronRight()
    }
}

@Composable
private fun AddServerLine(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(AmberShapes.Inner)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // As wide as a row's radio mark, so the names line up.
        Icon(AmberIcons.Add, contentDescription = null, tint = AmberPalette.Primary, modifier = Modifier.size(24.dp).padding(2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.cleanup_add_server), style = MaterialTheme.typography.bodyLarge, color = AmberPalette.Text)
            Hint(stringResource(R.string.cleanup_add_server_note))
        }
        ChevronRight()
    }
}

/**
 * The sheet itself: Amber's surface, rising from the bottom over a dimmed page. [content] gets
 * the way to close it from inside - it slides away first, then [onDismiss] takes it off the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetFrame(onDismiss: () -> Unit, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = AmberPalette.Surface,
        contentColor = AmberPalette.Text,
        scrimColor = AmberPalette.Scrim,
        dragHandle = { BottomSheetDefaults.DragHandle(color = AmberPalette.Outline) },
    ) { content(close) }
}

/** A sheet's content over its button: the content scrolls when it must, the button stays in reach. */
@Composable
private fun SheetBody(buttons: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = buttons)
    }
}

@Composable
private fun SheetHeader(name: String, trust: TrustLevel?, lead: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(name, style = MaterialTheme.typography.titleLarge, color = AmberPalette.Text)
        if (trust != null) TrustBadge(trust)
        Text(lead, style = MaterialTheme.typography.bodyMedium, color = AmberPalette.Muted)
    }
}

/** Where a sheet's models come from. */
private sealed interface ModelSource {
    /** The service's own list, or an Amber's: names, notes, a recommended one. */
    data class Listed(val models: List<ModelChoice>, val by: AmberMachine?) : ModelSource

    /** Another server's ids, searched when there are many. */
    data class Ids(val ids: List<String>) : ModelSource

    /** No list: the name is typed. [answered] says whether the server answered at all; null when it was not asked. */
    data class Typed(val answered: Boolean?) : ModelSource

    data object Asking : ModelSource

    /** The service offers no cleanup model right now. */
    data object None : ModelSource
}

/**
 * The sheet of a service in the list: its key - stored, or typed here - and its models, the one
 * in use or the recommended one chosen. Use hands the setup to [onUse], with the key when one
 * was typed; a typed key for a known service is tested first, and a failure is said with "Use
 * anyway" beside it.
 */
@Composable
internal fun RowSheetBody(
    row: ServiceRow,
    settings: AmethystSettings,
    access: CleanupAccess,
    inUse: CleanupChoice?,
    carriedKey: String,
    onSwitch: (ServiceSlot, key: String) -> Unit,
    onUse: (ServiceSetup, key: String?) -> Unit,
    onEdit: (() -> Unit)?,
    // For the screenshots: an own server's answer, a failed test.
    initialAnswer: ServiceAnswer? = null,
    initialFailure: String? = null,
) {
    val engine = row.engine
    // Not saveable: a key must never land in the saved-state bundle.
    var key by remember { mutableStateOf(carriedKey) }
    // The field shows where a key is wanted: none stored at a service that needs one, or one
    // carried over. An own server that works without one keeps it a tap away.
    var editKey by remember { mutableStateOf(carriedKey.isNotBlank() || (!row.keyStored && (row.keyRequired || row.state != ServiceState.READY))) }
    var model by remember { mutableStateOf(CleanupServices.preselected(row, inUse).orEmpty()) }
    var query by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf(initialAnswer) }
    var asking by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf(initialFailure) }
    // Used once: a second tap must not write twice.
    var used by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val context = LocalContext.current

    val typed = key.trim().takeIf { editKey && it.isNotEmpty() }
    val verdict = if (editKey) CleanupServices.keyVerdict(row.slot, key) else KeyVerdict.Fine

    // An own server that lists nothing itself is asked for its models: with its stored key as
    // the sheet opens, and again with a key typed here - for this very address - when asked to.
    val asksServer = engine != null && !row.provider.hasModelList
    fun ask(withKey: String?) {
        val server = engine ?: return
        if (asking) return
        val address = server.baseUrl.orEmpty().trim()
        asking = true
        scope.launch {
            val said = try {
                CleanupService.answer(address, if (withKey != null) access.fetchModels(address, withKey) else access.fetchModelsOf(server))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ServiceAnswer.Type(address, answered = false)
            } finally {
                asking = false
            }
            answer = said
            if (said is ServiceAnswer.Ready && model.isBlank()) model = said.plan.cleanupModel
        }
    }
    LaunchedEffect(row.id) {
        if (asksServer && row.state == ServiceState.READY && answer == null) ask(withKey = null)
    }

    val fresh = (answer as? ServiceAnswer.Ready)?.plan?.catalog
    val listed = remember(row) { CleanupServices.listed(row.provider) }
    val source: ModelSource = when (val said = answer) {
        is ServiceAnswer.Ready -> fresh?.let { ModelSource.Listed(CleanupServices.listed(ServerProviders.fromCatalog(it)), null) } ?: ModelSource.None
        ServiceAnswer.NoCleanupModel -> ModelSource.None
        is ServiceAnswer.Pick -> ModelSource.Ids(said.models)
        is ServiceAnswer.Type -> ModelSource.Typed(said.answered)
        null -> when {
            asking -> ModelSource.Asking
            row.provider.hasModelList && listed.isEmpty() -> ModelSource.None
            row.provider.hasModelList -> ModelSource.Listed(listed, row.provider.notOfferedBy(model, listed))
            else -> ModelSource.Typed(null)
        }
    }

    val keyOk = typed != null || row.state == ServiceState.READY
    val canUse = !testing && !used && row.state != ServiceState.NEEDS_SETUP && keyOk &&
        !CleanupServices.blocks(row.slot, verdict) && model.isNotBlank() && source != ModelSource.None && source != ModelSource.Asking

    fun use(test: Boolean) {
        if (!canUse) return
        val setup = CleanupServices.setup(settings, row, model, keyGiven = typed != null, PhoneLocales.tags(), catalog = fresh) ?: return
        // Only a key typed just now can be wrong in a way nothing here has seen. An own server
        // is not tested: its model list is the check, and a cold local model can take long.
        if (!test || typed == null || row.slot == ServiceSlot.SERVER) {
            used = true
            onUse(setup, typed)
            return
        }
        testing = true
        failure = null
        scope.launch {
            val result = try {
                access.testKey(setup.engine, setup.cleanupModel, typed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ConnectionTester.Result.Failed(context.getString(R.string.test_cleanup_failed))
            } finally {
                testing = false
            }
            when (result) {
                is ConnectionTester.Result.Ok -> {
                    used = true
                    onUse(setup, typed)
                }
                is ConnectionTester.Result.Failed -> failure = result.message
            }
        }
    }

    SheetBody(
        buttons = {
            if (testing) {
                ProgressLine(null)
                Hint(stringResource(R.string.cleanup_testing))
            }
            failure?.let { why ->
                Hint(why, AmberPalette.Error)
                GhostButton(stringResource(R.string.cleanup_use_anyway), { use(test = false) }, enabled = canUse)
            }
            PrimaryButton(stringResource(R.string.use), { use(test = true) }, Modifier.fillMaxWidth(), enabled = canUse)
        },
    ) {
        SheetHeader(
            name = row.name(),
            trust = row.trust(),
            lead = when (row.slot) {
                ServiceSlot.TINFOIL -> stringResource(R.string.cleanup_sheet_tinfoil)
                ServiceSlot.CLAUDE -> stringResource(R.string.cleanup_sheet_cloud, "Anthropic") // English: a name
                ServiceSlot.GROQ, ServiceSlot.OPENAI -> stringResource(R.string.cleanup_sheet_cloud, row.provider.name)
                // The host only: an Amber PC's address can carry its key.
                ServiceSlot.SERVER -> stringResource(R.string.cleanup_sheet_own, EnginePresets.hostOf(engine?.baseUrl) ?: row.name())
            },
        )

        if (row.state == ServiceState.NEEDS_SETUP) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                row.issues.forEach { Hint(words(it), AmberPalette.Error) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!editKey) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (row.keyStored) {
                            Hint(stringResource(R.string.cleanup_key_stored), AmberPalette.TrustOnDevice, Modifier.weight(1f))
                        } else {
                            Hint(stringResource(R.string.cleanup_key_none), modifier = Modifier.weight(1f))
                        }
                        GhostButton(stringResource(if (row.keyStored) R.string.cleanup_key_change else R.string.add), { editKey = true }, enabled = !testing)
                    }
                } else {
                    KeyField(
                        value = key,
                        label = stringResource(if (row.keyRequired) R.string.engine_api_key else R.string.cleanup_key_optional),
                        enabled = !testing,
                    ) {
                        key = it
                        failure = null
                    }
                    val elsewhere = row.keyElsewhere
                    when {
                        verdict == KeyVerdict.NotClaude -> Hint(stringResource(R.string.cleanup_claude_not_a_key), AmberPalette.Error)
                        verdict is KeyVerdict.Other -> OtherService(verdict.slot, R.string.cleanup_key_other, enabled = !testing) { onSwitch(verdict.slot, key) }
                        // Why the key that is there does not count, until one is typed.
                        elsewhere != null && typed == null -> Hint(words(elsewhere), AmberPalette.Primary)
                        else -> Hint(stringResource(R.string.engine_key_stored))
                    }
                    if (!row.keyStored) row.provider.keyPage?.let { page ->
                        GhostButton(
                            stringResource(R.string.cleanup_get_key, EnginePresets.hostOf(page).orEmpty()),
                            {
                                runCatching { uri.openUri(page) }.onFailure {
                                    Toast.makeText(context, context.getString(R.string.no_browser, page), Toast.LENGTH_LONG).show()
                                }
                            },
                        )
                    }
                    // A list that did not come with the stored key may come with this one.
                    if (asksServer && typed != null && !asking && (answer == null || answer is ServiceAnswer.Type)) {
                        GhostButton(stringResource(R.string.cleanup_ask_again), { ask(withKey = typed) }, enabled = !testing)
                    }
                }
            }
            Models(
                source = source,
                model = model,
                query = query,
                enabled = !testing,
                service = row.name(),
                onQuery = { query = it },
                onModel = {
                    model = it
                    failure = null
                },
            )
        }
        if (onEdit != null) GhostButton(stringResource(R.string.cleanup_edit_server), onEdit, enabled = !testing)
    }
}

/**
 * The sheet that adds an own server: its address - and its key, if it wants one - then Connect,
 * which asks the server for its models: an Amber PC's or Amber Server's own, another server's
 * list to pick from, or the model's name typed. An Amber PC's pairing link or its address with
 * the key in it goes apart ([ServiceFields]).
 */
@Composable
internal fun NewServerSheetBody(
    settings: AmethystSettings,
    access: CleanupAccess,
    carriedKey: String,
    onSwitch: (ServiceSlot, key: String) -> Unit,
    onUse: (ServiceSetup, key: String?) -> Unit,
    // For the screenshots: the fields filled and the server's answer.
    initialFields: ServiceFields? = null,
    initialAnswer: ServiceAnswer? = null,
) {
    // Not saveable: a key must never land in the saved-state bundle, nor the address, which
    // can carry an Amber PC's key in its path.
    var fields by remember { mutableStateOf(initialFields ?: ServiceFields(key = carriedKey)) }
    var answer by remember { mutableStateOf(initialAnswer) }
    var model by remember { mutableStateOf((initialAnswer as? ServiceAnswer.Ready)?.plan?.cleanupModel.orEmpty()) }
    var query by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var used by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val problems = CleanupService.addressProblems(fields.address)
    val known = CleanupServices.slotAt(fields.address)
    val verdict = CleanupServices.keyVerdict(ServiceSlot.SERVER, fields.key)

    // Any change to the fields makes an earlier answer void.
    fun edit(changed: ServiceFields) {
        fields = changed
        answer = null
        model = ""
        query = ""
    }

    fun connect() {
        tried = true
        if (asking || problems.isNotEmpty() || known != null) return
        val asked = fields
        asking = true
        scope.launch {
            // fetchModels never throws but to cancel; should it all the same, it is no answer.
            val said = try {
                CleanupService.answer(asked.address, access.fetchModels(asked.address.trim(), asked.key.trim()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ServiceAnswer.Type(asked.address.trim(), answered = false)
            } finally {
                asking = false
            }
            // The answer counts only for what was asked.
            if (fields != asked) return@launch
            answer = said
            if (said is ServiceAnswer.Ready) model = said.plan.cleanupModel
        }
    }

    val plan: ServicePlan? = when (val said = answer) {
        is ServiceAnswer.Ready -> model.trim().takeIf { it.isNotEmpty() }?.let { said.plan.copy(cleanupModel = it) }
        is ServiceAnswer.Pick -> CleanupService.picked(said, model)
        is ServiceAnswer.Type -> CleanupService.chosen(said.baseUrl, model)
        ServiceAnswer.NoCleanupModel, null -> null
    }
    val source: ModelSource? = when (val said = answer) {
        is ServiceAnswer.Ready -> said.plan.catalog?.let { ModelSource.Listed(CleanupServices.listed(ServerProviders.fromCatalog(it)), null) }
        ServiceAnswer.NoCleanupModel -> ModelSource.None
        is ServiceAnswer.Pick -> ModelSource.Ids(said.models)
        is ServiceAnswer.Type -> ModelSource.Typed(said.answered)
        null -> null
    }

    fun use() {
        val chosen = plan ?: return
        if (used || asking) return
        used = true
        val key = fields.key.trim().takeIf { it.isNotEmpty() }
        onUse(CleanupService.setup(settings, chosen, PhoneLocales.tags(), keyGiven = key != null), key)
    }

    SheetBody(
        buttons = {
            if (asking) {
                ProgressLine(null)
                Hint(stringResource(R.string.cleanup_asking))
            }
            if (source == null || source == ModelSource.None) {
                PrimaryButton(stringResource(R.string.cleanup_connect), ::connect, Modifier.fillMaxWidth(), enabled = !asking && known == null && fields.address.isNotBlank())
            } else {
                PrimaryButton(stringResource(R.string.use), ::use, Modifier.fillMaxWidth(), enabled = plan != null && !used)
            }
        },
    ) {
        SheetHeader(name = catalog(ServerProviders.CUSTOM.name), trust = null, lead = stringResource(R.string.cleanup_sheet_new_server))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = fields.address,
                onValueChange = { edit(fields.withAddress(it)) },
                enabled = !asking,
                label = { Text(stringResource(R.string.engine_address)) },
                singleLine = true,
                shape = AmberShapes.Inner,
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            when {
                // A known service has a row of its own: its address, its models, its key's test.
                known != null -> OtherService(known, R.string.cleanup_known_address, enabled = !asking) { onSwitch(known, fields.key) }
                (fields.address.isNotBlank() || tried) && problems.isNotEmpty() -> problems.forEach { Hint(words(it), AmberPalette.Error) }
                // The example under the field, never in it: what stands in a field reads as filled in.
                else -> Hint(stringResource(R.string.cleanup_address_example))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyField(fields.key, stringResource(R.string.cleanup_key_optional), enabled = !asking) { edit(fields.withKey(it)) }
            if (verdict is KeyVerdict.Other && known == null) {
                OtherService(verdict.slot, R.string.cleanup_key_other, enabled = !asking) { onSwitch(verdict.slot, fields.key) }
            } else {
                Hint(stringResource(R.string.engine_key_stored))
            }
        }
        if (source != null) {
            Models(
                source = source,
                model = model,
                query = query,
                enabled = !asking,
                service = catalog(ServerProviders.CUSTOM.name),
                onQuery = { query = it },
                onModel = { model = it },
            )
        }
    }
}

/** "This looks like a Groq key." and the way over to Groq's own sheet. */
@Composable
private fun OtherService(slot: ServiceSlot, text: Int, enabled: Boolean, onSwitch: () -> Unit) {
    val name = catalog(CleanupServices.providerOf(slot).name)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Hint(stringResource(text, name), AmberPalette.Primary, Modifier.weight(1f))
        GhostButton(stringResource(R.string.cleanup_switch_to, name), onSwitch, enabled = enabled)
    }
}

/** A sheet's models, whichever way they are known ([ModelSource]). */
@Composable
private fun Models(
    source: ModelSource,
    model: String,
    query: String,
    enabled: Boolean,
    service: String,
    onQuery: (String) -> Unit,
    onModel: (String) -> Unit,
) {
    val pick: (String) -> Unit = { if (enabled) onModel(it) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GroupLabel(stringResource(R.string.model_title))
        when (source) {
            is ModelSource.Listed -> {
                ModelPicker(
                    choices = source.models,
                    selected = model.takeIf { it.isNotBlank() },
                    noneLabel = null,
                    notOffered = source.by?.let { Texts.notOffered(LocalContext.current, it) },
                    onSelect = { id -> id?.let(pick) },
                )
                // A model of the user's own, typed earlier: still the one chosen.
                if (source.by == null && model.isNotBlank() && source.models.none { it.id == model }) {
                    RadioRow(model, null, selected = true, onSelect = {})
                }
            }
            is ModelSource.Ids -> {
                // OpenRouter lists hundreds: a long list is searched, a few rows at most.
                if (CleanupService.searchable(source.ids)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQuery,
                        label = { Text(stringResource(R.string.cleanup_step_search)) },
                        singleLine = true,
                        shape = AmberShapes.Inner,
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Hint(stringResource(R.string.cleanup_step_search_models, source.ids.size))
                }
                val shown = CleanupService.shownModels(source.ids, query)
                Column {
                    shown.forEach { id -> RadioRow(id, null, selected = model == id, onSelect = { pick(id) }) }
                    // Picked, then searched past - or in use and no longer listed: it stays in view as chosen.
                    if (model.isNotBlank() && model !in shown) RadioRow(model, null, selected = true, onSelect = {})
                }
            }
            is ModelSource.Typed -> {
                OutlinedTextField(
                    value = model,
                    onValueChange = onModel,
                    enabled = enabled,
                    label = { Text(stringResource(R.string.cleanup_chat_model)) },
                    singleLine = true,
                    shape = AmberShapes.Inner,
                    colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                when (source.answered) {
                    true -> Hint(stringResource(R.string.cleanup_step_type_model))
                    false -> Hint(stringResource(R.string.cleanup_step_no_answer), AmberPalette.Error)
                    null -> Hint(stringResource(R.string.cleanup_model_example))
                }
            }
            ModelSource.Asking -> {
                ProgressLine(null)
                Hint(stringResource(R.string.cleanup_asking))
            }
            ModelSource.None -> Hint(stringResource(R.string.cleanup_no_model, service), AmberPalette.Error)
        }
    }
}

/** A key, masked: off screenshots, recordings and other eyes. */
@Composable
private fun KeyField(value: String, label: String, enabled: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        shape = AmberShapes.Inner,
        colors = fieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}
