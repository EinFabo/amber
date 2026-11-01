package app.amethyst.core.net

/**
 * The minimum HTTP surface the engines need, as an interface.
 *
 * :core stays free of OkHttp (and of Android) so the whole engine layer can be built and
 * tested on a plain JVM. The app supplies an OkHttp-backed implementation; tests supply a fake.
 */
interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

/**
 * A transport that can hand out a copy bound to one server key. Requests through the copy
 * fail - before any request byte is written - unless the TLS server presents exactly the key
 * whose SubjectPublicKeyInfo hashes to [spkiSha256Hex].
 */
interface PinnableTransport : HttpTransport {
    fun pinnedTo(host: String, spkiSha256Hex: String): HttpTransport
}

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: HttpBody? = null,
    val timeoutMs: Long = 60_000,
    /** Told how many bytes of the body have gone out, of how many - for the progress ring. */
    val onUpload: ((sent: Long, total: Long) -> Unit)? = null,
    /**
     * Told each piece of the answer as it arrives - a streamed reply, for the progress ring.
     * The whole body still comes back in the response.
     */
    val onReceive: ((ByteArray) -> Unit)? = null,
)

sealed interface HttpBody {
    class Bytes(val bytes: ByteArray, val contentType: String) : HttpBody
    class Multipart(val parts: List<MultipartPart>) : HttpBody
}

sealed interface MultipartPart {
    val name: String

    data class Field(override val name: String, val value: String) : MultipartPart

    class FilePart(
        override val name: String,
        val fileName: String,
        val contentType: String,
        val bytes: ByteArray,
    ) : MultipartPart
}

class HttpResponse(
    val code: Int,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap(),
) {
    val isSuccessful: Boolean get() = code in 200..299

    fun bodyAsText(): String = body.decodeToString()

    /** Header lookup ignoring case; OkHttp reports names in lowercase. */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
