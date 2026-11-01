package app.amethyst.core

import app.amethyst.core.engine.EngineCapabilities
import app.amethyst.core.engine.OpenAiCompatibleEngine
import app.amethyst.core.net.ConnectionWarmUp
import app.amethyst.core.net.HttpResponse
import app.amethyst.core.net.HttpTransport
import app.amethyst.core.net.SecureChannel
import app.amethyst.core.privacy.AttestationResult
import app.amethyst.core.privacy.AttestationVerifier
import app.amethyst.core.privacy.EnclaveProof
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.text.ChatCleanup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The connection a request will take, opened while the user still speaks: a HEAD to the API
 * root that carries no key and no audio - for an enclave after the attestation, through the
 * pinned client. Failing changes nothing.
 */
class ConnectionWarmUpTest {

    private val keyA = "a".repeat(64)

    private fun engine(transport: HttpTransport, baseUrl: String = "https://api.groq.com/openai/v1", verifier: AttestationVerifier? = null) =
        OpenAiCompatibleEngine(
            id = "groq",
            displayName = "Groq",
            baseUrl = baseUrl,
            modelId = "whisper-large-v3-turbo",
            transport = transport,
            apiKeyProvider = { "secret" },
            capabilities = EngineCapabilities(TrustLevel.CLOUD, needsApiKey = true),
            attestationVerifier = verifier,
        )

    private class Verifier(private val key: String?) : AttestationVerifier {
        var calls = 0
        override suspend fun verify(baseUrl: String, forceFresh: Boolean): AttestationResult {
            calls++
            val key = key ?: return AttestationResult(false, "quote: signature invalid")
            val proof = EnclaveProof(
                host = "inference.tinfoil.sh", repo = "tinfoilsh/confidential-model-router", codeTag = "v1", codeDigest = "abc",
                measurementType = null, registers = emptyList(), tlsKeySha256Hex = key, hpkePublicKey = null,
                verifiedAtMillis = 0, freshUntilMillis = Long.MAX_VALUE,
            )
            return AttestationResult(true, "ok", proof)
        }
    }

    @Test
    fun `a server gets one HEAD to its API root, without the key or any audio`() = runTest {
        val transport = FakeHttpTransport { HttpResponse(404, ByteArray(0)) }

        assertTrue(engine(transport).warmUp().isSuccess, "any answer means the connection is open")

        val request = transport.requests.single()
        assertEquals("HEAD", request.method)
        assertEquals("https://api.groq.com/openai/v1", request.url)
        assertNull(request.body)
        assertTrue(request.headers.keys.none { it.equals("Authorization", ignoreCase = true) })
    }

    @Test
    fun `the API root is found from every shape of address`() = runTest {
        for (address in listOf("https://host.example", "https://host.example/v1/", "https://host.example/v1/audio/transcriptions")) {
            val transport = FakeHttpTransport { HttpResponse(200, ByteArray(0)) }
            engine(transport, baseUrl = address).warmUp()
            assertEquals("https://host.example/v1", transport.requests.single().url, address)
        }
    }

    @Test
    fun `a warm-up that cannot connect fails quietly`() = runTest {
        assertTrue(engine(FakeHttpTransport.failing(IOException("unreachable"))).warmUp().isFailure)
    }

    @Test
    fun `an enclave is verified and its pinned connection opened - nothing goes out unpinned`() = runTest {
        val transport = FakePinnableTransport(serverKey = keyA) { HttpResponse(404, ByteArray(0)) }
        val verifier = Verifier(keyA)

        assertTrue(engine(transport, "https://inference.tinfoil.sh/v1", verifier).warmUp().isSuccess)

        assertEquals(1, verifier.calls)
        val (pin, request) = transport.pinnedRequests.single()
        assertEquals(keyA, pin)
        assertEquals("HEAD", request.method)
        assertNull(request.headers["Authorization"])
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `an enclave that cannot be verified gets nothing at all`() = runTest {
        val transport = FakePinnableTransport(serverKey = keyA) { HttpResponse(200, ByteArray(0)) }

        assertTrue(engine(transport, "https://inference.tinfoil.sh/v1", Verifier(null)).warmUp().isFailure)
        assertTrue(transport.requests.isEmpty() && transport.pinnedRequests.isEmpty())
    }

    @Test
    fun `a server holding another key than the attested one fails the warm-up`() = runTest {
        val transport = FakePinnableTransport(serverKey = "c".repeat(64)) { HttpResponse(200, ByteArray(0)) }

        assertTrue(engine(transport, "https://inference.tinfoil.sh/v1", Verifier(keyA)).warmUp().isFailure)
    }

    @Test
    fun `a cleanup warms its chat server the same way`() = runTest {
        val transport = FakeHttpTransport { HttpResponse(401, ByteArray(0)) }
        val cleanup = ChatCleanup(
            baseUrl = "https://api.groq.com/openai/v1/",
            model = "llama",
            channel = SecureChannel("https://api.groq.com/openai/v1/", "Groq", transport, null, TrustLevel.CLOUD),
            apiKeyProvider = { "secret" },
        )

        assertTrue(cleanup.warmUp())
        val request = transport.requests.single()
        assertEquals("HEAD", request.method)
        assertEquals("https://api.groq.com/openai/v1", request.url)
        assertNull(request.headers["Authorization"])
    }

    @Test
    fun `the trace says whether the connection was ready when the request went out`() = runTest {
        val done = CompletableDeferred<Boolean>()
        val running = ConnectionWarmUp.start(this) { done.await() }
        advanceUntilIdle()
        assertEquals(ConnectionWarmUp.State.RUNNING, running.state)
        assertEquals("connection still warming up", running.describe())

        done.complete(true)
        advanceUntilIdle()
        assertEquals(ConnectionWarmUp.State.WARM, running.state)
        assertEquals("connection warm", running.describe())

        val refused = ConnectionWarmUp.start(this) { false }
        val thrown = ConnectionWarmUp.start(this) { throw IOException("no route") }
        advanceUntilIdle()
        for (failed in listOf(refused, thrown)) {
            assertEquals(ConnectionWarmUp.State.FAILED, failed.state)
            assertEquals("connection cold (warm-up failed)", failed.describe())
        }
        assertFalse(thrown.state == ConnectionWarmUp.State.WARM)
    }
}
