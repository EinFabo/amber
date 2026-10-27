package app.nocturne.engine

import android.content.Context
import app.nocturne.core.engine.EngineCapabilities
import app.nocturne.core.engine.EngineKind
import app.nocturne.core.engine.OpenAiCompatibleEngine
import app.nocturne.core.engine.SttEngine
import app.nocturne.core.engine.SttFailure
import app.nocturne.core.net.HttpTransport
import app.nocturne.core.privacy.TrustLevel
import app.nocturne.core.settings.EngineConfig
import app.nocturne.data.SecretStore

/**
 * Turns a stored [EngineConfig] into a live engine.
 *
 * Engines not yet implemented fail with a message naming the stage they arrive in, rather
 * than silently falling back to a different engine - a silent fallback would mean audio
 * going somewhere the user did not choose.
 */
class EngineFactory(
    private val context: Context,
    private val transport: HttpTransport,
    private val secrets: SecretStore,
) {

    fun create(config: EngineConfig): Result<SttEngine> = when (config.kind) {
        EngineKind.ANDROID_ON_DEVICE -> Result.success(AndroidOnDeviceEngine(context))

        EngineKind.OPENAI_COMPATIBLE -> {
            val baseUrl = config.baseUrl?.takeIf { it.isNotBlank() }
            val modelId = config.modelId?.takeIf { it.isNotBlank() }
            when {
                baseUrl == null -> incomplete(config, "Base-URL")
                modelId == null -> incomplete(config, "Modellname")
                else -> Result.success(
                    OpenAiCompatibleEngine(
                        id = config.id,
                        displayName = config.displayName,
                        baseUrl = baseUrl,
                        modelId = modelId,
                        transport = transport,
                        apiKeyProvider = { secrets.get(config.apiKeyAlias) },
                        capabilities = EngineCapabilities(
                            trustLevel = TrustLevel.CLOUD,
                            // An alias is only assigned to presets that authenticate, so a
                            // self-hosted endpoint is never nagged for a key.
                            needsApiKey = config.apiKeyAlias != null,
                        ),
                        // Stage 4 passes a real verifier here; until then the engine keeps
                        // reporting CLOUD even for an enclave endpoint.
                        attestationVerifier = null,
                    ),
                )
            }
        }

        EngineKind.LOCAL_SHERPA -> Result.failure(
            SttFailure(
                userMessage = "Lokale Modelle kommen in Stufe 3. " +
                    "Nutze bis dahin \"Android (auf dem Gerät)\" oder einen eigenen Server.",
                technical = "LOCAL_SHERPA not implemented in stage 1",
            ),
        )

        EngineKind.ELEVENLABS_REALTIME -> Result.failure(
            SttFailure(
                userMessage = "Streaming über ElevenLabs kommt in Stufe 4.",
                technical = "ELEVENLABS_REALTIME not implemented in stage 1",
            ),
        )
    }

    private fun incomplete(config: EngineConfig, field: String): Result<SttEngine> = Result.failure(
        SttFailure(
            userMessage = "Für \"${config.displayName}\" fehlt noch: $field.",
            technical = "incomplete config for ${config.id}: missing $field",
        ),
    )
}
