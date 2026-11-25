package app.amethyst.ui.screen

import android.net.Uri
import android.os.Build
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.Texts
import app.amethyst.core.engine.AmberAddress
import app.amethyst.core.engine.AmberCatalog
import app.amethyst.core.engine.AmberCatalogs
import app.amethyst.core.engine.ConfigIssue
import app.amethyst.core.engine.EngineConfigValidator
import app.amethyst.core.engine.EngineAdvice
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.EnginePreset
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.EngineTrust
import app.amethyst.core.engine.FileProblem
import app.amethyst.core.engine.KeyBinding
import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.engine.ServerProvider
import app.amethyst.core.engine.Servers
import app.amethyst.core.engine.sherpa.ParakeetModel
import app.amethyst.core.engine.whisper.ModelSlots.SlotState
import app.amethyst.core.engine.whisper.WhisperModelSpec
import app.amethyst.core.engine.whisper.WhisperModels
import app.amethyst.core.privacy.AttestationResult
import app.amethyst.core.privacy.EnclaveProof
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.LanguageList
import app.amethyst.data.PhoneLocales
import app.amethyst.engine.AndroidOnDeviceEngine
import app.amethyst.engine.ParakeetState
import app.amethyst.engine.TinfoilVerifier
import app.amethyst.ui.ChoiceChip
import app.amethyst.ui.ConnectionTester
import app.amethyst.ui.ExpandChevron
import app.amethyst.ui.NavRow
import app.amethyst.ui.SettingsGroup
import app.amethyst.ui.Unfold
import app.amethyst.ui.foldState
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.LanguageChips
import app.amethyst.ui.PageGutter
import app.amethyst.ui.PrimaryButton
import app.amethyst.ui.ProgressLine
import app.amethyst.ui.RadioRow
import app.amethyst.ui.SectionCard
import app.amethyst.ui.TrustBadge
import app.amethyst.ui.catalog
import app.amethyst.ui.engineName
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes
import app.amethyst.ui.theme.placeExplanation
import app.amethyst.ui.words
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun EngineDetailScreen(
    config: EngineConfig,
    /** The models an Amber PC or Amber Server listed for this engine, if it is one. */
    catalog: AmberCatalog?,
    active: Boolean,
    storedKey: String?,
    whisperModels: Map<String, SlotState>,
    onSaveKey: (String, String) -> Unit,
    onSave: (EngineConfig) -> Unit,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
    onTest: suspend (EngineConfig) -> ConnectionTester.Result,
    onDownloadModel: (WhisperModelSpec) -> Unit,
    onImportModel: (Uri) -> Unit,
    onCancelModel: (String) -> Unit,
    onDeleteModel: (String) -> Unit,
    /** Newest first: what the Tinfoil verifier did lately. */
    enclaveLog: List<String>,
    /** Verifies the enclave behind a base URL afresh, bypassing the cache. */
    onVerifyEnclave: suspend (String) -> AttestationResult,
    parakeetState: ParakeetState,
    onDownloadParakeet: () -> Unit,
    onCancelParakeet: () -> Unit,
    onDeleteParakeet: () -> Unit,
    modifier: Modifier = Modifier,
    /** A server there for the cleanup only ([Servers.cleanupOnly]): no recognition from it until it has a speech model. */
    cleanupOnly: Boolean = false,
) {
    var baseUrl by remember(config.id) { mutableStateOf(config.baseUrl.orEmpty()) }
    var modelId by remember(config.id) { mutableStateOf(config.modelId.orEmpty()) }
    var language by remember(config.id) { mutableStateOf(config.languageTag.orEmpty()) }
    var apiKey by remember(config.id) { mutableStateOf(storedKey.orEmpty()) }
    // Typed or pasted here: only then does the key belong to the address now in the field. A key
    // given for another host stays there ([KeyBinding]) until it is entered again.
    var keyEdited by remember(config.id) { mutableStateOf(false) }
    // Something typed, picked, saved or tested here: only then is a missing field an error in
    // red. Before, a new server's page would open with errors for fields nobody had seen yet.
    var touched by remember(config.id) { mutableStateOf(false) }
    // At Groq's or OpenAI's own address the field stays folded until asked for.
    var otherAddress by remember(config.id) { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember(config.id) { mutableStateOf<ConnectionTester.Result?>(null) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val preset = if (Servers.isServer(config)) EnginePresets.SERVER else EnginePresets.byId(config.id)
    // A model on the phone is tested, not a connection: Whisper and Parakeet alike.
    val local = config.kind == EngineKind.WHISPER_CPP || config.kind == EngineKind.LOCAL_SHERPA

    fun edited(): EngineConfig = config.copy(
        baseUrl = baseUrl.trim().takeIf { it.isNotBlank() },
        modelId = modelId.trim().takeIf { it.isNotBlank() },
        languageTag = language.trim().takeIf { it.isNotBlank() },
    ).let { if (keyEdited && apiKey.isNotBlank()) KeyBinding.bind(it) else it }

    var confirmRemove by remember { mutableStateOf(false) }
    var removed by remember { mutableStateOf(false) }

    // The same chips on every page that has languages; saved at once, as a model pick is.
    val phone = remember { PhoneLocales.tags() }

    @Composable
    fun Languages() = LanguageChips(
        picked = LanguageList.parse(language, LanguageList.use(config.kind)),
        use = LanguageList.use(config.kind),
        defaults = LanguageList.defaults(config.kind, phone),
    ) { picked ->
        language = LanguageList.with(config, picked).languageTag.orEmpty()
        onSave(edited())
    }

    fun save() {
        config.apiKeyAlias?.let { alias -> onSaveKey(alias, apiKey) }
        onSave(edited())
    }

    /**
     * Saved before a test or "Use": the key stays with the address it is tested or used with -
     * changed after that, the address gets it only once it is typed again ([KeyBinding]).
     */
    fun persist() {
        touched = true
        save()
        keyEdited = false
    }

    /**
     * What was typed, saved as it is - a moment after typing stops and as the page goes. A key
     * typed here follows the address until a test or "Use": a pause in the middle of typing the
     * address must not tie it to half a host.
     */
    fun persistIfChanged() {
        if (removed) return
        if (edited() != config || apiKey != storedKey.orEmpty()) save()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PageGutter)
            .padding(bottom = 32.dp),
    ) {
        val problems = EngineConfigValidator.validate(edited(), hasApiKey = apiKey.isNotBlank())

        SectionCard(title = engineName(config)) {
            TrustBadge(config.trustLevelForDisplay())
            Hint(config.trustLevelForDisplay().placeExplanation())
            if (EngineTrust.unencrypted(edited())) {
                Hint(stringResource(R.string.engine_unencrypted), AmberPalette.Error)
            }
            if (preset?.note?.isNotBlank() == true) Hint(catalog(preset.note))

            // Without a speech model on purpose: said plainly, not as an error.
            val shown = if (cleanupOnly) problems.filterNot { it == ConfigIssue.ModelNameMissing || it is ConfigIssue.NoSpeechRecognition } else problems
            if (cleanupOnly) Hint(stringResource(R.string.recognition_cleanup_only))
            // Listed while typing, so the user is never left guessing which field the engine is
            // still waiting for - in red once something was typed. Nothing said once it is
            // complete: what is typed is saved by itself, as on every other page.
            shown.forEach { problem -> Hint(words(problem), if (touched) AmberPalette.Error else AmberPalette.Muted) }
            // Not before it is complete: made active half filled in, the next dictation fails.
            if (!active && !cleanupOnly) {
                PrimaryButton(
                    stringResource(R.string.engine_use_active),
                    {
                        persist()
                        onActivate()
                    },
                    enabled = shown.isEmpty(),
                )
            }
        }

        when (config.kind) {
            EngineKind.ANDROID_ON_DEVICE -> {
                SectionCard(title = stringResource(R.string.engine_configuration)) {
                    Hint(stringResource(R.string.engine_android_note))
                    val onDevice = remember {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                    }
                    if (!onDevice) {
                        Hint(stringResource(R.string.engine_android_cloud), AmberPalette.Primary)
                    }
                }
                SectionCard(title = stringResource(R.string.engine_language)) {
                    Hint(stringResource(R.string.engine_android_language))
                    Languages()
                }
            }

            EngineKind.WHISPER_CPP -> {
                WhisperModelsCard(
                    states = whisperModels,
                    active = active,
                    selectedId = modelId.ifBlank { WhisperModels.default.id },
                    onSelect = { id ->
                        modelId = id
                        onSave(edited())
                    },
                    onDownload = onDownloadModel,
                    onImport = onImportModel,
                    onCancel = onCancelModel,
                    onDelete = onDeleteModel,
                )
                SectionCard(title = stringResource(R.string.engine_languages)) {
                    Hint(stringResource(R.string.engine_whisper_languages))
                    Languages()
                    if (language.isBlank()) Hint(stringResource(R.string.whisper_none_picked))
                }
            }

            EngineKind.OPENAI_COMPATIBLE -> {
                // One backend for every server; the provider only decides what is offered. Taken
                // from the saved address, not the one being typed, so the choices do not change
                // mid-word - the same provider the factory and the statistics go by. An own
                // server that turned out to be an Amber PC or Server offers what it listed.
                val provider = remember(config.baseUrl, catalog) { ServerProviders.forUrl(config.baseUrl, catalog) }
                val fixedAddress = remember(config.id, config.baseUrl) { ServerProviders.addressFixed(config) }
                val listed = provider.hasModelList && provider.speechModels.isNotEmpty()
                // No speech model yet and the server's list just came: its default is saved
                // right away, so the engine is ready without a tap. A chosen model stays.
                LaunchedEffect(config.id, catalog) {
                    val pick = AmberCatalogs.speechModelToPreselect(modelId.ifBlank { config.modelId }, catalog)
                    if (pick != null) {
                        modelId = pick
                        onSave(config.copy(modelId = pick))
                    }
                }
                SectionCard(
                    title = stringResource(R.string.engine_connection),
                    subtitle = when {
                        fixedAddress -> "${catalog(provider.name)} · ${baseUrl.trim()}"
                        provider !== ServerProviders.CUSTOM -> stringResource(R.string.server_recognized, catalog(provider.name))
                        else -> stringResource(R.string.engine_connection_subtitle)
                    },
                ) {
                    if (!fixedAddress) {
                        ServerQuickPicks(
                            current = ServerProviders.forUrl(baseUrl.trim()),
                            onPick = { quick ->
                                touched = true
                                otherAddress = false
                                val pointed = Servers.pointAt(edited(), quick)
                                baseUrl = pointed.baseUrl.orEmpty()
                                modelId = pointed.modelId.orEmpty()
                                onSave(pointed)
                            },
                            // Back to nothing: an own address to type, no model of the service left.
                            onOwn = {
                                touched = true
                                baseUrl = ""
                                modelId = ""
                                onSave(edited())
                            },
                        )
                        // Typed into, the field stays, also once the text is a known address.
                        if (otherAddress || !Servers.atKnownAddress(baseUrl)) OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { typed ->
                                touched = true
                                otherAddress = true
                                // An Amber address with the key in it, or a pairing link: the two
                                // go into their own fields, as with OpenAI.
                                val split = AmberAddress.split(typed).takeIf { config.apiKeyAlias != null }
                                if (split != null) {
                                    baseUrl = split.baseUrl
                                    apiKey = split.key
                                    keyEdited = true
                                } else {
                                    baseUrl = typed
                                }
                            },
                            // No example in the field: what stands in a field reads as filled in. It is in the note below.
                            label = { Text(stringResource(R.string.engine_address)) },
                            singleLine = true,
                            shape = AmberShapes.Inner,
                            colors = fieldColors(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (otherAddress || !Servers.atKnownAddress(baseUrl)) {
                            Hint(stringResource(R.string.engine_address_hint))
                        } else {
                            GhostButton(stringResource(R.string.engine_other_address), { otherAddress = true })
                        }
                        // Typed only while the server lists no speech model to pick, so an empty
                        // list never leaves the engine without one.
                        if (listed) {
                            if (catalog != null && provider.listsItself) {
                                Hint(
                                    stringResource(
                                        R.string.engine_models_below,
                                        listOf(catalog.name, catalog.version).filter { it.isNotBlank() }.joinToString(" "),
                                    ),
                                    AmberPalette.TrustOnDevice,
                                )
                            }
                        } else {
                            OutlinedTextField(
                                value = modelId,
                                onValueChange = {
                                    touched = true
                                    modelId = it
                                },
                                label = { Text(stringResource(R.string.engine_speech_model)) },
                                placeholder = { Text("Systran/faster-whisper-large-v3") },
                                singleLine = true,
                                shape = AmberShapes.Inner,
                                colors = fieldColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    if (config.apiKeyAlias != null) {
                        val optional = !Servers.keyRequired(edited())
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = {
                                touched = true
                                apiKey = it
                                keyEdited = true
                                // A Groq or OpenAI key with no address yet: its service's address and model.
                                if (baseUrl.isBlank()) Servers.quickForKey(it)?.let { quick ->
                                    val pointed = Servers.pointAt(edited(), quick)
                                    baseUrl = pointed.baseUrl.orEmpty()
                                    modelId = pointed.modelId.orEmpty()
                                }
                            },
                            label = { Text(stringResource(if (optional) R.string.server_key_optional else R.string.engine_api_key)) },
                            singleLine = true,
                            shape = AmberShapes.Inner,
                            colors = fieldColors(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            // Masked: off screenshots, recordings and other services' eyes.
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Hint(stringResource(R.string.engine_key_stored))
                        // Given for another host: it stays there; this address gets none until typed again.
                        if (!keyEdited && apiKey.isNotBlank() && config.keyHost != null && !KeyBinding.allows(edited())) {
                            Hint(stringResource(R.string.server_key_elsewhere, KeyBinding.shown(edited())), AmberPalette.Error)
                        }
                    }
                }
                // Claude recognizes no speech: no speech model, no languages to choose.
                if (provider.hasModelList && provider.recognizes) {
                    SectionCard(
                        title = stringResource(R.string.engine_speech_model),
                        subtitle = stringResource(R.string.engine_speech_model_subtitle, catalog(provider.name)),
                    ) {
                        ModelPicker(
                            choices = provider.speechModels,
                            selected = modelId.trim(),
                            noneLabel = null,
                            notOffered = provider.notOfferedBy(modelId, provider.speechModels)?.let { Texts.notOffered(context, it) },
                            onSelect = { id ->
                                modelId = id ?: modelId
                                onSave(edited())
                            },
                        )
                        if (provider.speechModels.isEmpty()) Hint(stringResource(R.string.engine_no_speech_model, catalog(provider.name)))
                    }
                }
                // An Amber server takes several languages and picks among them; a plain API
                // takes one code, and detects by itself for several or none.
                val amber = catalog?.takeIf { AmberCatalogs.isCandidate(config) }
                if (provider.recognizes) SectionCard(title = stringResource(R.string.engine_languages)) {
                    if (amber != null) {
                        val machine = Texts.machine(context, amber.machine)
                        Hint(stringResource(R.string.engine_amber_languages, machine))
                        Languages()
                        if (language.isBlank()) Hint(stringResource(R.string.engine_amber_none_picked, machine))
                    } else {
                        Hint(stringResource(R.string.engine_server_languages))
                        Languages()
                    }
                }
                Hint(stringResource(R.string.engine_cleanup_elsewhere))
                if (provider == ServerProviders.TINFOIL) EnclaveSection(baseUrl.trim(), enclaveLog, onVerifyEnclave)
            }

            EngineKind.LOCAL_SHERPA -> {
                ParakeetCard(parakeetState, inUse = active, onDownloadParakeet, onCancelParakeet, onDeleteParakeet)
                // No chips: Parakeet is given no language.
                SectionCard(title = stringResource(R.string.engine_languages)) {
                    Hint(stringResource(R.string.engine_parakeet_languages))
                }
            }

            EngineKind.ELEVENLABS_REALTIME -> SectionCard(title = stringResource(R.string.engine_not_supported)) {
                Hint(stringResource(R.string.engine_elevenlabs))
            }
        }

        SectionCard(title = stringResource(R.string.engine_check)) {
            PrimaryButton(
                text = stringResource(if (local) R.string.engine_test_model else R.string.engine_test_connection),
                enabled = !testing,
                onClick = {
                    persist()
                    testing = true
                    testResult = null
                    scope.launch {
                        testResult = onTest(edited())
                        testing = false
                    }
                },
            )

            if (config.kind == EngineKind.OPENAI_COMPATIBLE) {
                val price = ServerProviders.forUrl(baseUrl).speechModel(modelId.trim())?.usdPerRequest ?: 0.0
                if (price > 0) Hint(stringResource(R.string.engine_test_billed, String.format(Texts.locale(context), "%.2f", price)))
            }
            AnimatedVisibility(visible = testing, enter = fadeIn(), exit = fadeOut()) {
                ProgressLine(fraction = null)
            }

            when (val result = testResult) {
                null -> Unit
                is ConnectionTester.Result.Ok -> Hint(result.detail, AmberPalette.TrustOnDevice)
                is ConnectionTester.Result.Failed -> Hint(result.message, AmberPalette.Error)
            }
        }

        if (config.id != EnginePresets.ANDROID_ON_DEVICE.id) {
            GhostButton(stringResource(R.string.engine_remove), { confirmRemove = true }, color = AmberPalette.Error)
        }
    }

    // Saved a moment after typing stops, as an app's own instruction is, and as the page goes:
    // there is no Save button to forget.
    val latestPersist by rememberUpdatedState(::persistIfChanged)
    LaunchedEffect(config.id, baseUrl, modelId, apiKey) {
        delay(FIELD_SAVE_MS)
        latestPersist()
    }
    DisposableEffect(config.id) {
        onDispose { latestPersist() }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            containerColor = AmberPalette.Surface,
            shape = AmberShapes.Card,
            title = { Text(stringResource(R.string.engine_remove_title, engineName(config)), color = AmberPalette.Text) },
            text = { Hint(stringResource(R.string.engine_remove_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    removed = true
                    config.apiKeyAlias?.let { alias -> onSaveKey(alias, "") }
                    onDelete()
                }) { Text(stringResource(R.string.remove), color = AmberPalette.Error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel), color = AmberPalette.Muted) }
            },
        )
    }
}

/**
 * Every downloadable model plus the slot for a picked file. Each row carries its own state,
 * so a large download can run while a smaller model is already in use.
 */
@Composable
private fun WhisperModelsCard(
    states: Map<String, SlotState>,
    /** This recognition is the one in use: deleting its chosen model stops the dictations. */
    active: Boolean,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDownload: (WhisperModelSpec) -> Unit,
    onImport: (Uri) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImport)
    }
    // The model asked to be deleted, by id and name, until the question is answered.
    var deleting by remember { mutableStateOf<Pair<String, String>?>(null) }
    val ramMb = rememberTotalRamMb()
    // A model too big for the memory, by id and name, until the question is answered.
    var asking by remember { mutableStateOf<Pair<String, String>?>(null) }
    asking?.let { (id, name) ->
        TooBigDialog(name = name, onUse = { asking = null; onSelect(id) }, onDismiss = { asking = null })
    }
    deleting?.let { (id, name) ->
        DeleteModelDialog(
            name = name,
            inUse = active && id == selectedId,
            onDelete = {
                deleting = null
                onDelete(id)
            },
            onDismiss = { deleting = null },
        )
    }

    SectionCard(
        title = stringResource(R.string.models_title),
        subtitle = stringResource(R.string.models_subtitle),
        modifier = modifier,
    ) {
        WhisperModels.all.forEach { spec ->
            val title = catalog(spec.displayName)
            ModelRow(
                title = title,
                tier = catalog(spec.tier),
                sizeLabel = stringResource(R.string.megabytes, (spec.sizeBytes / 1_000_000).toInt()),
                note = catalog(spec.note),
                state = states[spec.id] ?: SlotState.Missing,
                selected = spec.id == selectedId,
                tooBig = EngineAdvice.tooBigForMemory(EngineKind.WHISPER_CPP, spec.id, ramMb),
                onSelect = { if (EngineAdvice.tooBigForMemory(EngineKind.WHISPER_CPP, spec.id, ramMb)) asking = spec.id to title else onSelect(spec.id) },
                onDownload = { onDownload(spec) },
                onCancel = { onCancel(spec.id) },
                onDelete = { deleting = spec.id to title },
            )
        }

        val imported = states[WhisperModels.IMPORTED_ID] ?: SlotState.Missing
        if (imported !is SlotState.Missing) {
            val title = stringResource(R.string.model_own_file)
            ModelRow(
                title = title,
                tier = "",
                sizeLabel = (imported as? SlotState.Ready)?.let { stringResource(R.string.megabytes, (it.sizeBytes / 1_000_000).toInt()) } ?: "",
                note = stringResource(R.string.model_own_file_note),
                state = imported,
                selected = selectedId == WhisperModels.IMPORTED_ID,
                onSelect = { onSelect(WhisperModels.IMPORTED_ID) },
                onDownload = { picker.launch(arrayOf("*/*")) },
                onCancel = { onCancel(WhisperModels.IMPORTED_ID) },
                onDelete = { deleting = WhisperModels.IMPORTED_ID to title },
                // A picked file is not downloaded: a damaged one is picked anew.
                againLabel = stringResource(R.string.model_pick_file),
            )
        }

        GhostButton(stringResource(R.string.model_pick_file), { picker.launch(arrayOf("*/*")) })
        Hint(stringResource(R.string.model_pick_file_hint))
        // The file goes to native code. Its checks keep a wrong or broken file away from it,
        // not one made to pass them (WhisperModelFile.check).
        Hint(stringResource(R.string.model_pick_file_trust))
    }
}

@Composable
private fun ModelRow(
    title: String,
    tier: String,
    sizeLabel: String,
    note: String,
    state: SlotState,
    selected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    /** Known to need more memory than the phone has ([EngineAdvice.tooBigForMemory]). */
    tooBig: Boolean = false,
    /** The button that brings a damaged model anew ([FileProblem.Damaged]). */
    againLabel: String = stringResource(R.string.download_again),
) {
    val ready = state is SlotState.Ready
    Column(
        Modifier
            .fillMaxWidth()
            .clip(AmberShapes.Inner)
            .background(AmberPalette.Surface)
            // Only the model in use is framed; everything else is flat.
            .border(
                1.dp,
                if (selected && ready) AmberPalette.Primary else Color.Transparent,
                AmberShapes.Inner,
            )
            .animateContentSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = AmberPalette.Text,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (tier.isNotBlank()) TierPill(tier)
            Spacer(Modifier.weight(1f))
            Hint(sizeLabel)
        }
        Hint(note)
        if (tooBig) Hint(stringResource(R.string.model_too_big), AmberPalette.Error)

        AnimatedContent(
            targetState = state,
            contentKey = { it::class },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "modelState",
        ) { current ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (current) {
                    SlotState.Missing -> GhostButton(stringResource(R.string.download), onDownload)

                    is SlotState.Busy -> {
                        Hint(stringResource(R.string.model_busy, current.label, progressText(current)), AmberPalette.Text)
                        ProgressLine(current.fraction)
                        GhostButton(stringResource(R.string.cancel), onCancel)
                    }

                    is SlotState.Ready -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (selected) {
                            Hint(stringResource(R.string.model_in_use), AmberPalette.Primary)
                        } else {
                            PrimaryButton(stringResource(R.string.use), onSelect)
                        }
                        Spacer(Modifier.weight(1f))
                        GhostButton(stringResource(R.string.delete), onDelete, color = AmberPalette.Error)
                    }

                    is SlotState.Failed -> if (current.problem == FileProblem.Damaged) {
                        // Not loaded again until it is brought anew; or it goes, for the room.
                        Hint(words(current.problem), AmberPalette.Error)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            PrimaryButton(againLabel, onDownload, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.weight(1f))
                            GhostButton(stringResource(R.string.delete), onDelete, color = AmberPalette.Error)
                        }
                    } else {
                        Hint(words(current.problem), AmberPalette.Error)
                        GhostButton(stringResource(R.string.try_again), onDownload)
                    }
                }
                if (current is SlotState.Ready && !current.info.multilingual) {
                    Hint(stringResource(R.string.model_english_only), AmberPalette.Error)
                }
            }
        }
    }
}

/** Where a model sits among the others ("fast", "more accurate"), as plain grey words. */
@Composable
private fun TierPill(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = AmberPalette.Muted)
}

@Composable
private fun progressText(busy: SlotState.Busy): String {
    val mb = (busy.received / 1_000_000).toInt()
    val total = busy.total
    return if (total != null && total > 0) stringResource(R.string.progress_of, mb, (total / 1_000_000).toInt())
    else stringResource(R.string.megabytes, mb)
}

/** Parakeet's one model: download with progress, then checked and ready, or delete. */
@Composable
private fun ParakeetCard(
    state: ParakeetState,
    /** Parakeet is the recognition in use: without its model there is no dictation. */
    inUse: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    var deleting by remember { mutableStateOf(false) }
    if (deleting) {
        DeleteModelDialog(
            name = ParakeetModel.DISPLAY_NAME,
            inUse = inUse,
            onDelete = {
                deleting = false
                onDelete()
            },
            onDismiss = { deleting = false },
        )
    }
    SectionCard(
        title = stringResource(R.string.model_title),
        subtitle = stringResource(R.string.parakeet_subtitle, ParakeetModel.DISPLAY_NAME, (ParakeetModel.TOTAL_BYTES / 1_000_000).toInt()),
    ) {
        when (state) {
            ParakeetState.Missing -> {
                Hint(stringResource(R.string.parakeet_missing))
                PrimaryButton(stringResource(R.string.download), onDownload)
            }
            is ParakeetState.Busy -> {
                Hint(stringResource(R.string.parakeet_busy, (state.received / 1_000_000).toInt(), (state.total / 1_000_000).toInt()), AmberPalette.Text)
                ProgressLine(state.received.toFloat() / state.total)
                GhostButton(stringResource(R.string.cancel), onCancel)
            }
            is ParakeetState.Ready -> {
                Hint(stringResource(R.string.parakeet_ready), AmberPalette.TrustOnDevice)
                GhostButton(stringResource(R.string.delete), { deleting = true }, color = AmberPalette.Error)
            }
            is ParakeetState.Failed -> if (state.problem == FileProblem.Damaged) {
                // Not loaded again until the broken files are downloaded anew; or it goes, for the room.
                Hint(words(state.problem), AmberPalette.Error)
                PrimaryButton(stringResource(R.string.download_again), onDownload)
                GhostButton(stringResource(R.string.delete), { deleting = true }, color = AmberPalette.Error)
            } else {
                Hint(words(state.problem), AmberPalette.Error)
                GhostButton(stringResource(R.string.try_again), onDownload)
            }
        }
    }
}

/**
 * A downloaded model is hundreds of megabytes and minutes to fetch again: one tap on Delete only
 * asks. The model in use says what follows - no dictation with it until it is back.
 */
@Composable
private fun DeleteModelDialog(name: String, inUse: Boolean, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AmberPalette.Surface,
        shape = AmberShapes.Card,
        title = { Text(stringResource(R.string.model_delete_title, name), color = AmberPalette.Text) },
        text = { Hint(stringResource(if (inUse) R.string.model_delete_in_use else R.string.model_delete_text)) },
        confirmButton = {
            TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = AmberPalette.Error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = AmberPalette.Muted) }
        },
    )
}

/** Typing pauses this long before what was typed is saved. */
private const val FIELD_SAVE_MS = 800L

/**
 * Models as a radio list with a short note each; [noneLabel] adds a choice for "none" (null).
 */
@Composable
internal fun ModelPicker(
    choices: List<EnginePresets.ModelChoice>,
    selected: String?,
    noneLabel: String?,
    /**
     * Said under a [selected] model the server no longer lists, which then stays as a row of
     * its own: still chosen, still sent.
     */
    notOffered: String? = null,
    onSelect: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (noneLabel != null) RadioRow(noneLabel, null, selected = selected == null, onSelect = { onSelect(null) })
        choices.forEach { choice ->
            val note = when {
                !choice.recommended -> catalog(choice.note)
                choice.note.isBlank() -> stringResource(R.string.model_recommended_only)
                else -> stringResource(R.string.model_recommended, catalog(choice.note))
            }
            RadioRow(choice.label, note, selected = choice.id == selected, onSelect = { onSelect(choice.id) })
        }
        if (notOffered != null && selected != null && choices.none { it.id == selected }) {
            RadioRow(selected, notOffered, selected = true, onSelect = {}, noteColor = AmberPalette.Primary)
        }
    }
}

/** Where this engine sends the voice; see [EngineTrust.forDisplay]. */
internal fun EngineConfig.trustLevelForDisplay(): TrustLevel =
    EngineTrust.forDisplay(this, onDeviceSpeech = AndroidOnDeviceEngine.onDeviceSpeech)

/** Where a preset not yet added would send the voice; see [EngineTrust.forPreset]. */
internal fun EnginePreset.trustLevelForDisplay(): TrustLevel =
    EngineTrust.forPreset(this, onDeviceSpeech = AndroidOnDeviceEngine.onDeviceSpeech)

/** Android's recognition on a phone that may send the voice to its maker; see [EngineTrust.androidMaySend]. */
internal fun EngineConfig?.mayGoToMaker(): Boolean =
    EngineTrust.androidMaySend(this, onDeviceSpeech = AndroidOnDeviceEngine.onDeviceSpeech)

/**
 * The debug view of Tinfoil's attestation: verify on demand, see exactly what was proven, and
 * the verifier's recent history. When a Tinfoil dictation fails, this is where the reason is.
 */
@Composable
private fun EnclaveSection(
    baseUrl: String,
    log: List<String>,
    onVerify: suspend (String) -> AttestationResult,
) {
    var checking by remember { mutableStateOf(false) }
    var result by remember(baseUrl) { mutableStateOf<AttestationResult?>(null) }
    val scope = rememberCoroutineScope()

    SectionCard(
        title = stringResource(R.string.enclave_title),
        subtitle = stringResource(R.string.enclave_subtitle),
    ) {
        PrimaryButton(
            text = stringResource(if (checking) R.string.enclave_verifying else R.string.enclave_verify),
            enabled = !checking,
            onClick = {
                checking = true
                scope.launch {
                    result = onVerify(baseUrl)
                    checking = false
                }
            },
        )
        if (checking) ProgressLine(fraction = null)

        val context = LocalContext.current
        val current = result
        if (current != null) {
            val proof = current.proof
            if (current.verified && proof != null) ProofDetails(proof) else Hint(Texts.attestation(context, current.failure), AmberPalette.Error)
        }

        // The raw lines and the hex digests are diagnostics: behind one row, in the verifier's English.
        val proof = current?.proof?.takeIf { current.verified }
        val failedLine = current?.detail?.takeIf { !current.verified && it.isNotBlank() }
        if (proof != null || failedLine != null || log.isNotEmpty()) {
            var open by rememberSaveable { mutableStateOf(false) }
            SettingsGroup(label = null) {
                NavRow(
                    title = stringResource(R.string.enclave_details),
                    summary = stringResource(R.string.enclave_details_note),
                    modifier = Modifier.foldState(open),
                    trailing = { ExpandChevron(open) },
                    onClick = { open = !open },
                )
            }
            Unfold(open) {
                // Selectable, so a fingerprint can be copied and compared with Tinfoil's own tools.
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (proof != null) ProofHex(proof)
                        failedLine?.let { Hint(it) }
                        if (log.isNotEmpty()) {
                            Text(stringResource(R.string.enclave_history), style = MaterialTheme.typography.bodySmall, color = AmberPalette.Text)
                            log.forEach { Hint(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProofDetails(proof: EnclaveProof) {
    // The phone's own date and time format, in the phone's language.
    val locale = Texts.locale(LocalContext.current)
    val time = remember(locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).withZone(ZoneId.systemDefault())
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ProofRow(stringResource(R.string.proof_status), stringResource(R.string.proof_verified), AmberPalette.TrustOnDevice)
        ProofRow(stringResource(R.string.proof_host), proof.host)
        ProofRow(stringResource(R.string.proof_release), "${proof.repo}@${proof.codeTag ?: "?"}")
        ProofRow(stringResource(R.string.proof_platform), proof.platform)
        ProofRow(stringResource(R.string.proof_verified_at), time.format(Instant.ofEpochMilli(proof.verifiedAtMillis)))
        ProofRow(stringResource(R.string.proof_valid_until), time.format(Instant.ofEpochMilli(proof.freshUntilMillis)))
    }
}

/** The digests, for the Details fold. */
@Composable
private fun ProofHex(proof: EnclaveProof) {
    ProofRow(stringResource(R.string.proof_digest), proof.codeDigest, mono = true)
    val measurements = stringResource(R.string.proof_measurements)
    proof.registers.forEachIndexed { index, register ->
        ProofRow(if (index == 0) measurements else "", register.ifEmpty { "–" }, mono = true)
    }
    ProofRow(stringResource(R.string.proof_tls), proof.tlsKeySha256Hex, mono = true)
    proof.hpkePublicKey?.let { ProofRow(stringResource(R.string.proof_hpke), it, mono = true) }
}

@Composable
private fun ProofRow(label: String, value: String, color: Color = AmberPalette.Text, mono: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = AmberPalette.Muted,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.let { if (mono) it.copy(fontFamily = FontFamily.Monospace) else it },
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * One tap to a known service - its address and model filled in - or to an own server, which
 * empties both again. The lit one is what the address in the field is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerQuickPicks(current: ServerProvider, onPick: (Servers.Quick) -> Unit, onOwn: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Servers.QUICK.forEach { quick ->
            ChoiceChip(quick.provider.name, null, on = current === quick.provider) { if (current !== quick.provider) onPick(quick) }
        }
        val own = current === ServerProviders.CUSTOM
        ChoiceChip(catalog(ServerProviders.CUSTOM.name), null, on = own) { if (!own) onOwn() }
    }
}
