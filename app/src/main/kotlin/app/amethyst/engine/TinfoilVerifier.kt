package app.amethyst.engine

import android.util.Log
import app.amethyst.core.privacy.AttestationResult
import app.amethyst.core.privacy.AttestationVerifier
import app.amethyst.core.privacy.EnclaveProof
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import sh.tinfoil.mobile.Mobile
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Verifies Tinfoil enclaves with tinfoil-go's `mobile` package (built by
 * tools/build-tinfoil-aar.sh) - the verifier Tinfoil's own Swift SDK uses. It does no I/O; this
 * class fetches the attestation document, hands it over with a fresh nonce and turns the JSON
 * it gets back into an [EnclaveProof].
 *
 * The Go side checks the hardware quote (Intel TDX or AMD SEV-SNP) up to the vendor's root,
 * the Sigstore-signed release of [TRUSTED_REPOS] in the transparency log, that the two
 * measurements match, and that the document is fresh. What comes out - above all the TLS key
 * fingerprint - is what the engine pins its connection to.
 *
 * Proofs are cached until shortly before they expire; Tinfoil's documents stay valid for days,
 * so a dictation normally pays for no verification at all.
 */
class TinfoilVerifier(
    /** Newest first, for the debug section of the engine screen. */
    private val log: MutableStateFlow<List<String>>,
    private val clock: () -> Long = System::currentTimeMillis,
) : AttestationVerifier {

    private val mutex = Mutex()
    private val proofs = HashMap<String, EnclaveProof>()

    private val _last = MutableStateFlow<AttestationResult?>(null)

    /** The outcome of the last real verification (not of cache hits), for the debug section. */
    val last: StateFlow<AttestationResult?> = _last

    /**
     * Its own client: attestation fetches must not share pooled connections with other traffic,
     * a redirect may not leave HTTPS, and a stuck server must not stall a dictation forever.
     */
    private val http = OkHttpClient.Builder()
        .callTimeout(FETCH_TIMEOUT_S, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .followSslRedirects(false)
        .build()

    /** Built on first use: loading the Go library is not free, and most users never need it. */
    private val verifier by lazy { Mobile.newVerifier("") }

    override suspend fun verify(baseUrl: String, forceFresh: Boolean): AttestationResult = mutex.withLock {
        val host = hostOf(baseUrl) ?: return@withLock failed("Not a valid address: $baseUrl")
        val repo = TRUSTED_REPOS[host]
            ?: return@withLock failed("For $host it is not known which repository the enclave must come from.")

        proofs[host]?.takeIf { !forceFresh && it.isFresh(clock()) }?.let { cached ->
            return@withLock AttestationResult(true, summary(cached), cached)
        }
        withContext(Dispatchers.IO) { verifyNow(host, repo) }.also { _last.value = it }
    }

    private fun verifyNow(host: String, repo: String): AttestationResult {
        val started = clock()
        return try {
            val nonce = Mobile.newNonce()
            val document = fetch(Mobile.attestationURL(host, "", nonce))
            val fetched = clock()
            note("Fetched attestation from $host: ${document.size / 1024} KB in ${fetched - started} ms")

            val proof = parse(host, Json.parseToJsonElement(verifier.verify(document, nonce, repo)).jsonObject)
            proofs[host] = proof
            note(
                "Verified in ${clock() - fetched} ms: ${summary(proof)}, TLS ${shortHex(proof.tlsKeySha256Hex)}, " +
                    "valid until ${formatTime(proof.freshUntilMillis)}",
            )
            AttestationResult(true, summary(proof), proof)
        } catch (e: LinkageError) {
            // A build made without tools/build-tinfoil-aar.sh, or a missing native library.
            failed("The verifier library is missing from this build (${e::class.simpleName}).")
        } catch (e: Exception) {
            proofs.remove(host)
            failed(describe(e))
        }
    }

    private fun fetch(url: String): ByteArray {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("attestation fetch: HTTP ${response.code}")
            if (!response.request.url.isHttps) throw IOException("attestation fetch was redirected to plain HTTP")
            val source = response.body?.source() ?: throw IOException("attestation fetch: empty response")
            if (source.request(MAX_DOCUMENT_BYTES + 1)) {
                throw IOException("attestation is larger than ${MAX_DOCUMENT_BYTES shr 20} MiB")
            }
            return source.buffer.readByteArray()
        }
    }

    /** Reads the JSON contract of tinfoil-go/mobile/verification.go, schema version 1. */
    private fun parse(host: String, json: JsonObject): EnclaveProof {
        val version = json["schema_version"]?.jsonPrimitive?.int
        if (version != SCHEMA_VERSION) throw IOException("unknown attestation format (version $version)")
        fun text(key: String) = json[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val measurement = json["enclave_measurement"]?.jsonObject
        return EnclaveProof(
            host = host,
            repo = text("config_repo") ?: throw IOException("attestation names no repository"),
            codeTag = text("code_tag"),
            codeDigest = text("code_digest").orEmpty(),
            measurementType = measurement?.get("type")?.jsonPrimitive?.content,
            registers = measurement?.get("registers")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            // Without it there is nothing to bind the connection to, so the proof is useless.
            tlsKeySha256Hex = text("tls_public_key_fp") ?: throw IOException("attestation endorses no TLS key"),
            hpkePublicKey = text("hpke_public_key"),
            verifiedAtMillis = text("verified_at")?.let { Instant.parse(it).toEpochMilli() } ?: clock(),
            freshUntilMillis = Instant.parse(text("freshness_expires_at") ?: throw IOException("attestation has no expiry"))
                .toEpochMilli(),
        )
    }

    /**
     * tinfoil-go prefixes its errors and names the layer that rejected the document
     * ("verification error: quote: ..."); the layer says more than the rest of the message.
     */
    private fun describe(e: Exception): String {
        val message = e.message.orEmpty()
        val layer = Regex("""verification error: (\w+):""").find(message)?.groupValues?.get(1)
        val where = when (layer) {
            "envelope" -> "Attestation document invalid"
            "provenance" -> "Provenance not confirmed (Sigstore/release)"
            "quote" -> "Hardware quote rejected"
            "policy" -> "Policy violated"
            else -> if (e is IOException) "Attestation unavailable" else "Verification failed"
        }
        return "$where: $message"
    }

    private fun failed(detail: String): AttestationResult {
        note("Rejected: $detail")
        return AttestationResult(false, detail)
    }

    private fun summary(proof: EnclaveProof): String =
        "${proof.repo}@${proof.codeTag ?: proof.codeDigest.take(12)} · ${proof.platform}"

    private fun note(line: String) {
        Log.i(TAG, "tinfoil: $line")
        log.value = (listOf("${CLOCK.format(Instant.ofEpochMilli(clock()))}  $line") + log.value).take(LOG_LINES)
    }

    private fun formatTime(millis: Long): String = DATE.format(Instant.ofEpochMilli(millis))

    companion object {
        private const val TAG = "Amber"
        private const val SCHEMA_VERSION = 1
        private const val FETCH_TIMEOUT_S = 30L
        private const val MAX_DOCUMENT_BYTES = 32L shl 20
        private const val LOG_LINES = 12

        private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
        private val DATE = DateTimeFormatter.ofPattern("MMM d, HH:mm", java.util.Locale.ENGLISH).withZone(ZoneId.systemDefault())

        /**
         * The hosts Amber can verify, each with the repository its enclave must have been built
         * from. The router is the trust anchor: it verifies each model enclave itself before
         * forwarding (the trusted model repos are compiled into its measured code).
         */
        val TRUSTED_REPOS = mapOf("inference.tinfoil.sh" to "tinfoilsh/confidential-model-router")

        fun hostOf(url: String?): String? =
            runCatching { URI(url.orEmpty().trim()).host?.lowercase() }.getOrNull()

        fun supports(baseUrl: String?): Boolean = hostOf(baseUrl) in TRUSTED_REPOS

        fun shortHex(hex: String): String = if (hex.length > 16) "${hex.take(8)}…${hex.takeLast(8)}" else hex
    }
}
