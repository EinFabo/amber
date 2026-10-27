package app.nocturne.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.nocturne.core.engine.EngineKind
import app.nocturne.core.engine.EnginePresets
import app.nocturne.core.settings.EngineConfig
import app.nocturne.ui.ConnectionTester
import app.nocturne.ui.Hint
import app.nocturne.ui.SectionCard
import app.nocturne.ui.TrustBadge
import app.nocturne.ui.theme.NocturnePalette
import app.nocturne.ui.theme.badgeExplanation
import kotlinx.coroutines.launch

@Composable
fun EngineDetailScreen(
    config: EngineConfig,
    storedKey: String?,
    onSaveKey: (String, String) -> Unit,
    onSave: (EngineConfig) -> Unit,
    onDelete: () -> Unit,
    onTest: suspend (EngineConfig) -> ConnectionTester.Result,
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(title = config.displayName) {
            TrustBadge(config.trustLevelForDisplay())
            Hint(config.trustLevelForDisplay().badgeExplanation())
            if (preset?.note?.isNotBlank() == true) Hint(preset.note)
        }

        when (config.kind) {
            EngineKind.ANDROID_ON_DEVICE -> SectionCard(title = "Konfiguration") {
                Hint(
                    "Diese Engine hat nichts einzustellen. Welche Sprachen offline " +
                        "verfügbar sind, entscheiden die Systemeinstellungen.",
                )
                LanguageField(language) { language = it }
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint("Mit oder ohne /v1 – beides wird akzeptiert.")

                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("Modell") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (config.apiKeyAlias != null) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API-Schlüssel") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Hint("Wird verschlüsselt gespeichert, nie in den Einstellungs-Export geschrieben.")
                }

                LanguageField(language) { language = it }
            }

            EngineKind.LOCAL_SHERPA -> SectionCard(title = "Noch nicht verfügbar") {
                Hint(
                    "Lokale Modelle (Whisper, Parakeet, Moonshine über sherpa-onnx) kommen " +
                        "in Stufe 3, zusammen mit Download-Verwaltung und Geräte-Benchmark.",
                )
                LanguageField(language) { language = it }
            }

            EngineKind.ELEVENLABS_REALTIME -> SectionCard(title = "Noch nicht verfügbar") {
                Hint("Streaming über ElevenLabs Scribe v2 Realtime kommt in Stufe 4.")
            }
        }

        SectionCard(title = "Prüfen") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { persist() }) { Text("Speichern") }
                TextButton(
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
                ) {
                    Text("Verbindung testen", color = NocturnePalette.Primary)
                }
            }

            if (testing) {
                CircularProgressIndicator(color = NocturnePalette.Primary)
            }

            when (val result = testResult) {
                null -> Unit
                is ConnectionTester.Result.Ok -> Text(
                    text = result.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = NocturnePalette.TrustOnDevice,
                )

                is ConnectionTester.Result.Failed -> Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = NocturnePalette.Error,
                )
            }
        }

        if (config.id != EnginePresets.ANDROID_ON_DEVICE.id) {
            TextButton(onClick = onDelete) {
                Text("Engine entfernen", color = NocturnePalette.Error)
            }
        }
    }
}

@Composable
private fun LanguageField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Sprache (BCP-47)") },
        placeholder = { Text("de-DE, leer = automatisch") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The level this engine would report *today*. Enclave verification only exists from stage 4,
 * so a Tinfoil endpoint is honestly shown as CLOUD until then.
 */
private fun EngineConfig.trustLevelForDisplay() = when (kind) {
    EngineKind.ANDROID_ON_DEVICE, EngineKind.LOCAL_SHERPA ->
        app.nocturne.core.privacy.TrustLevel.ON_DEVICE
    EngineKind.OPENAI_COMPATIBLE, EngineKind.ELEVENLABS_REALTIME ->
        app.nocturne.core.privacy.TrustLevel.CLOUD
}
