package app.amethyst.ui.screen

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.amethyst.core.engine.EngineConfigValidator
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.whisper.WhisperInput
import app.amethyst.core.engine.whisper.WhisperModelSpec
import app.amethyst.core.engine.whisper.WhisperModels
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.engine.whisper.ModelSlots.SlotState
import app.amethyst.ui.ConnectionTester
import app.amethyst.ui.GhostButton
import app.amethyst.ui.ProgressLine
import app.amethyst.ui.Hint
import app.amethyst.ui.PrimaryButton
import app.amethyst.ui.SectionCard
import app.amethyst.ui.TrustBadge
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes
import app.amethyst.ui.theme.badgeExplanation
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
    modifier: Modifier = Modifier,
) {
    var baseUrl by remember(config.id) { mutableStateOf(config.baseUrl.orEmpty()) }
    var modelId by remember(config.id) { mutableStateOf(config.modelId.orEmpty()) }
    var language by remember(config.id) { mutableStateOf(config.languageTag.orEmpty()) }
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
    )

    fun persist() {
        config.apiKeyAlias?.let { alias -> onSaveKey(alias, apiKey) }
        onSave(edited())
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
            Hint(config.trustLevelForDisplay().badgeExplanation())
            if (preset?.note?.isNotBlank() == true) Hint(preset.note)

            if (problems.isEmpty()) {
                Hint("Einstellungen vollständig.", AmberPalette.TrustOnDevice)
            } else {
                // Listed while typing, so the user is never left guessing which field the
                // engine is still waiting for.
                problems.forEach { problem -> Hint(problem, AmberPalette.Error) }
            }
            if (!active) PrimaryButton("Als aktive Engine verwenden", onActivate)
        }

        when (config.kind) {
            EngineKind.ANDROID_ON_DEVICE -> SectionCard(
                title = "Konfiguration",
            ) {
                Hint(
                    "Nutzt den Erkenner des Systems. Welche Sprachen offline verfügbar sind, " +
                        "entscheiden die Systemeinstellungen.",
                )
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
                SectionCard(title = "Sprachen") {
                    Hint(
                        "Die Sprachen, in denen du diktierst. Bei mehreren erkennt Whisper pro " +
                            "Diktat, welche gesprochen wurde. Eine einzelne wird erzwungen: " +
                            "Was in einer anderen Sprache kommt, wird dann übersetzt.",
                    )
                    WhisperLanguagePicker(language) { picked ->
                        language = picked
                        onSave(edited())
                    }
                }
            }

            EngineKind.OPENAI_COMPATIBLE -> SectionCard(
                title = "Verbindung",
                subtitle = "Alles, was POST /v1/audio/transcriptions versteht.",
            ) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base-URL") },
                    placeholder = { Text("https://host/v1") },
                    singleLine = true,
                    shape = AmberShapes.Inner,
                    colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint("Mit oder ohne /v1, beides geht.")

                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("Modell") },
                    singleLine = true,
                    shape = AmberShapes.Inner,
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (config.apiKeyAlias != null) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API-Schlüssel") },
                        singleLine = true,
                        shape = AmberShapes.Inner,
                        colors = fieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Hint("Wird verschlüsselt gespeichert, nie in den Einstellungs-Export geschrieben.")
                }

                LanguageField(language) { language = it }
            }

            EngineKind.LOCAL_SHERPA -> SectionCard(
                title = "Noch nicht verfügbar",
            ) {
                Hint("Parakeet und Moonshine über sherpa-onnx kommen in einer späteren Stufe.")
            }

            EngineKind.ELEVENLABS_REALTIME -> SectionCard(
                title = "Noch nicht verfügbar",
            ) {
                Hint("Streaming über ElevenLabs Scribe v2 Realtime kommt in Stufe 4.")
            }
        }

        SectionCard(title = "Prüfen") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Speichern", { persist() })
                GhostButton(
                    text = if (local) "Modell testen" else "Verbindung testen",
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
            GhostButton("Engine entfernen", onDelete, color = AmberPalette.Error)
        }
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
        title = "Modelle",
        subtitle = "Größer heißt genauer, aber langsamer. Laden, dann „Verwenden“.",
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
                title = "Eigene Datei",
                tier = "",
                sizeLabel = (imported as? SlotState.Ready)?.let { "${it.sizeBytes / 1_000_000} MB" } ?: "",
                note = "Ein whisper.cpp-Modell, das du selbst gewählt hast. Läuft mit vollem Kontext.",
                state = imported,
                selected = selectedId == WhisperModels.IMPORTED_ID,
                onSelect = { onSelect(WhisperModels.IMPORTED_ID) },
                onDownload = { picker.launch(arrayOf("*/*")) },
                onCancel = { onCancel(WhisperModels.IMPORTED_ID) },
                onDelete = { onDelete(WhisperModels.IMPORTED_ID) },
            )
        }

        GhostButton("Eigene Datei wählen", { picker.launch(arrayOf("*/*")) })
        Hint("Schon von FUTO geladen? Die .bin-Datei wählen, sie wird erkannt und geprüft.")
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
                    SlotState.Missing -> GhostButton("Laden", onDownload)

                    is SlotState.Busy -> {
                        Hint(current.label + " … " + progressText(current), AmberPalette.Text)
                        ProgressLine(current.fraction)
                        GhostButton("Abbrechen", onCancel)
                    }

                    is SlotState.Ready -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (selected) {
                            Hint("Wird verwendet", AmberPalette.Primary)
                        } else {
                            PrimaryButton("Verwenden", onSelect)
                        }
                        Spacer(Modifier.weight(1f))
                        GhostButton("Löschen", onDelete, color = AmberPalette.Error)
                    }

                    is SlotState.Failed -> {
                        Hint(current.message, AmberPalette.Error)
                        GhostButton("Erneut versuchen", onDownload)
                    }
                }
                if (current is SlotState.Ready && !current.info.multilingual) {
                    Hint("Dieses Modell versteht nur Englisch.", AmberPalette.Error)
                }
            }
        }
    }
}

/** Where a model sits among the others ("schnell", "genauer"), as plain grey words. */
@Composable
private fun TierPill(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = AmberPalette.Muted)
}

private fun progressText(busy: SlotState.Busy): String {
    val mb = busy.received / 1_000_000
    val total = busy.total
    return if (total != null && total > 0) "$mb von ${total / 1_000_000} MB" else "$mb MB"
}

/** The languages offered as chips; anything else can still be typed into the old field. */
private val WHISPER_LANGUAGES = listOf(
    "de" to "Deutsch",
    "en" to "Englisch",
    "fr" to "Französisch",
    "es" to "Spanisch",
    "it" to "Italienisch",
    "nl" to "Niederländisch",
    "pl" to "Polnisch",
    "tr" to "Türkisch",
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
    if (picked.isEmpty()) Hint("Keine gewählt: Whisper rät unter allen Sprachen.")
}

@Composable
private fun LanguageField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Sprache (BCP-47)") },
        placeholder = { Text("de-DE, leer = automatisch") },
        singleLine = true,
        shape = AmberShapes.Inner,
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The level this engine would report *today*. Enclave verification only exists from stage 4,
 * so a Tinfoil endpoint is honestly shown as CLOUD until then.
 */
internal fun EngineConfig.trustLevelForDisplay(): TrustLevel = when (kind) {
    EngineKind.ANDROID_ON_DEVICE, EngineKind.LOCAL_SHERPA, EngineKind.WHISPER_CPP -> TrustLevel.ON_DEVICE
    EngineKind.OPENAI_COMPATIBLE, EngineKind.ELEVENLABS_REALTIME -> TrustLevel.CLOUD
}
