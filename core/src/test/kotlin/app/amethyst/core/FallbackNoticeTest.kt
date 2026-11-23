package app.amethyst.core

import app.amethyst.core.engine.FallbackNotice
import app.amethyst.core.engine.Problem
import app.amethyst.core.engine.ReadyFallback
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.text.Transcript
import app.amethyst.core.privacy.TrustLevel
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class FallbackNoticeTest {

    private val offline = SttFailure(Problem.NoConnection("Groq"), "unknown host", cause = IOException("unknown host"))
    private val down = SttFailure(Problem.ServiceDown(503), "HTTP 503", statusCode = 503)
    private val noCredit = SttFailure(Problem.NoCredit("Groq"), "HTTP 402", statusCode = 402)
    private val modelGone = SttFailure(Problem.ModelNotOnServer("whisper-large-v3", "Groq"), "HTTP 404", statusCode = 404)

    private fun local(serverFailure: SttFailure?) =
        ReadyFallback.Outcome.Local(Transcript(text = "hello", engineId = "parakeet", trustLevel = TrustLevel.ON_DEVICE, latencyMs = 0), serverFailure)

    @Test
    fun `the prepared text won the race against a slow server`() {
        assertEquals(FallbackNotice.ServerTooSlow, FallbackNotice.afterRace(local(null), TrustLevel.CLOUD))
        // Too slow wins over the label: the server may have the recording, and it was not gone.
        assertEquals(FallbackNotice.ServerTooSlow, FallbackNotice.afterRace(local(null), TrustLevel.ON_DEVICE))
    }

    @Test
    fun `a server that cannot serve is named with what it said`() {
        assertEquals(FallbackNotice.ServerRefused(noCredit.problem), FallbackNotice.afterRace(local(noCredit), TrustLevel.CLOUD))
        assertEquals(FallbackNotice.ServerRefused(modelGone.problem), FallbackNotice.afterRace(local(modelGone), TrustLevel.ON_DEVICE))
    }

    @Test
    fun `after the race, no connection or no answer depends on whether the recording stayed on the phone`() {
        assertEquals(FallbackNotice.NoConnection, FallbackNotice.afterRace(local(offline), TrustLevel.ON_DEVICE))
        assertEquals(FallbackNotice.NoAnswerOnPhone, FallbackNotice.afterRace(local(down), TrustLevel.CLOUD))
    }

    @Test
    fun `recognized elsewhere after a server that cannot serve`() {
        assertEquals(FallbackNotice.ServerRefused(noCredit.problem), FallbackNotice.afterFailure(noCredit, TrustLevel.CLOUD, recognizedOnPhone = true))
        assertEquals(FallbackNotice.ServerRefused(modelGone.problem), FallbackNotice.afterFailure(modelGone, TrustLevel.CLOUD, recognizedOnPhone = false))
    }

    @Test
    fun `recognized elsewhere after an outage`() {
        // The connection never opened: the recording stayed, the phone recognized.
        assertEquals(FallbackNotice.NoConnection, FallbackNotice.afterFailure(offline, TrustLevel.ON_DEVICE, recognizedOnPhone = true))
        // A 5xx after the upload: the phone recognized, but the server may have the recording.
        assertEquals(FallbackNotice.NoAnswerOnPhone, FallbackNotice.afterFailure(down, TrustLevel.CLOUD, recognizedOnPhone = true))
        // Another server took it.
        assertEquals(FallbackNotice.OtherServer, FallbackNotice.afterFailure(down, TrustLevel.CLOUD, recognizedOnPhone = false))
        assertEquals(FallbackNotice.OtherServer, FallbackNotice.afterFailure(offline, TrustLevel.CLOUD, recognizedOnPhone = false))
    }
}
