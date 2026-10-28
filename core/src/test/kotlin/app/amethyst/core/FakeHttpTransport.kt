package app.amethyst.core

import app.amethyst.core.net.HttpRequest
import app.amethyst.core.net.HttpResponse
import app.amethyst.core.net.HttpTransport
import app.amethyst.core.net.PinnableTransport
import javax.net.ssl.SSLPeerUnverifiedException

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

/**
 * Pinning without TLS: [serverKey] is the key "the server" presents. A request through a copy
 * pinned to any other key fails the way OkHttp's CertificatePinner does.
 */
class FakePinnableTransport(
    var serverKey: String,
    private val responder: (HttpRequest) -> HttpResponse,
) : PinnableTransport {

    val requests = mutableListOf<HttpRequest>()
    val pinnedRequests = mutableListOf<Pair<String, HttpRequest>>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests.add(request)
        return responder(request)
    }

    override fun pinnedTo(host: String, spkiSha256Hex: String): HttpTransport = object : HttpTransport {
        override suspend fun execute(request: HttpRequest): HttpResponse {
            if (spkiSha256Hex != serverKey) throw SSLPeerUnverifiedException("Certificate pinning failure!")
            pinnedRequests.add(spkiSha256Hex to request)
            return responder(request)
        }
    }
}
