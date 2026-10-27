package app.nocturne.core.engine

import app.nocturne.core.audio.PcmAudio
import app.nocturne.core.privacy.TrustLevel
import app.nocturne.core.text.Transcript
import kotlinx.coroutines.flow.Flow

/**
 * One way of turning audio into text. Everything behind this interface is swappable at
 * runtime: the on-device recognizer, a local ONNX model, an OpenAI-compatible endpoint,
 * a streaming cloud service.
 */
interface SttEngine {
    val id: String
    val displayName: String
    val capabilities: EngineCapabilities

    suspend fun transcribe(
        audio: PcmAudio,
        options: TranscribeOptions = TranscribeOptions(),
    ): Result<Transcript>

    /** Load models / open connections ahead of the first dictation. */
    suspend fun warmUp(): Result<Unit> = Result.success(Unit)

    fun close() {}
}

/** An engine that can emit interim text while the user is still speaking. */
interface StreamingSttEngine : SttEngine {
    fun stream(audio: Flow<PcmAudio>, options: TranscribeOptions = TranscribeOptions()): Flow<Transcript>
}

data class EngineCapabilities(
    val trustLevel: TrustLevel,
    val streaming: Boolean = false,
    val worksOffline: Boolean = false,
    val needsApiKey: Boolean = false,
    val needsModelDownload: Boolean = false,
    val maxAudioMs: Long = 10 * 60 * 1000L,
)

data class TranscribeOptions(
    /** BCP-47 tag, or null to let the engine detect the language. */
    val languageTag: String? = null,
    /** Names and jargon passed to engines that accept a biasing prompt. */
    val vocabularyHint: String? = null,
)

/**
 * Why a dictation failed, in terms the UI can show without leaking stack traces.
 * [userMessage] is user-facing German; [technical] goes to the log.
 */
class SttFailure(
    val userMessage: String,
    val technical: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception("$userMessage ($technical)", cause)
