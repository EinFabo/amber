package app.amethyst.core

import app.amethyst.core.net.HttpRequest
import app.amethyst.core.net.OkHttpTransport
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.security.MessageDigest
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The pin Tinfoil endorses is a hex SHA-256 of the server's SubjectPublicKeyInfo; OkHttp wants
 * base64. Only a real TLS handshake proves the conversion lands on the same key - a wrong pin
 * here would either lock Tinfoil out entirely or, worse, let any certificate through.
 */
class PinningTest {

    private val serverCert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val server = MockWebServer().apply {
        useHttps(HandshakeCertificates.Builder().heldCertificate(serverCert).build().sslSocketFactory(), false)
        start()
    }

    /** Trusts the test certificate the way a public CA would be trusted in production. */
    private val transport = OkHttpTransport(
        OkHttpClient.Builder()
            .sslSocketFactory(
                HandshakeCertificates.Builder().addTrustedCertificate(serverCert.certificate).build().let {
                    it.sslSocketFactory()
                },
                HandshakeCertificates.Builder().addTrustedCertificate(serverCert.certificate).build().trustManager,
            )
            .build(),
    )

    private val spkiHex = MessageDigest.getInstance("SHA-256")
        .digest(serverCert.certificate.publicKey.encoded)
        .joinToString("") { "%02x".format(it) }

    @AfterTest
    fun stop() = server.shutdown()

    private fun request() = HttpRequest("GET", server.url("/v1/models").toString())

    @Test
    fun `a pin on the presented key lets the request through`() = runTest {
        server.enqueue(MockResponse().setBody("ok"))
        val response = transport.pinnedTo("localhost", spkiHex).execute(request())
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    fun `a pin on any other key stops the request before it is sent`() = runTest {
        server.enqueue(MockResponse().setBody("ok"))
        assertFailsWith<SSLPeerUnverifiedException> {
            transport.pinnedTo("localhost", "0".repeat(64)).execute(request())
        }
        assertEquals(0, server.requestCount, "the request must not reach the server")
    }

    @Test
    fun `a malformed fingerprint is rejected rather than pinned`() {
        val error = assertFailsWith<IllegalArgumentException> { transport.pinnedTo("localhost", "abc") }
        assertTrue("64 hex" in error.message.orEmpty())
    }

    @Test
    fun `the pinned copy is reused while the key stays the same`() {
        val first = transport.pinnedTo("localhost", spkiHex)
        assertTrue(first === transport.pinnedTo("localhost", spkiHex.uppercase()))
        assertTrue(first !== transport.pinnedTo("localhost", "0".repeat(64)), "a new key gets a new copy")
    }
}
