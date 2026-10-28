package app.amethyst.ui.screen

import android.os.Build
import android.speech.SpeechRecognizer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import app.amethyst.core.engine.ServerProviders
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.ui.unit.dp
import app.amethyst.core.engine.EngineConfigValidator
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.whisper.WhisperInput
import app.amethyst.core.engine.whisper.WhisperModelSpec
import app.amethyst.core.engine.whisper.WhisperModels
import app.amethyst.core.privacy.AttestationResult
import app.amethyst.core.privacy.EnclaveProof
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.engine.whisper.ModelSlots.SlotState
import app.amethyst.core.engine.sherpa.ParakeetModel
import app.amethyst.engine.ParakeetState
import app.amethyst.engine.TinfoilVerifier
import app.amethyst.ui.ChoiceChip
import app.amethyst.ui.ConnectionTester
import app.amethyst.ui.GhostButton
import app.amethyst.ui.ProgressLine
import app.amethyst.ui.Hint
import app.amethyst.ui.PrimaryButton
import app.amethyst.ui.SectionCard
import app.amethyst.ui.TrustBadge
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes
import app.amethyst.ui.theme.placeExplanation
import kotlinx.coroutines.launch

@Composable
fun EngineDetailScreen(
    config: EngineConfig,
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
) {
    var baseUrl by remember(config.id) { mutableStateOf(config.baseUrl.orEmpty()) }
    var modelId by remember(config.id) { mutableStateOf(config.modelId.orEmpty()) }
    var language by remember(config.id) { mutableStateOf(config.languageTag.orEmpty()) }
    var cleanupModel by remember(config.id) { mutableStateOf(config.cleanupModel) }
    var apiKey by remember(config.id) { mutableStateOf(storedKey.orEmpty()) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember(config.id) { mutableStateOf<ConnectionTester.Result?>(null) }

    val scope = rememberCoroutineScope()
    val preset = EnginePresets.byId(config.id)
    val local = config.kind == EngineKind.WHISPER_CPP

    fun edited(): EngineConfig = config.copy(
        baseUrl = baseUrl.trim().takeIf { it.isNotBlank() },
        modelId = modelId.trim().takeIf { it.isNotBlank() },
        languageTag = language.trim().takeIf { it.isNotBlank() },
        cleanupModel = cleanupModel,
    )

    var confirmRemove by remember { mutableStateOf(false) }
    var removed by remember { mutableStateOf(false) }

    fun persist() {
        config.apiKeyAlias?.let { alias -> onSaveKey(alias, apiKey) }
        onSave(edited())
    }

    fun persistIfChanged() {
        if (removed) return
        if (edited() != config || apiKey != storedKey.orEmpty()) persist()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        val problems = EngineConfigValidator.validate(edited(), hasApiKey = apiKey.isNotBlank())

        SectionCard(title = config.displayName) {
            TrustBadge(config.trustLevelForDisplay())
            Hint(config.trustLevelForDisplay().placeExplanation())
            if (preset?.note?.isNotBlank() == true) Hint(preset.note)

            if (problems.isEmpty()) {
                Hint("Settings complete.", AmberPalette.TrustOnDevice)
            } else {
                // Listed while typing, so the user is never left guessing which field the
                // engine is still waiting for.
                problems.forEach { problem -> Hint(problem, AmberPalette.Error) }
            }
            if (!active) PrimaryButton("Use as active engine", onActivate)
        }

        when (config.kind) {
            EngineKind.ANDROID_ON_DEVICE -> SectionCard(
                title = "Configuration",
            ) {
                Hint(
                    "Uses the system recognizer. Which languages are available offline is up to " +
                        "the system settings.",
                )
                val context = LocalContext.current
                val onDevice = remember {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                }
                if (!onDevice) {
                    Hint(
                        "This phone has no on-device recognizer: the system one may send your voice to " +
                            "its servers, and such dictations are marked as cloud. Parakeet or Whisper stay on the phone.",
                        AmberPalette.Primary,
                    )
                }
                LanguageField(language) { language = it }
            }

            EngineKind.WHISPER_CPP -> {
                WhisperModelsCard(
                    states = whisperModels,
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
                SectionCard(title = "Languages") {
                    Hint(
                        "The languages you dictate in. With several, Whisper detects per dictation " +
                            "which one was spoken. A single one is forced: " +
                            "anything said in another language is then translated.",
                    )
                    WhisperLanguagePicker(language) { picked ->
                        language = picked
                        onSave(edited())
                    }
                }
            }

            EngineKind.OPENAI_COMPATIBLE -> {
                // One backend for every server; the provider only decides what is offered. Taken
                // from the saved address, not the one being typed - otherwise typing a known host
                // into an own server's field would make that field vanish mid-word.
                val provider = remember(config.id, config.baseUrl) {
                    if (config.id == EnginePresets.SELF_HOSTED.id) ServerProviders.CUSTOM else ServerProviders.forUrl(config.baseUrl)
                }
                SectionCard(
                    title = "Connection",
                    subtitle = if (provider.fixedAddress) "${provider.name} · ${baseUrl.trim()}"
                    else "Anything with /v1/audio/transcriptions - Speaches, LocalAI, whisper.cpp's server.",
                ) {
                    if (!provider.fixedAddress) {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text("Address") },
                            placeholder = { Text("http://192.168.1.20:8000/v1") },
                            singleLine = true,
                            shape = AmberShapes.Inner,
                            colors = fieldColors(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Hint("With or without /v1. http:// only for servers in your own network.")
                        OutlinedTextField(
                            value = modelId,
                            onValueChange = { modelId = it },
                            label = { Text("Speech model") },
                            placeholder = { Text("Systran/faster-whisper-large-v3") },
                            singleLine = true,
                            shape = AmberShapes.Inner,
                            colors = fieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (config.apiKeyAlias != null) {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("API key") },
                            singleLine = true,
                            shape = AmberShapes.Inner,
                            colors = fieldColors(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            // Masked: off screenshots, recordings and other services' eyes.
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Hint("Stored encrypted on this phone only.")
                    }
                }
                if (provider.known) {
                    SectionCard(
                        title = "Speech model",
                        subtitle = "Which of ${provider.name}'s models turns your voice into text.",
                    ) {
                        ModelPicker(
                            choices = provider.speechModels,
                            selected = modelId.trim(),
                            noneLabel = null,
                            onSelect = { id ->
                                modelId = id ?: modelId
                                onSave(edited())
                            },
                        )
                    }
                }
                SectionCard(
                    title = "Language",
                    subtitle = "Automatic works for most. One fixed language helps with short dictations.",
                ) {
                    ServerLanguagePicker(language.substringBefore('-').lowercase()) { picked ->
                        language = picked
                        onSave(edited())
                    }
                }
                SectionCard(
                    title = "Cleanup",
                    subtitle = "A chat model tidies each dictation before it is typed: punctuation, filler " +
                        "words, mis-hearings - and the writing style per app. " +
                        if (provider == ServerProviders.TINFOIL) "Same verified enclave, a moment longer." else "Same server, a moment longer.",
                ) {
                    if (provider.known) {
                        ModelPicker(
                            choices = provider.cleanupModels,
                            selected = cleanupModel,
                            noneLabel = "Off",
                            onSelect = { id ->
                                cleanupModel = id
                                onSave(edited())
                            },
                        )
                    } else {
                        OutlinedTextField(
                            value = cleanupModel.orEmpty(),
                            onValueChange = { cleanupModel = it.ifBlank { null } },
                            label = { Text("Chat model (empty: off)") },
                            placeholder = { Text("llama3.2") },
                            singleLine = true,
                            shape = AmberShapes.Inner,
                            colors = fieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Hint("Any model your server offers at /v1/chat/completions.")
                    }
                    Hint("If the cleanup fails or changes too much, the dictation goes in as recognized.")
                }
                if (provider == ServerProviders.TINFOIL) EnclaveSection(baseUrl.trim(), enclaveLog, onVerifyEnclave)
            }

            EngineKind.LOCAL_SHERPA -> ParakeetCard(parakeetState, onDownloadParakeet, onCancelParakeet, onDeleteParakeet)

            EngineKind.ELEVENLABS_REALTIME -> SectionCard(title = "Not supported") {
                Hint("ElevenLabs Realtime was never finished. Remove this engine.")
            }
        }

        SectionCard(title = "Check") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Save", { persist() })
                GhostButton(
                    text = if (local) "Test model" else "Test connection",
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
            }

            if (config.kind == EngineKind.OPENAI_COMPATIBLE) {
                val price = ServerProviders.forUrl(baseUrl).speechModel(modelId.trim())?.usdPerRequest ?: 0.0
                if (price > 0) Hint("A test is billed like a dictation (\$${"%.2f".format(java.util.Locale.US, price)}).")
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
            GhostButton("Remove engine", { confirmRemove = true }, color = AmberPalette.Error)
        }
    }

    // Typed into a field and went back without "Save": keep it anyway.
    val latestPersist by rememberUpdatedState(::persistIfChanged)
    DisposableEffect(config.id) {
        onDispose { latestPersist() }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            containerColor = AmberPalette.Surface,
            shape = AmberShapes.Card,
            title = { Text("Remove ${config.displayName}?", color = AmberPalette.Text) },
            text = { Hint("Its settings and stored key are deleted. Downloaded models stay until you delete them.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    removed = true
                    config.apiKeyAlias?.let { alias -> onSaveKey(alias, "") }
                    onDelete()
                }) { Text("Remove", color = AmberPalette.Error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Cancel", color = AmberPalette.Muted) }
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

    SectionCard(
        title = "Models",
        subtitle = "Bigger means more accurate but slower. Download, then \"Use\".",
        modifier = modifier,
    ) {
        WhisperModels.all.forEach { spec ->
            ModelRow(
                title = spec.displayName,
                tier = spec.tier,
                sizeLabel = "${spec.sizeBytes / 1_000_000} MB",
                note = spec.note,
                state = states[spec.id] ?: SlotState.Missing,
                selected = spec.id == selectedId,
                onSelect = { onSelect(spec.id) },
                onDownload = { onDownload(spec) },
                onCancel = { onCancel(spec.id) },
                onDelete = { onDelete(spec.id) },
            )
        }

        val imported = states[WhisperModels.IMPORTED_ID] ?: SlotState.Missing
        if (imported !is SlotState.Missing) {
            ModelRow(
                title = "Own file",
                tier = "",
                sizeLabel = (imported as? SlotState.Ready)?.let { "${it.sizeBytes / 1_000_000} MB" } ?: "",
                note = "A whisper.cpp model you picked yourself. Runs with the full context.",
                state = imported,
                selected = selectedId == WhisperModels.IMPORTED_ID,
                onSelect = { onSelect(WhisperModels.IMPORTED_ID) },
                onDownload = { picker.launch(arrayOf("*/*")) },
                onCancel = { onCancel(WhisperModels.IMPORTED_ID) },
                onDelete = { onDelete(WhisperModels.IMPORTED_ID) },
            )
        }

        GhostButton("Pick own file", { picker.launch(arrayOf("*/*")) })
        Hint("Already downloaded from FUTO? Pick the .bin file, it is recognized and checked.")
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

        AnimatedContent(
            targetState = state,
            contentKey = { it::class },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "modelState",
        ) { current ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (current) {
                    SlotState.Missing -> GhostButton("Download", onDownload)

                    is SlotState.Busy -> {
                        Hint(current.label + " … " + progressText(current), AmberPalette.Text)
                        ProgressLine(current.fraction)
                        GhostButton("Cancel", onCancel)
                    }

                    is SlotState.Ready -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (selected) {
                            Hint("In use", AmberPalette.Primary)
                        } else {
                            PrimaryButton("Use", onSelect)
                        }
                        Spacer(Modifier.weight(1f))
                        GhostButton("Delete", onDelete, color = AmberPalette.Error)
                    }

                    is SlotState.Failed -> {
                        Hint(current.message, AmberPalette.Error)
                        GhostButton("Try again", onDownload)
                    }
                }
                if (current is SlotState.Ready && !current.info.multilingual) {
                    Hint("This model only understands English.", AmberPalette.Error)
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

private fun progressText(busy: SlotState.Busy): String {
    val mb = busy.received / 1_000_000
    val total = busy.total
    return if (total != null && total > 0) "$mb of ${total / 1_000_000} MB" else "$mb MB"
}

/** Parakeet's one model: download with progress, then checked and ready, or delete. */
@Composable
private fun ParakeetCard(
    state: ParakeetState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    SectionCard(
        title = "Model",
        subtitle = "${ParakeetModel.DISPLAY_NAME} · ${ParakeetModel.TOTAL_BYTES / 1_000_000} MB · runs on this phone",
    ) {
        when (state) {
            ParakeetState.Missing -> {
                Hint(
                    "A one-time download; Wi-Fi recommended. While you dictate it holds about 700 MB " +
                        "of memory, freed again after three idle minutes.",
                )
                PrimaryButton("Download", onDownload)
            }
            is ParakeetState.Busy -> {
                Hint("Downloading … ${state.received / 1_000_000} of ${state.total / 1_000_000} MB", AmberPalette.Text)
                ProgressLine(state.received.toFloat() / state.total)
                GhostButton("Cancel", onCancel)
            }
            is ParakeetState.Ready -> {
                Hint("Downloaded and checked.", AmberPalette.TrustOnDevice)
                GhostButton("Delete", onDelete, color = AmberPalette.Error)
            }
            is ParakeetState.Failed -> {
                Hint(state.message, AmberPalette.Error)
                GhostButton("Try again", onDownload)
            }
        }
    }
}

/**
 * Models as chips with a short note each; [noneLabel] adds a choice for "none" (null). The
 * selected one is filled amber.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelPicker(
    choices: List<EnginePresets.ModelChoice>,
    selected: String?,
    noneLabel: String?,
    onSelect: (String?) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (noneLabel != null) ChoiceChip(noneLabel, null, on = selected == null) { onSelect(null) }
        choices.forEach { choice ->
            val note = if (choice.recommended) "Recommended · ${choice.note}" else choice.note
            ChoiceChip(choice.label, note, on = choice.id == selected) { onSelect(choice.id) }
        }
    }
}

/** The languages offered as chips; anything else can still be typed into the old field. */
private val WHISPER_LANGUAGES = listOf(
    "de" to "German",
    "en" to "English",
    "fr" to "French",
    "es" to "Spanish",
    "it" to "Italian",
    "nl" to "Dutch",
    "pl" to "Polish",
    "tr" to "Turkish",
)

/** Toggles languages in a "de,en" list. None picked means Whisper detects any language. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhisperLanguagePicker(value: String, onChange: (String) -> Unit) {
    val picked = WhisperInput.languageCodes(value)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WHISPER_LANGUAGES.forEach { (code, name) ->
            val on = code in picked
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                color = if (on) AmberPalette.Background else AmberPalette.Text,
                modifier = Modifier
                    .clip(AmberShapes.Inner)
                    .background(if (on) AmberPalette.Primary else AmberPalette.Surface)
                    .clickable { onChange((if (on) picked - code else picked + code).joinToString(",")) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
    if (picked.isEmpty()) Hint("None picked: Whisper guesses among all languages.")
}

/** Automatic or one language, for servers: the API takes a single ISO code or none. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerLanguagePicker(value: String, onChange: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChoiceChip("Automatic", null, on = value.isBlank()) { onChange("") }
        WHISPER_LANGUAGES.forEach { (code, name) ->
            ChoiceChip(name, null, on = value == code) { onChange(code) }
        }
    }
}

@Composable
private fun LanguageField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Language (BCP-47)") },
        placeholder = { Text("de-DE, empty = automatic") },
        singleLine = true,
        shape = AmberShapes.Inner,
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Where this engine sends the voice. An engine that requires its enclave sends nothing unless
 * the attestation passes, so it is shown as verified; any other server is plain cloud.
 */
internal fun EngineConfig.trustLevelForDisplay(): TrustLevel = when (kind) {
    EngineKind.ANDROID_ON_DEVICE, EngineKind.LOCAL_SHERPA, EngineKind.WHISPER_CPP -> TrustLevel.ON_DEVICE
    EngineKind.OPENAI_COMPATIBLE, EngineKind.ELEVENLABS_REALTIME ->
        if (requireEnclave && EnginePresets.isEnclaveHost(baseUrl)) TrustLevel.ENCLAVE_VERIFIED else TrustLevel.CLOUD
}

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
        title = "Enclave verification",
        subtitle = "Recordings only go to a verified enclave, over a connection bound to " +
            "its verified key. Otherwise nothing is sent.",
    ) {
        PrimaryButton(
            text = if (checking) "Verifying …" else "Verify now",
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

        when (val current = result) {
            null -> Unit
            else -> {
                val proof = current.proof
                if (current.verified && proof != null) ProofDetails(proof) else Hint(current.detail, AmberPalette.Error)
            }
        }

        if (log.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("History", style = MaterialTheme.typography.bodySmall, color = AmberPalette.Text)
                log.forEach { Hint(it) }
            }
        }
    }
}

@Composable
private fun ProofDetails(proof: EnclaveProof) {
    val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    // Selectable, so a fingerprint can be copied and compared with Tinfoil's own tools.
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ProofRow("Status", "Verified", AmberPalette.TrustOnDevice)
            ProofRow("Host", proof.host)
            ProofRow("Release", "${proof.repo}@${proof.codeTag ?: "?"}")
            ProofRow("Platform", proof.platform)
            ProofRow("Code digest", proof.codeDigest, mono = true)
            proof.registers.forEachIndexed { index, register ->
                ProofRow(if (index == 0) "Measurements" else "", register.ifEmpty { "–" }, mono = true)
            }
            ProofRow("TLS key", proof.tlsKeySha256Hex, mono = true)
            proof.hpkePublicKey?.let { ProofRow("HPKE key", it, mono = true) }
            ProofRow("Verified at", time.format(Instant.ofEpochMilli(proof.verifiedAtMillis)))
            ProofRow("Valid until", time.format(Instant.ofEpochMilli(proof.freshUntilMillis)))
        }
    }
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
