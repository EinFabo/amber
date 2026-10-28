package app.amethyst.core.privacy

/**
 * Checks that a remote endpoint really is the enclave it claims to be.
 *
 * Tinfoil has no Kotlin SDK; the app implements this with tinfoil-go's `mobile` package, the
 * I/O-free verifier Tinfoil's Swift SDK binds through gomobile. An engine without a verifier
 * labels its transcripts CLOUD, and an engine must never claim ENCLAVE_VERIFIED on its own.
 */
interface AttestationVerifier {
    /**
     * Verifies the enclave behind [baseUrl]. A still-fresh earlier result may be returned
     * unless [forceFresh] - set after the enclave's TLS key no longer matched, which means it
     * rotated and the old proof is worthless.
     */
    suspend fun verify(baseUrl: String, forceFresh: Boolean = false): AttestationResult
}

data class AttestationResult(
    val verified: Boolean,
    /** Human-readable outcome for the diagnostics, e.g. the release that was matched. */
    val detail: String,
    /** What was proven; present exactly when [verified]. */
    val proof: EnclaveProof? = null,
)

/**
 * What a passing attestation established about one enclave host - everything the transport
 * needs to bind a connection to it, and everything the debug screen shows.
 */
data class EnclaveProof(
    val host: String,
    /** The GitHub repository whose signed release the enclave's measurements matched. */
    val repo: String,
    val codeTag: String?,
    val codeDigest: String,
    /** "https://tinfoil.sh/predicate/tdx-guest/v2" or the SEV-SNP equivalent. */
    val measurementType: String?,
    val registers: List<String>,
    /** SHA-256 of the enclave's TLS SubjectPublicKeyInfo, lowercase hex. */
    val tlsKeySha256Hex: String,
    val hpkePublicKey: String?,
    val verifiedAtMillis: Long,
    /** After this the proof must not authorize another request. */
    val freshUntilMillis: Long,
) {
    /** Usable now, with a margin so a long upload does not straddle the expiry. */
    fun isFresh(now: Long): Boolean = now < freshUntilMillis - REFRESH_MARGIN_MS

    val platform: String
        get() = when {
            measurementType == null -> "unknown"
            "tdx" in measurementType -> "Intel TDX"
            "sev-snp" in measurementType -> "AMD SEV-SNP"
            else -> measurementType
        }

    companion object {
        const val REFRESH_MARGIN_MS = 5 * 60 * 1000L
    }
}
