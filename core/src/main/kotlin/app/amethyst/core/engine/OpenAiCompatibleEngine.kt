package app.amethyst.core.engine

import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.net.HttpBody
import app.amethyst.core.net.HttpRequest
import app.amethyst.core.net.HttpResponse
import app.amethyst.core.net.HttpTransport
import app.amethyst.core.net.MultipartPart
import app.amethyst.core.privacy.AttestationResult
import app.amethyst.core.privacy.AttestationVerifier
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.text.Transcript
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One driver for every service that implements POST /v1/audio/transcriptions.
 *
 * That single endpoint covers a self-hosted Speaches or whisper.cpp box, a Tinfoil enclave,
 * Groq, Fireworks and OpenAI itself. Only base URL, model id and key differ, and all three
 * are user-editable - so adding a provider is a settings entry, not a code change.
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

    @Volatile
    private var cachedAttestation: AttestationResult? = null

    override suspend fun transcribe(
        audio: PcmAudio,
        options: TranscribeOptions,
    ): Result<Transcript> {
        val startedAt = clock()

        val apiKey = apiKeyProvider()
        if (capabilities.needsApiKey && apiKey.isNullOrBlank()) {
            return Result.failure(
                SttFailure(
                    userMessage = "Für \"$displayName\" ist kein API-Schlüssel hinterlegt.",
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

        val response = try {
            transport.execute(request)
        } catch (e: Exception) {
            return Result.failure(
                SttFailure(
                    userMessage = "Keine Verbindung zu \"$displayName\".",
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
                    userMessage = "Antwort von \"$displayName\" war unverständlich.",
                    technical = "unparseable body: ${response.bodyAsText().take(200)}",
                    statusCode = response.code,
                    cause = e,
                ),
            )
        }

        if (parsed.text.isBlank()) {
            return Result.failure(
                SttFailure(
                    userMessage = "Es wurde nichts erkannt.",
                    technical = "engine returned empty text",
                    statusCode = response.code,
                ),
            )
        }

        return Result.success(
            Transcript(
                text = parsed.text,
                engineId = id,
                trustLevel = resolveTrustLevel(),
                latencyMs = clock() - startedAt,
                detectedLanguage = parsed.language,
            ),
        )
    }

    override suspend fun warmUp(): Result<Unit> {
        if (attestationVerifier != null) verifyAttestation()
        return Result.success(Unit)
    }

    /** The verified attestation, or null if this engine has no verifier configured. */
    suspend fun attestation(): AttestationResult? =
        if (attestationVerifier == null) null else verifyAttestation()

    private suspend fun verifyAttestation(): AttestationResult {
        cachedAttestation?.let { return it }
        val verifier = attestationVerifier ?: return AttestationResult(false, "kein Prüfer konfiguriert")
        val result = try {
            verifier.verify(baseUrl)
        } catch (e: Exception) {
            AttestationResult(false, "Prüfung fehlgeschlagen: ${e.message}")
        }
        cachedAttestation = result
        return result
    }

    /**
     * Never upgrades a label optimistically: without a passing attestation this stays CLOUD,
     * even for an endpoint that advertises an enclave.
     */
    private fun resolveTrustLevel(): TrustLevel = when {
        attestationVerifier == null -> capabilities.trustLevel
        cachedAttestation?.verified == true -> TrustLevel.ENCLAVE_VERIFIED
        else -> TrustLevel.CLOUD
    }

    private fun failureFor(response: HttpResponse): SttFailure {
        val snippet = response.bodyAsText().take(300)
        val message = when (response.code) {
            401, 403 -> "API-Schlüssel wurde abgelehnt."
            404 -> "Endpunkt nicht gefunden - Base-URL prüfen."
            413 -> "Aufnahme war zu lang für diesen Dienst."
            422 -> "Dienst hat das Audioformat abgelehnt."
            429 -> "Rate-Limit erreicht, kurz warten."
            in 500..599 -> "Dienst antwortet nicht (${response.code})."
            else -> "Fehler ${response.code} von \"$displayName\"."
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
