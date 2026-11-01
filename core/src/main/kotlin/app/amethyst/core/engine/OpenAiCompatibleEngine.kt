package app.amethyst.core.engine

import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.engine.whisper.WhisperInput
import app.amethyst.core.net.HttpBody
import app.amethyst.core.net.HttpRequest
import app.amethyst.core.net.HttpResponse
import app.amethyst.core.net.HttpTransport
import app.amethyst.core.net.MultipartPart
import app.amethyst.core.net.SecureChannel
import app.amethyst.core.privacy.AttestationResult
import app.amethyst.core.privacy.AttestationVerifier
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.text.Transcript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.CoroutineContext

/**
 * One driver for every service that implements POST /v1/audio/transcriptions.
 *
 * That single endpoint covers a self-hosted Speaches or whisper.cpp box, a Tinfoil enclave,
 * Groq, Fireworks and OpenAI itself. Only base URL, model id and key differ, and all three
 * are user-editable - so adding a provider is a settings entry, not a code change.
 *
 * With an [AttestationVerifier] the engine is strict: the audio is sent only over a connection
 * bound to the TLS key a fresh attestation endorsed, and only that earns ENCLAVE_VERIFIED. If
 * the enclave cannot be verified, nothing is sent - someone who picked an enclave engine did
 * so to keep their voice off ordinary servers, and quietly downgrading to plain HTTPS would
 * break exactly that.
 *
 * With a [CompressedUpload] (only an Amber server whose list takes it) the recording goes up as
 * Ogg Opus instead of WAV; without one, or when the encoder cannot, the request is the WAV one
 * every other service gets.
 *
 * An [amberServer] (an own server whose stored list says it is Amber PC or Amber Server) takes
 * several languages at once and detects among them; any other service takes one code or none.
 */
class OpenAiCompatibleEngine(
    override val id: String,
    override val displayName: String,
    private val baseUrl: String,
    private val modelId: String,
    private val transport: HttpTransport,
    private val apiKeyProvider: () -> String? = { null },
    override val capabilities: EngineCapabilities = EngineCapabilities(
        trustLevel = TrustLevel.CLOUD,
        needsApiKey = true,
        commandLanguageAttempts = COMMAND_LANGUAGE_ATTEMPTS,
    ),
    private val attestationVerifier: AttestationVerifier? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val compressedUpload: CompressedUpload? = null,
    private val amberServer: Boolean = false,
    /**
     * Where the recording is compressed: off the caller's thread, which may be the main one.
     * The encoder blocks - codec waits and a scratch file, up to seconds - so the IO pool.
     */
    private val encodeContext: CoroutineContext = Dispatchers.IO,
) : SttEngine {

    private val channel = SecureChannel(baseUrl, displayName, transport, attestationVerifier, capabilities.trustLevel)

    override suspend fun transcribe(
        audio: PcmAudio,
        options: TranscribeOptions,
    ): Result<Transcript> {
        val startedAt = clock()

        val apiKey = apiKeyProvider()
        if (capabilities.needsApiKey && apiKey.isNullOrBlank()) {
            return Result.failure(
                SttFailure(
                    userMessage = "No API key is stored for \"$displayName\".",
                    technical = "missing api key for engine $id",
                ),
            )
        }

        val prompt = listOfNotNull(options.vocabularyHint, options.precedingText)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .takeIf { it.isNotEmpty() }

        var compressed = compressedUpload?.let { upload -> withContext(encodeContext) { upload.encode(audio) } }
        var opusRefused = false

        fun request(withPrompt: Boolean) = HttpRequest(
            method = "POST",
            url = endpointUrl(baseUrl),
            timeoutMs = requestTimeoutMs(audio.durationMs),
            headers = buildMap {
                if (!apiKey.isNullOrBlank()) put("Authorization", "Bearer $apiKey")
                put("Accept", "application/json")
            },
            body = HttpBody.Multipart(
                buildList {
                    add(
                        compressed?.let { MultipartPart.FilePart("file", it.fileName, it.mimeType, it.bytes) }
                            ?: MultipartPart.FilePart("file", "dictation.wav", "audio/wav", audio.toWav()),
                    )
                    add(MultipartPart.Field("model", modelId))
                    add(MultipartPart.Field("response_format", "json"))
                    languageField(options.languageTag, amberServer)?.let { add(MultipartPart.Field("language", it)) }
                    // The preceding chunk's text continues the sentence; names and jargon bias
                    // spelling.
                    if (withPrompt && prompt != null) add(MultipartPart.Field("prompt", prompt))
                },
            ),
        )

        var sent = channel.send(request(withPrompt = true)).getOrElse { return Result.failure(it) }
        // A server that announced more than it takes: the same request once more as WAV, and
        // WAV from then on, until its list is fetched again. A 400 that was really about the
        // prompt counts as a refusal too - Opus stays off until the next fetch, which costs a
        // bigger upload, never a dictation.
        if (compressed != null && sent.response.code == COMPRESSION_REFUSED) {
            compressed = null
            opusRefused = true
            compressedUpload?.refused()
            sent = channel.send(request(withPrompt = true)).getOrElse { return Result.failure(it) }
        }
        // Not every model takes a prompt (Voxtral may refuse one); the text matters more.
        if (prompt != null && sent.response.code in PROMPT_REJECTED) {
            sent = channel.send(request(withPrompt = false)).getOrElse { return Result.failure(it) }
        }
        val response = sent.response

        if (!response.isSuccessful) return Result.failure(failureFor(response))

        val parsed = try {
            parseResponse(response.bodyAsText())
        } catch (e: Exception) {
            return Result.failure(
                SttFailure(
                    userMessage = "The answer from \"$displayName\" could not be read.",
                    technical = "unparseable body: ${response.bodyAsText().take(200)}",
                    statusCode = response.code,
                    cause = e,
                ),
            )
        }

        if (parsed.text.isBlank()) {
            return Result.failure(
                SttFailure(
                    userMessage = SttFailure.NOTHING_RECOGNIZED,
                    technical = "engine returned empty text",
                    statusCode = response.code,
                ),
            )
        }

        return Result.success(
            Transcript(
                text = parsed.text,
                engineId = id,
                trustLevel = sent.trust,
                latencyMs = clock() - startedAt,
                detectedLanguage = parsed.language,
                servedBy = response.header(ENCLAVE_HEADER),
                upload = UploadReport(
                    oggOpus = compressed != null,
                    bytes = compressed?.bytes?.size ?: (PcmAudio.WAV_HEADER_SIZE + audio.samples.size * 2),
                    opusRefused = opusRefused,
                ),
            ),
        )
    }

    /**
     * Opens the connection while the user speaks ([SecureChannel.warmUp]): for an enclave the
     * attestation and the pinned connection, for any other server its TLS connection - a HEAD to
     * the API root, with no Authorization header and no audio (an Amber PC or Server has its key
     * in the address's path, which the HEAD goes to like every request; never logged). A failure
     * is silent and changes nothing.
     */
    override suspend fun warmUp(): Result<Unit> =
        if (channel.warmUp(apiRoot(baseUrl))) Result.success(Unit)
        else Result.failure(SttFailure(userMessage = "No connection to \"$displayName\".", technical = "warm-up failed"))

    /** The current attestation, or null if this engine has no verifier configured. */
    suspend fun attestation(): AttestationResult? = channel.attestation()

    private fun failureFor(response: HttpResponse): SttFailure {
        val snippet = response.bodyAsText().take(300)
        val message = when (response.code) {
            401, 403 -> "The API key was rejected."
            404 -> "Endpoint not found - check the base URL."
            413 -> "The recording was too long for this service."
            422 -> "The service rejected the audio format."
            429 -> "Rate limit reached, wait a moment."
            in 500..599 -> "The service is not responding (${response.code})."
            else -> "Error ${response.code} from \"$displayName\"."
        }
        return SttFailure(message, "HTTP ${response.code}: $snippet", response.code)
    }

    private data class ParsedResponse(val text: String, val language: String?)

    private fun parseResponse(body: String): ParsedResponse {
        val obj = json.parseToJsonElement(body).jsonObject
        // contentOrNull: a JSON null is no text, not the four letters "null".
        val text = obj["text"]?.jsonPrimitive?.contentOrNull
            ?: error("no 'text' in response")
        return ParsedResponse(
            text = text.trim(),
            language = obj["language"]?.jsonPrimitive?.contentOrNull,
        )
    }

    companion object {
        /** Upload plus recognition: a minute of headroom on top of the audio's own length. */
        fun requestTimeoutMs(audioMs: Long): Long = 60_000 + audioMs

        /**
         * A voice command's passes, one language each: it takes one code per request, and each
         * pass is a request (and on some services a charge), so one retry at most.
         */
        const val COMMAND_LANGUAGE_ATTEMPTS = 2

        /**
         * The `language` field for [tag]: bare ISO 639-1 codes, as Whisper-family endpoints want
         * them, not BCP-47 tags. An Amber server gets every code ("de-DE, en" -> "de,en") and
         * detects among them; any other service takes one code, so a tag with several gets none
         * and the service detects - forcing the first would translate the others into it. Null
         * for no tag.
         */
        fun languageField(tag: String?, amberServer: Boolean): String? {
            val codes = WhisperInput.languageCodes(tag)
            return when {
                codes.isEmpty() -> null
                amberServer -> codes.joinToString(",")
                else -> codes.singleOrNull()
            }
        }

        /** Tinfoil's router names the model enclave that served a request in this header. */
        const val ENCLAVE_HEADER = "Tinfoil-Enclave"

        /** What an Amber server answers a compressed upload it does not take with. */
        private const val COMPRESSION_REFUSED = 400

        /** Answers that may mean "this model takes no prompt" - worth one retry without it. */
        private val PROMPT_REJECTED = setOf(400, 422)

        private val json = Json { ignoreUnknownKeys = true }

        private const val TRANSCRIPTIONS = "/audio/transcriptions"

        /**
         * Builds the transcription URL from whatever the user typed. Accepts a bare host,
         * a URL that already ends in /v1, and a full endpoint URL, because all three show up
         * in provider docs and none of them should be a support question.
         */
        fun endpointUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            if (trimmed.endsWith(TRANSCRIPTIONS)) return trimmed
            return apiRoot(trimmed) + TRANSCRIPTIONS
        }

        /**
         * The /v1 root of an address as typed - with or without /v1, or the full transcription
         * endpoint - that the chat endpoint and the model list hang off too.
         */
        fun apiRoot(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/').removeSuffix(TRANSCRIPTIONS)
            return if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
        }
    }
}
