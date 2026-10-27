package app.nocturne.engine

import app.nocturne.core.net.HttpBody
import app.nocturne.core.net.HttpRequest
import app.nocturne.core.net.HttpResponse
import app.nocturne.core.net.HttpTransport
import app.nocturne.core.net.MultipartPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** The Android side of [HttpTransport]. Keeps OkHttp out of :core. */
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
