package app.nocturne.core

import app.nocturne.core.net.HttpRequest
import app.nocturne.core.net.HttpResponse
import app.nocturne.core.net.HttpTransport

/** Records what an engine sent and replays a canned response. */
class FakeHttpTransport(
    private val responder: (HttpRequest) -> HttpResponse,
) : HttpTransport {

    val requests = mutableListOf<HttpRequest>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests.add(request)
        return responder(request)
    }

    companion object {
        fun json(body: String, code: Int = 200) = FakeHttpTransport {
            HttpResponse(code, body.encodeToByteArray())
        }

        fun failing(exception: Exception) = FakeHttpTransport { throw exception }
    }
}
