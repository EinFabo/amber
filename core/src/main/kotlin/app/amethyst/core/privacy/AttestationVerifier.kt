package app.amethyst.core.privacy

/**
 * Checks that a remote endpoint really is the enclave it claims to be.
 *
 * Tinfoil ships SDKs for Go, Python, JS and Swift that compare a hardware-rooted
 * attestation against measurements in a public transparency log - but none for Kotlin.
 * Amethyst therefore owns this interface: Stage 2 runs without a verifier (and says so, by
 * labelling the transcript CLOUD), Stage 4 plugs in a real implementation and the engine
 * starts reporting ENCLAVE_VERIFIED. An engine must never claim the stronger label on its own.
 */
interface AttestationVerifier {
    suspend fun verify(baseUrl: String): AttestationResult
}

data class AttestationResult(
    val verified: Boolean,
    /** Human-readable outcome for the privacy screen, e.g. the matched measurement digest. */
    val detail: String,
)
