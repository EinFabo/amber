// The Java surface gomobile generates for tinfoil-go/mobile with -javapkg=sh.tinfoil
// (tools/build-tinfoil-aar.sh). Errors returned by Go arrive as java.lang.Exception.
package sh.tinfoil.mobile

object Mobile {
    @JvmStatic fun newNonce(): ByteArray = TODO()
    @JvmStatic fun attestationURL(host: String, relay: String, nonce: ByteArray): String = TODO()
    @JvmStatic fun newVerifier(optionsJSON: String): Verifier = TODO()
}

class Verifier {
    fun verify(doc: ByteArray, nonce: ByteArray, repo: String): String = TODO()
}
