package app.nocturne.core.privacy

/**
 * How far a dictation travelled before it came back as text.
 *
 * Nocturne shows this on every transcript instead of hiding it, which is the main
 * thing a cloud-only dictation app cannot offer.
 */
enum class TrustLevel {
    /** Audio never left the device. */
    ON_DEVICE,

    /** Audio left the device, but into an enclave whose attestation Nocturne verified. */
    ENCLAVE_VERIFIED,

    /** Audio left the device to an ordinary server. TLS only, no further guarantee. */
    CLOUD,
    ;

    /** Lower is stricter. Used to sort engines and to refuse silent downgrades. */
    val strictness: Int
        get() = when (this) {
            ON_DEVICE -> 0
            ENCLAVE_VERIFIED -> 1
            CLOUD -> 2
        }
}
