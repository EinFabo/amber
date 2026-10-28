package app.amethyst.engine

import android.content.Context
import app.amethyst.core.engine.EngineCapabilities
import app.amethyst.core.engine.EngineConfigValidator
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.engine.OpenAiCompatibleEngine
import app.amethyst.core.engine.SttEngine
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.engine.sherpa.ParakeetEngine
import app.amethyst.core.engine.whisper.WhisperCppEngine
import app.amethyst.core.engine.whisper.WhisperModels
import app.amethyst.core.net.HttpTransport
import app.amethyst.core.net.SecureChannel
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.text.ChatCleanup
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
    private val tinfoil: TinfoilVerifier,
    private val parakeetModels: ParakeetModelStore,
    private val sherpaRuntime: SherpaRuntime,
) {

    fun create(config: EngineConfig): Result<SttEngine> = when (config.kind) {
        EngineKind.ANDROID_ON_DEVICE -> Result.success(AndroidOnDeviceEngine(context))

        EngineKind.OPENAI_COMPATIBLE -> {
            val problems = EngineConfigValidator.validate(config, secrets.has(config.apiKeyAlias))
            val baseUrl = config.baseUrl?.trim()
            val modelId = config.modelId?.trim()
            when {
                problems.isNotEmpty() -> incomplete(config, problems)
                baseUrl.isNullOrBlank() -> incomplete(config, listOf("The base URL is missing."))
                modelId.isNullOrBlank() -> incomplete(config, listOf("The model name is missing."))
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
                            // Not chunked: a server recognizes 30 s of audio in about a second,
                            // so cutting saves next to no waiting - and services that bill per
                            // request (Tinfoil's Whisper: $0.01 each) would charge every chunk.
                            chunkable = false,
                            typicalRate = 0.08,
                            overheadMs = 900,
                        ),
                        // A host Amber can verify gets the verifier, and the engine then
                        // sends only over a connection bound to the attested key. Any other
                        // host stays plain CLOUD.
                        attestationVerifier = tinfoil.takeIf { TinfoilVerifier.supports(baseUrl) },
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
                        userMessage = "The selected Whisper model is not downloaded yet.",
                        technical = "WHISPER_CPP model $modelId is not usable",
                        remedy = SttFailure.Remedy.DOWNLOAD_MODEL,
                    ),
                )
            }
        }

        EngineKind.LOCAL_SHERPA -> if (parakeetModels.isReady()) {
            Result.success(
                ParakeetEngine(
                    id = config.id,
                    displayName = config.displayName,
                    modelDir = parakeetModels.dir.path,
                    runtime = sherpaRuntime,
                    compute = sherpaRuntime.dispatcher,
                ),
            )
        } else {
            Result.failure(
                SttFailure(
                    userMessage = "The Parakeet model is not downloaded yet.",
                    technical = "LOCAL_SHERPA model missing",
                    remedy = SttFailure.Remedy.DOWNLOAD_MODEL,
                ),
            )
        }

        EngineKind.ELEVENLABS_REALTIME -> Result.failure(
            SttFailure(
                userMessage = "Streaming through ElevenLabs comes in stage 4.",
                technical = "ELEVENLABS_REALTIME not implemented in stage 1",
            ),
        )
    }

    /**
     * The cleanup for [config], or null when none is chosen. It talks to the same service as
     * the recognition, over the same kind of channel - verified and pinned for Tinfoil.
     */
    fun cleanupFor(config: EngineConfig): ChatCleanup? {
        if (!EngineConfigValidator.isReady(config, secrets.has(config.apiKeyAlias))) return null
        val model = config.cleanupModel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (config.kind != EngineKind.OPENAI_COMPATIBLE) return null
        val baseUrl = config.baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // Recognition already refuses such an engine; the cleanup must not send its text anyway.
        if (config.requireEnclave && !EnginePresets.isEnclaveHost(baseUrl)) return null
        val channel = SecureChannel(
            baseUrl = baseUrl,
            displayName = config.displayName,
            transport = transport,
            verifier = tinfoil.takeIf { TinfoilVerifier.supports(baseUrl) },
            plainTrust = TrustLevel.CLOUD,
        )
        // The same backend for every server: only the model's own switches differ.
        val choice = ServerProviders.forUrl(baseUrl).cleanupModel(model)
        return ChatCleanup(
            baseUrl = baseUrl,
            model = model,
            channel = channel,
            apiKeyProvider = { secrets.get(config.apiKeyAlias) },
            templateKwargs = choice?.noThinking.orEmpty(),
            extraParams = choice?.extraParams.orEmpty(),
        )
    }

    private fun incomplete(config: EngineConfig, problems: List<String>): Result<SttEngine> =
        Result.failure(
            SttFailure(
                userMessage = "\"${config.displayName}\" is not ready yet. " +
                    problems.joinToString(" "),
                technical = "incomplete config for ${config.id}: $problems",
            ),
        )
}
