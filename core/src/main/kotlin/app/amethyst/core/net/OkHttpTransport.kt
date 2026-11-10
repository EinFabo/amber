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
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
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
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // A 307/308 would replay the recording to wherever the server points, https to http
        // included. Speech and cleanup endpoints do not redirect; a wrong address should fail.
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) : PinnableTransport {

    /** Whatever client is passed in: plain http to the internet stays refused. */
    internal val client: OkHttpClient =
        if (client.networkInterceptors.any { it is CleartextGuard }) client else client.newBuilder().guardCleartext().build()

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
        val call = clientFor(request).newCall(toOkRequest(request))
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
                                    body = it.body?.let { b -> boundedBytes(b, request.onReceive) } ?: ByteArray(0),
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

    /**
     * A server answers a long recording only once it has recognized all of it, and sends no
     * byte meanwhile: the client's read timeout would end that wait long before the request's
     * own limit - five minutes of audio on a PC's CPU take longer than two. A request allowed
     * more than the read timeout may wait silently for as long as it is allowed; the call
     * timeout still bounds it. Same pool and threads, only the timeout differs.
     */
    internal fun clientFor(request: HttpRequest): OkHttpClient =
        if (request.timeoutMs <= client.readTimeoutMillis) {
            client
        } else {
            client.newBuilder().readTimeout(request.timeoutMs, TimeUnit.MILLISECONDS).build()
        }

    /**
     * The body, refused beyond [MAX_RESPONSE_BYTES]: a transcript or a cleanup reply is a few
     * kilobytes, and an endless answer from a broken or hostile server must not run the
     * process - which also hosts the accessibility service - out of memory.
     */
    private fun boundedBytes(body: okhttp3.ResponseBody, onReceive: ((ByteArray) -> Unit)?): ByteArray {
        val source = body.source()
        if (onReceive == null) {
            if (source.request(MAX_RESPONSE_BYTES + 1)) throw tooLarge()
            return source.buffer.readByteArray()
        }
        // Piece by piece as it arrives, each passed on: a streamed reply.
        val whole = Buffer()
        val piece = Buffer()
        while (source.read(piece, PIECE_BYTES) != -1L) {
            if (whole.size + piece.size > MAX_RESPONSE_BYTES) throw tooLarge()
            val bytes = piece.readByteArray()
            onReceive(bytes)
            whole.write(bytes)
        }
        return whole.readByteArray()
    }

    private fun tooLarge() = IOException("response larger than ${MAX_RESPONSE_BYTES shr 20} MiB")

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

        val counted = body?.let { b -> request.onUpload?.let { CountingBody(b, it) } } ?: body
        return Request.Builder()
            .url(request.url)
            .method(request.method, counted)
            .apply { request.headers.forEach { (name, value) -> header(name, value) } }
            .build()
    }

    /**
     * [body] as it is, telling [onUpload] the bytes written so far. A retried request writes
     * the body again, and the count starts again with it.
     */
    private class CountingBody(private val body: RequestBody, private val onUpload: (Long, Long) -> Unit) : RequestBody() {
        override fun contentType() = body.contentType()

        override fun contentLength() = body.contentLength()

        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            var sent = 0L
            val counting = object : ForwardingSink(sink) {
                // Okio hands a large body over in one write; in steps, the ring moves with it.
                override fun write(source: Buffer, byteCount: Long) {
                    var left = byteCount
                    while (left > 0) {
                        val step = minOf(left, PIECE_BYTES)
                        super.write(source, step)
                        left -= step
                        sent += step
                        onUpload(sent, total)
                    }
                }
            }.buffer()
            body.writeTo(counting)
            counting.flush()
        }
    }

    private companion object {
        /** Far beyond any transcript or cleanup reply; see [boundedBytes]. */
        const val MAX_RESPONSE_BYTES = 8L shl 20

        /** How much of a streamed reply is read at a time. */
        const val PIECE_BYTES = 8_192L

        fun hexToBytes(hex: String): ByteArray {
            val clean = hex.trim().lowercase()
            require(clean.length == 64 && clean.all { it in '0'..'9' || it in 'a'..'f' }) {
                "expected a SHA-256 as 64 hex characters, got \"$hex\""
            }
            return ByteArray(32) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
    }
}
