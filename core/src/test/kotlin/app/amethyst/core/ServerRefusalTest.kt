package app.amethyst.core

import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.engine.Fallback
import app.amethyst.core.engine.OpenAiCompatibleEngine
import app.amethyst.core.engine.Problem
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.FallbackMode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerRefusalTest {

    private val audio = PcmAudio(ShortArray(16_000) { 1_000 })

    private suspend fun failure(status: Int, body: String): SttFailure {
        val engine = OpenAiCompatibleEngine(
            id = "groq",
            displayName = "Groq",
            baseUrl = "https://api.example.com/openai/v1",
            modelId = "distil-whisper-large-v3-en",
            transport = FakeHttpTransport.json(body, status),
            apiKeyProvider = { "key" },
        )
        return assertIs<SttFailure>(engine.transcribe(audio).exceptionOrNull())
    }

    private fun mayFallBack(failure: SttFailure) = Fallback.recoverable(failure, TrustLevel.CLOUD, FallbackMode.AUTOMATIC) != null

    @Test
    fun `a 404 for a retired model says the model is gone, not that the address is wrong`() = runTest {
        val failure = failure(
            404,
            """{"error":{"message":"The model `distil-whisper-large-v3-en` does not exist or you do not have access to it.","type":"invalid_request_error","code":"model_not_found"}}""",
        )
        assertEquals(Problem.ModelNotOnServer("distil-whisper-large-v3-en", "Groq"), failure.problem)
        assertEquals(404, failure.statusCode)
        assertTrue(mayFallBack(failure), "a model on the phone takes the recording")
        assertTrue(failure.recordingMayHaveLeft, "the label says the recording reached the server")
    }

    @Test
    fun `a decommissioned model answered with 400 is the same problem`() = runTest {
        val failure = failure(
            400,
            """{"error":{"message":"The model `distil-whisper-large-v3-en` has been decommissioned and is no longer supported.","type":"invalid_request_error","code":"model_decommissioned"}}""",
        )
        assertIs<Problem.ModelNotOnServer>(failure.problem)
        assertTrue(mayFallBack(failure))
    }

    @Test
    fun `servers without error codes are read by what they say about the model`() = runTest {
        assertIs<Problem.ModelNotOnServer>(failure(404, """{"error":"model 'whisper-9' not found, try pulling it first"}""").problem)
        assertIs<Problem.ModelNotOnServer>(failure(404, """{"detail":"Model 'Systran/faster-whisper-tiny' is not installed locally."}""").problem)
        assertIs<Problem.ModelNotOnServer>(failure(400, """{"detail":"Unknown model whisper-9"}""").problem)
    }

    @Test
    fun `a 404 that knows nothing of a model is still a wrong address, and no fallback`() = runTest {
        for (body in listOf("404 page not found", """{"detail":"Not Found"}""", "<html><body>Not Found</body></html>")) {
            val failure = failure(404, body)
            assertEquals(Problem.AddressNotFound, failure.problem, body)
            assertFalse(mayFallBack(failure), body)
        }
    }

    @Test
    fun `a 400 that merely mentions the model stays a plain error`() = runTest {
        val failure = failure(400, """{"error":{"message":"Invalid file format for this model.","type":"invalid_request_error"}}""")
        assertEquals(Problem.ServerError(400, "Groq"), failure.problem)
        assertFalse(mayFallBack(failure))
    }

    @Test
    fun `a 429 for an account without credit says so, not to wait a moment`() = runTest {
        val failure = failure(
            429,
            """{"error":{"message":"You exceeded your current quota, please check your plan and billing details.","type":"insufficient_quota","param":null,"code":"insufficient_quota"}}""",
        )
        assertEquals(Problem.NoCredit("Groq"), failure.problem)
        assertTrue(mayFallBack(failure))
    }

    @Test
    fun `a 402 and a spend limit are no credit too`() = runTest {
        assertEquals(Problem.NoCredit("Groq"), failure(402, "Payment Required").problem)
        assertEquals(Problem.NoCredit("Groq"), failure(429, """{"error":{"message":"Monthly spend limit reached."}}""").problem)
    }

    @Test
    fun `a 429 for too many requests still says to wait, and takes no fallback`() = runTest {
        val failure = failure(
            429,
            """{"error":{"message":"Rate limit reached for model `whisper-large-v3` in organization org_1 on requests per minute.","type":"requests","code":"rate_limit_exceeded"}}""",
        )
        assertEquals(Problem.RateLimited, failure.problem)
        assertFalse(mayFallBack(failure))
    }

    @Test
    fun `a refused key stays a refused key and never falls back silently`() = runTest {
        val wrongKey = failure(401, """{"error":{"message":"Incorrect API key provided.","type":"invalid_request_error","code":"invalid_api_key"}}""")
        assertEquals(Problem.KeyRejected("Groq"), wrongKey.problem)
        assertFalse(mayFallBack(wrongKey))
        // Also when the answer speaks of the model, or of credit.
        val noAccess = failure(403, """{"error":{"message":"You do not have access to model whisper-1; insufficient quota.","code":"model_not_found"}}""")
        assertEquals(Problem.KeyRejected("Groq"), noAccess.problem)
        assertFalse(mayFallBack(noAccess))
        // And when a server answers a bad key with another status.
        assertEquals(Problem.KeyRejected("Groq"), failure(400, """{"error":{"message":"bad key","code":"invalid_api_key"}}""").problem)
    }

    @Test
    fun `a server that is down is down, whatever its error page says`() = runTest {
        val failure = failure(503, """{"error":{"message":"model not found in cache, insufficient credit","code":"model_not_found"}}""")
        assertEquals(Problem.ServiceDown(503), failure.problem)
        assertTrue(mayFallBack(failure))
    }

    @Test
    fun `a server that cannot serve is no outage - the user is told what it said`() {
        val gone = SttFailure(Problem.ModelNotOnServer("m", "Groq"), "HTTP 404", statusCode = 404)
        assertTrue(gone.serverCannotServe)
        assertFalse(gone.serviceUnavailable)
        assertNotNull(Fallback.recoverable(gone, TrustLevel.ENCLAVE_VERIFIED, FallbackMode.ENGINE))
        assertNull(Fallback.recoverable(gone, TrustLevel.CLOUD, FallbackMode.OFF), "the fallback is off")
        assertFalse(SttFailure(Problem.KeyRejected("Groq"), "HTTP 401", statusCode = 401).serverCannotServe)
        assertFalse(SttFailure(Problem.RateLimited, "HTTP 429", statusCode = 429).serverCannotServe)
    }
}
