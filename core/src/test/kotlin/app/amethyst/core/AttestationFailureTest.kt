package app.amethyst.core

import app.amethyst.core.privacy.AttestationFailure
import kotlin.test.Test
import kotlin.test.assertEquals

class AttestationFailureTest {
    @Test
    fun `each layer the verifier names becomes its own failure`() {
        fun of(layer: String) = AttestationFailure.fromVerifier("verification error: $layer: boom", network = false)
        assertEquals(AttestationFailure.DocumentInvalid, of("envelope"))
        assertEquals(AttestationFailure.ReleaseNotConfirmed, of("provenance"))
        assertEquals(AttestationFailure.HardwareRejected, of("quote"))
        assertEquals(AttestationFailure.PolicyViolated, of("policy"))
    }

    @Test
    fun `an unknown layer or no layer is a failed verification, or an unavailable document on the network`() {
        assertEquals(AttestationFailure.Failed, AttestationFailure.fromVerifier("verification error: other: x", network = false))
        assertEquals(AttestationFailure.Failed, AttestationFailure.fromVerifier("something else", network = false))
        assertEquals(AttestationFailure.Unavailable, AttestationFailure.fromVerifier("HTTP 404", network = true))
    }

    @Test
    fun `the layer wins over the network flag, as it does in the verifier's own errors`() {
        assertEquals(
            AttestationFailure.HardwareRejected,
            AttestationFailure.fromVerifier("verification error: quote: bad", network = true),
        )
    }
}
