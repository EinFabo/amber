package app.amethyst.core.engine

import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.net.CleartextRefusedException
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
    /**
     * How many passes a voice command may take, one language each (CommandLanguages): a
     * command is too short for the engine to detect its language. 1 for an engine that takes no
     * language per request; on a server every pass is a request, so it gets one retry at most.
     */
    val commandLanguageAttempts: Int = 1,
    /**
     * A voice command's pass may carry a short command vocabulary as the prompt
     * ([TranscribeOptions.precedingText]). Not for servers: a model that refuses a prompt costs
     * a second request.
     */
    val commandPrompt: Boolean = false,
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
    /**
     * Told how many bytes of the recording's upload have gone out, of how many - for the
     * progress ring. Engines on the phone upload nothing and never call it.
     */
    val onUpload: ((sent: Long, total: Long) -> Unit)? = null,
)

/**
 * Why a dictation failed, in terms the UI can show without leaking stack traces.
 * [problem] is what the user is told (as a value, worded by the app in the phone's language);
 * [technical] goes to the log.
 */
class SttFailure(
    val problem: Problem,
    val technical: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
    /** Something the user can do about it, so the UI can offer a button instead of prose. */
    val remedy: Remedy? = null,
) : Exception("$problem ($technical)", cause) {

    /** The audio reached the engine and it found no speech - a working round trip. */
    val nothingRecognized: Boolean get() = problem == Problem.NothingRecognized

    /**
     * The server could not be reached, did not answer, or is down - worth recognizing the
     * same recording elsewhere. A refused key or a bad request is not: that needs the user.
     * Nor is plain http refused for a public address: a setting to fix, not an outage.
     */
    val serviceUnavailable: Boolean
        get() = (statusCode == null && cause is java.io.IOException && cause !is CleartextRefusedException) ||
            statusCode in 500..599

    /**
     * Whether the recording may have reached the server before this failure. Only a failure to
     * connect at all proves it did not: a read timeout or a 5xx came after the upload, so a
     * text recognized on the phone afterwards must not claim the voice stayed there.
     */
    val recordingMayHaveLeft: Boolean
        get() = statusCode != null || generateSequence(cause) { it.cause }.none { it.isConnectFailure() }

    private fun Throwable.isConnectFailure(): Boolean =
        this is NotSentException ||
            this is java.net.UnknownHostException ||
            this is java.net.ConnectException ||
            this is java.net.NoRouteToHostException ||
            this is javax.net.ssl.SSLHandshakeException ||
            this is javax.net.ssl.SSLPeerUnverifiedException ||
            // OkHttp's connect timeout: "failed to connect to ... after 10000ms".
            (this is java.net.SocketTimeoutException && message.orEmpty().contains("connect", ignoreCase = true))

    enum class Remedy {
        /** The system recognizer lacks the offline pack for the dictation language. */
        INSTALL_LANGUAGE,

        /** A local engine is selected but its model is not on the device yet. */
        DOWNLOAD_MODEL,
    }

    companion object {
        val NOTHING_RECOGNIZED: Problem = Problem.NothingRecognized
    }
}

/** A failure that happened before a single byte of the request went out. */
open class NotSentException(message: String) : java.io.IOException(message)
