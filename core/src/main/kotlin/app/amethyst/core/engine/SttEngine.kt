package app.amethyst.core.engine

import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.text.Transcript
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
    /**
     * Parts of a recording may be transcribed separately while it is still running (see
     * ChunkedTranscription). True for engines that take a recording and return text; false for
     * engines that capture by themselves.
     */
    val chunkable: Boolean = false,
    /**
     * The shortest chunk worth cutting off. Short chunks only pay off when a short chunk is also
     * cheap to compute; an engine that always processes a full window should get long ones.
     */
    val minChunkMs: Int = 4_000,
    /** Typical milliseconds of work per millisecond of audio, before this phone taught better. */
    val typicalRate: Double = 0.15,
    /** Fixed cost per request on top of that: a network round trip, a warm-up. */
    val overheadMs: Long = 200,
)

data class TranscribeOptions(
    /** BCP-47 tag, or null to let the engine detect the language. */
    val languageTag: String? = null,
    /** Names and jargon passed to engines that accept a biasing prompt. */
    val vocabularyHint: String? = null,
    /**
     * The text recognized just before this audio, when it is one chunk of a longer dictation:
     * passed as the engine's prompt so sentence flow and spellings hold across the cut.
     */
    val precedingText: String? = null,
)

/**
 * Why a dictation failed, in terms the UI can show without leaking stack traces.
 * [userMessage] is user-facing; [technical] goes to the log.
 */
class SttFailure(
    val userMessage: String,
    val technical: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
    /** Something the user can do about it, so the UI can offer a button instead of prose. */
    val remedy: Remedy? = null,
) : Exception("$userMessage ($technical)", cause) {

    /** The audio reached the engine and it found no speech - a working round trip. */
    val nothingRecognized: Boolean get() = userMessage == NOTHING_RECOGNIZED

    /**
     * The server could not be reached, did not answer, or is down - worth recognizing the
     * same recording elsewhere. A refused key or a bad request is not: that needs the user.
     */
    val serviceUnavailable: Boolean
        get() = (statusCode == null && cause is java.io.IOException) || statusCode in 500..599

    enum class Remedy {
        /** The system recognizer lacks the offline pack for the dictation language. */
        INSTALL_LANGUAGE,

        /** A local engine is selected but its model is not on the device yet. */
        DOWNLOAD_MODEL,
    }

    companion object {
        const val NOTHING_RECOGNIZED = "Nothing was recognized."
    }
}
