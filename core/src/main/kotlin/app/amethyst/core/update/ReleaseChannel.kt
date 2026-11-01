package app.amethyst.core.update

import java.security.MessageDigest

/**
 * Which build this is, told by the key it is signed with. Only builds signed with the GitHub
 * release key look for updates - on GitHub, where they came from. Every other build (signed
 * with another key, or a debug build) gets its updates from wherever it was installed and never
 * asks anyone.
 */
enum class ReleaseChannel {
    GITHUB,
    OTHER,
    ;

    val checksForUpdates: Boolean get() = this == GITHUB

    companion object {
        /** SHA-256 of the GitHub release key's certificate (CN=Amber), lowercase hex. */
        const val GITHUB_SIGNER_SHA256 = "c3f69e720b6f195fc0b45dd538a0844e02c196e73b2909fc88d959be96d68e11"

        /**
         * [signers]: the SHA-256 of each certificate the installed APK is signed with now, hex in
         * either case, colons allowed. Exactly the GitHub key is the GitHub channel; anything
         * else - another key, a second signer beside it, none at all - is not.
         */
        fun of(signers: List<String>): ReleaseChannel =
            if (signers.size == 1 && normalize(signers.single()) == GITHUB_SIGNER_SHA256) GITHUB else OTHER

        /** [of] from the DER-encoded certificates, as Android hands them out. */
        fun ofCertificates(certificates: List<ByteArray>): ReleaseChannel = of(certificates.map(::sha256Hex))

        /** The fingerprint `apksigner verify --print-certs` prints: SHA-256 of the DER bytes. */
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun normalize(fingerprint: String): String =
            fingerprint.filterNot { it == ':' || it.isWhitespace() }.lowercase()
    }
}
