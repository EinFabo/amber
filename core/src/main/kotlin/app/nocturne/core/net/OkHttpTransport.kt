package app.nocturne.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

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
) : HttpTransport {

    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
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

        val okRequest = Request.Builder()
            .url(request.url)
            .method(request.method, body)
            .apply { request.headers.forEach { (name, value) -> header(name, value) } }
            .build()

        client.newCall(okRequest).execute().use { response ->
            HttpResponse(
                code = response.code,
                body = response.body?.bytes() ?: ByteArray(0),
                headers = response.headers.toMultimap().mapValues { it.value.joinToString(", ") },
            )
        }
    }
}
