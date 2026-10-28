package app.amethyst.core.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CertificatePinner
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The real HTTP implementation of [HttpTransport].
 *
 * It lives in :core rather than in the Android module on purpose: OkHttp is an ordinary JVM
 * library, so keeping it here means the actual network path - multipart assembly, headers,
 * response handling - is covered by tests that run without a device. A transport that only
 * existed in :app would be the one piece nothing could verify.
 */
class OkHttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) : PinnableTransport {

    /** One pinned copy per host, replaced when the pinned key changes. */
    private val pinned = ConcurrentHashMap<String, Pair<String, OkHttpTransport>>()

    /**
     * OkHttp pins by the base64 SHA-256 of the SubjectPublicKeyInfo - the same hash Tinfoil's
     * attestation endorses, only hex-encoded there. The copy gets its own connection pool, so
     * no connection opened without the pin can ever be reused for a pinned request.
     *
     * Copies are kept and reused: a chunked dictation sends several requests in a row, and a
     * fresh client each time would mean a fresh TLS handshake, pool and threads per chunk.
     */
    override fun pinnedTo(host: String, spkiSha256Hex: String): HttpTransport {
        val key = spkiSha256Hex.trim().lowercase()
        pinned[host]?.let { (pinnedKey, transport) -> if (pinnedKey == key) return transport }
        val pin = "sha256/" + Base64.getEncoder().encodeToString(hexToBytes(key))
        val transport = OkHttpTransport(
            client.newBuilder()
                .connectionPool(ConnectionPool())
                .certificatePinner(CertificatePinner.Builder().add(host, pin).build())
                // A redirect would replay the body to another address, outside the pin.
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
        )
        pinned[host] = key to transport
        return transport
    }

    /**
     * Runs the call asynchronously so a cancelled coroutine cancels it - a stopped dictation
     * stops uploading, and a caller's timeout really ends the request - and bounds the whole
     * call by [HttpRequest.timeoutMs].
     */
    override suspend fun execute(request: HttpRequest): HttpResponse {
        val call = client.newCall(toOkRequest(request))
        call.timeout().timeout(request.timeoutMs, TimeUnit.MILLISECONDS)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching {
                            response.use {
                                HttpResponse(
                                    code = it.code,
                                    body = it.body?.bytes() ?: ByteArray(0),
                                    headers = it.headers.toMultimap().mapValues { h -> h.value.joinToString(", ") },
                                )
                            }
                        }
                        result.fold(continuation::resume, continuation::resumeWithException)
                    }
                },
            )
        }
    }

    private fun toOkRequest(request: HttpRequest): Request {
        val body = when (val b = request.body) {
            null -> null
            is HttpBody.Bytes -> b.bytes.toRequestBody(b.contentType.toMediaType())
            is HttpBody.Multipart -> MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .apply {
                    b.parts.forEach { part ->
                        when (part) {
                            is MultipartPart.Field -> addFormDataPart(part.name, part.value)
                            is MultipartPart.FilePart -> addFormDataPart(
                                part.name,
                                part.fileName,
                                part.bytes.toRequestBody(part.contentType.toMediaType()),
                            )
                        }
                    }
                }
                .build()
        }

        return Request.Builder()
            .url(request.url)
            .method(request.method, body)
            .apply { request.headers.forEach { (name, value) -> header(name, value) } }
            .build()
    }

    private companion object {
        fun hexToBytes(hex: String): ByteArray {
            val clean = hex.trim().lowercase()
            require(clean.length == 64 && clean.all { it in '0'..'9' || it in 'a'..'f' }) {
                "expected a SHA-256 as 64 hex characters, got \"$hex\""
            }
            return ByteArray(32) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
    }
}
