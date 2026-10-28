package app.amethyst.core.engine

import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.net.HttpBody
import app.amethyst.core.net.HttpRequest
import app.amethyst.core.net.HttpResponse
import app.amethyst.core.net.HttpTransport
import app.amethyst.core.net.MultipartPart
import app.amethyst.core.net.PinnableTransport
import app.amethyst.core.privacy.AttestationResult
import app.amethyst.core.privacy.AttestationVerifier
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.text.Transcript
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import javax.net.ssl.SSLPeerUnverifiedException

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
    ),
    private val attestationVerifier: AttestationVerifier? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SttEngine {

    /** Where a request goes and what it earns: plain, or bound to an attested key. */
    private class Channel(val transport: HttpTransport, val trust: TrustLevel)

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

        val parts = buildList {
            add(MultipartPart.FilePart("file", "dictation.wav", "audio/wav", audio.toWav()))
            add(MultipartPart.Field("model", modelId))
            add(MultipartPart.Field("response_format", "json"))
            // Whisper-family endpoints want a bare ISO-639-1 code, not a full BCP-47 tag.
            options.languageTag?.takeIf { it.isNotBlank() }?.let {
                add(MultipartPart.Field("language", it.substringBefore('-')))
            }
            options.vocabularyHint?.takeIf { it.isNotBlank() }?.let {
                add(MultipartPart.Field("prompt", it))
            }
        }

        val request = HttpRequest(
            method = "POST",
            url = endpointUrl(baseUrl),
            headers = buildMap {
                if (!apiKey.isNullOrBlank()) put("Authorization", "Bearer $apiKey")
                put("Accept", "application/json")
            },
            body = HttpBody.Multipart(parts),
        )

        var channel = openChannel(forceFresh = false).getOrElse { return Result.failure(it) }
        val response = try {
            try {
                channel.transport.execute(request)
            } catch (e: SSLPeerUnverifiedException) {
                // The server's key is not the attested one. Enclaves rotate their keys, so
                // verify afresh once; if the new proof does not match either, give up.
                if (attestationVerifier == null) throw e
                channel = openChannel(forceFresh = true).getOrElse { return Result.failure(it) }
                channel.transport.execute(request)
            }
        } catch (e: SSLPeerUnverifiedException) {
            return Result.failure(
                SttFailure(
                    userMessage = "The server of \"$displayName\" does not hold the verified " +
                        "enclave key. The recording was not sent.",
                    technical = "pin mismatch: ${e.message}",
                    cause = e,
                ),
            )
        } catch (e: Exception) {
            return Result.failure(
                SttFailure(
                    userMessage = "No connection to \"$displayName\".",
                    technical = e.toString(),
                    cause = e,
                ),
            )
        }

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
                trustLevel = channel.trust,
                latencyMs = clock() - startedAt,
                detectedLanguage = parsed.language,
                servedBy = response.header(ENCLAVE_HEADER),
            ),
        )
    }

    /** Verifies ahead of the first dictation, so it does not pay for the attestation fetch. */
    override suspend fun warmUp(): Result<Unit> {
        attestationVerifier?.let { runCatching { it.verify(baseUrl) } }
        return Result.success(Unit)
    }

    /** The current attestation, or null if this engine has no verifier configured. */
    suspend fun attestation(): AttestationResult? = attestationVerifier?.let { verify(it, forceFresh = false) }

    private suspend fun openChannel(forceFresh: Boolean): Result<Channel> {
        val verifier = attestationVerifier
            ?: return Result.success(Channel(transport, capabilities.trustLevel))

        val result = verify(verifier, forceFresh)
        val proof = result.proof
        if (!result.verified || proof == null) {
            return Result.failure(
                SttFailure(
                    userMessage = "The enclave of \"$displayName\" could not be verified. " +
                        "The recording was not sent.",
                    technical = "attestation: ${result.detail}",
                ),
            )
        }
        val host = runCatching { URI(endpointUrl(baseUrl)).host }.getOrNull()
        if (!proof.host.equals(host, ignoreCase = true)) {
            return Result.failure(
                SttFailure(
                    userMessage = "The verified enclave does not belong to the configured address.",
                    technical = "attested ${proof.host}, endpoint $host",
                ),
            )
        }
        val pinnable = transport as? PinnableTransport
            ?: return Result.failure(
                SttFailure(
                    userMessage = "This connection cannot be bound to the enclave.",
                    technical = "transport ${transport::class.simpleName} cannot pin",
                ),
            )
        return Result.success(Channel(pinnable.pinnedTo(proof.host, proof.tlsKeySha256Hex), TrustLevel.ENCLAVE_VERIFIED))
    }

    private suspend fun verify(verifier: AttestationVerifier, forceFresh: Boolean): AttestationResult =
        try {
            verifier.verify(baseUrl, forceFresh)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AttestationResult(false, "Verification failed: ${e.message}")
        }

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
        val text = obj["text"]?.jsonPrimitive?.content
            ?: error("no 'text' field in response")
        return ParsedResponse(
            text = text.trim(),
            language = obj["language"]?.jsonPrimitive?.content,
        )
    }

    companion object {
        /** Tinfoil's router names the model enclave that served a request in this header. */
        const val ENCLAVE_HEADER = "Tinfoil-Enclave"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Builds the transcription URL from whatever the user typed. Accepts a bare host,
         * a URL that already ends in /v1, and a full endpoint URL, because all three show up
         * in provider docs and none of them should be a support question.
         */
        fun endpointUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            if (trimmed.endsWith("/audio/transcriptions")) return trimmed
            val versioned = if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
            return "$versioned/audio/transcriptions"
        }
    }
}
