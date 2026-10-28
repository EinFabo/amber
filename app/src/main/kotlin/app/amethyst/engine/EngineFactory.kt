package app.amethyst.engine

import android.content.Context
import app.amethyst.core.engine.EngineCapabilities
import app.amethyst.core.engine.EngineConfigValidator
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.OpenAiCompatibleEngine
import app.amethyst.core.engine.SttEngine
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.engine.whisper.WhisperCppEngine
import app.amethyst.core.engine.whisper.WhisperModels
import app.amethyst.core.net.HttpTransport
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.EngineConfig
import app.amethyst.data.SecretStore

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
    private val whisperModels: WhisperModelStore,
    private val whisperRuntime: NativeWhisperRuntime,
) {

    fun create(config: EngineConfig): Result<SttEngine> = when (config.kind) {
        EngineKind.ANDROID_ON_DEVICE -> Result.success(AndroidOnDeviceEngine(context))

        EngineKind.OPENAI_COMPATIBLE -> {
            val problems = EngineConfigValidator.validate(config, secrets.has(config.apiKeyAlias))
            val baseUrl = config.baseUrl?.trim()
            val modelId = config.modelId?.trim()
            when {
                problems.isNotEmpty() -> incomplete(config, problems)
                baseUrl.isNullOrBlank() -> incomplete(config, listOf("Die Base-URL fehlt."))
                modelId.isNullOrBlank() -> incomplete(config, listOf("Der Modellname fehlt."))
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

        EngineKind.WHISPER_CPP -> {
            val modelId = config.modelId ?: WhisperModels.default.id
            if (whisperModels.isReady(modelId)) {
                Result.success(
                    WhisperCppEngine(
                        id = config.id,
                        displayName = config.displayName,
                        modelPath = whisperModels.fileFor(modelId).path,
                        runtime = whisperRuntime,
                        threads = NativeWhisperRuntime.defaultThreads(),
                        shortContext = whisperModels.shortContextFor(modelId),
                        compute = whisperRuntime.dispatcher,
                    ),
                )
            } else {
                Result.failure(
                    SttFailure(
                        userMessage = "Das gewählte Whisper-Modell ist noch nicht geladen.",
                        technical = "WHISPER_CPP model $modelId is not usable",
                        remedy = SttFailure.Remedy.DOWNLOAD_MODEL,
                    ),
                )
            }
        }

        EngineKind.LOCAL_SHERPA -> Result.failure(
            SttFailure(
                userMessage = "Parakeet und Moonshine kommen in einer späteren Stufe. " +
                    "Nutze bis dahin \"Whisper lokal\" oder einen eigenen Server.",
                technical = "LOCAL_SHERPA not implemented",
            ),
        )

        EngineKind.ELEVENLABS_REALTIME -> Result.failure(
            SttFailure(
                userMessage = "Streaming über ElevenLabs kommt in Stufe 4.",
                technical = "ELEVENLABS_REALTIME not implemented in stage 1",
            ),
        )
    }

    private fun incomplete(config: EngineConfig, problems: List<String>): Result<SttEngine> =
        Result.failure(
            SttFailure(
                userMessage = "\"${config.displayName}\" ist noch nicht einsatzbereit. " +
                    problems.joinToString(" "),
                technical = "incomplete config for ${config.id}: $problems",
            ),
        )
}
