package app.amethyst.engine

import android.content.Context
import app.amethyst.core.audio.AudioEncoder
import app.amethyst.core.engine.AmberCatalogs
import app.amethyst.core.engine.ChatDialect
import app.amethyst.core.engine.ConfigIssue
import app.amethyst.core.engine.EngineCapabilities
import app.amethyst.core.engine.EngineConfigValidator
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.KeyBinding
import app.amethyst.core.engine.OpenAiCompatibleEngine
import app.amethyst.core.engine.Problem
import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.engine.Servers
import app.amethyst.core.engine.SttEngine
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.engine.sherpa.ParakeetEngine
import app.amethyst.core.engine.whisper.WhisperCppEngine
import app.amethyst.core.engine.whisper.WhisperInput
import app.amethyst.core.engine.whisper.WhisperModels
import app.amethyst.core.net.HttpTransport
import app.amethyst.core.net.SecureChannel
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.text.ChatCleanup
import app.amethyst.core.text.ClaudeCleanup
import app.amethyst.core.text.TextCleanup
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
    /** Compresses recordings for an Amber server whose list takes it; null where it cannot. */
    private val encoder: AudioEncoder?,
    private val settings: () -> AmethystSettings,
    /** An engine's server answered a compressed upload with 400: (engine id, address). */
    private val compressionRefused: (String, String) -> Unit,
) {

    fun create(config: EngineConfig): Result<SttEngine> = when (config.kind) {
        EngineKind.ANDROID_ON_DEVICE -> Result.success(AndroidOnDeviceEngine(context))

        EngineKind.OPENAI_COMPATIBLE -> {
            val problems = EngineConfigValidator.validate(config, secrets.has(config.apiKeyAlias))
            val baseUrl = config.baseUrl?.trim()
            val modelId = config.modelId?.trim()
            when {
                problems.isNotEmpty() -> incomplete(config, problems)
                baseUrl.isNullOrBlank() -> incomplete(config, listOf(ConfigIssue.AddressMissing))
                modelId.isNullOrBlank() -> incomplete(config, listOf(ConfigIssue.ModelNameMissing))
                else -> Result.success(
                    OpenAiCompatibleEngine(
                        id = config.id,
                        displayName = config.displayName,
                        baseUrl = baseUrl,
                        modelId = modelId,
                        transport = transport,
                        // Only to the host the key was given for; the validator above says so too.
                        apiKeyProvider = { secrets.get(config.apiKeyAlias)?.takeIf { KeyBinding.allows(config) } },
                        capabilities = EngineCapabilities(
                            trustLevel = TrustLevel.CLOUD,
                            // A known service authenticates; an own server is never nagged for a
                            // key, and gets one when it was given.
                            needsApiKey = Servers.keyRequired(config),
                            // Not chunked: a server recognizes 30 s of audio in about a second,
                            // so cutting saves next to no waiting - and services that bill per
                            // request (Tinfoil's Whisper: $0.01 each) would charge every chunk.
                            chunkable = false,
                            typicalRate = 0.08,
                            overheadMs = 900,
                            commandLanguageAttempts = OpenAiCompatibleEngine.COMMAND_LANGUAGE_ATTEMPTS,
                        ),
                        // A host Amber can verify gets the verifier, and the engine then
                        // sends only over a connection bound to the attested key. Any other
                        // host stays plain CLOUD.
                        attestationVerifier = tinfoil.takeIf { EnginePresets.enclaveRepoFor(baseUrl) != null },
                        // Ogg Opus only for an own server whose stored list takes it; every
                        // other server - Groq, OpenAI, Tinfoil, any plain own one - gets WAV.
                        compressedUpload = AmberCatalogs.compressedUpload(settings(), config, encoder) {
                            compressionRefused(config.id, baseUrl)
                        },
                        amberServer = AmberCatalogs.isAmber(settings(), config),
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
                        typicalRate = WhisperModels.byId(modelId)?.typicalRate ?: 3.5,
                        contextSlack = WhisperModels.byId(modelId)?.contextSlack ?: WhisperInput.AUDIO_CTX_SLACK,
                        compute = whisperRuntime.dispatcher,
                    ),
                )
            } else {
                Result.failure(
                    SttFailure(
                        problem = Problem.WhisperModelMissing,
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
                    problem = Problem.ParakeetModelMissing,
                    technical = "LOCAL_SHERPA model missing",
                    remedy = SttFailure.Remedy.DOWNLOAD_MODEL,
                ),
            )
        }

        EngineKind.ELEVENLABS_REALTIME -> Result.failure(
            SttFailure(
                problem = Problem.NotSupported,
                technical = "ELEVENLABS_REALTIME not implemented in stage 1",
            ),
        )
    }

    /**
     * The cleanup for [choice] - a chat model on one of the configured server engines, used
     * with that engine's address, key and enclave check, whichever engine recognized. Null
     * when its engine is gone or not ready. Which choice applies decides
     * [app.amethyst.core.engine.CleanupRouting].
     */
    fun cleanupFor(choice: CleanupChoice, settings: AmethystSettings): TextCleanup? {
        val config = settings.engines.firstOrNull { it.id == choice.engineId } ?: return null
        if (!EngineConfigValidator.readyForCleanup(config, secrets.has(config.apiKeyAlias))) return null
        val model = choice.model.trim().takeIf { it.isNotEmpty() } ?: return null
        if (config.kind != EngineKind.OPENAI_COMPATIBLE) return null
        val baseUrl = config.baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // Recognition already refuses such an engine; the cleanup must not send its text anyway.
        if (config.requireEnclave && !EnginePresets.isEnclaveHost(baseUrl)) return null
        val channel = SecureChannel(
            baseUrl = baseUrl,
            displayName = config.displayName,
            transport = transport,
            verifier = tinfoil.takeIf { EnginePresets.enclaveRepoFor(baseUrl) != null },
            plainTrust = TrustLevel.CLOUD,
        )
        val provider = ServerProviders.forUrl(baseUrl)
        val known = provider.cleanupModel(model)
        // Only to the host the key was given for.
        val key = { secrets.get(config.apiKeyAlias)?.takeIf { KeyBinding.allows(config) } }
        return when (provider.chat) {
            // Claude in its own Messages API.
            ChatDialect.ANTHROPIC -> ClaudeCleanup(
                baseUrl = baseUrl,
                model = model,
                channel = channel,
                apiKeyProvider = key,
                service = config.displayName,
                thinkingOff = known?.thinkingOff,
            )
            // The same backend for every other server: only the model's own switches differ.
            ChatDialect.OPENAI -> ChatCleanup(
                baseUrl = baseUrl,
                model = model,
                channel = channel,
                apiKeyProvider = key,
                templateKwargs = known?.noThinking.orEmpty(),
                extraParams = known?.extraParams.orEmpty(),
                // A streamed reply lets the ring follow the cleanup; Amber PC's answers in one piece.
                stream = provider.streamsChat,
            )
        }
    }

    private fun incomplete(config: EngineConfig, problems: List<ConfigIssue>): Result<SttEngine> =
        Result.failure(
            SttFailure(
                problem = Problem.NotReady(config.displayName, problems),
                technical = "incomplete config for ${config.id}: $problems",
            ),
        )
}
