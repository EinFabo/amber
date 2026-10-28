package app.amethyst.core.net

import app.amethyst.core.engine.SttFailure
import app.amethyst.core.privacy.AttestationResult
import app.amethyst.core.privacy.AttestationVerifier
import app.amethyst.core.privacy.TrustLevel
import java.net.URI
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Sends requests to one service and says what each one earned.
 *
 * Without a verifier it is a plain transport at [plainTrust]. With one it is strict: a request
 * goes out only over a connection pinned to the TLS key a fresh attestation endorsed, and only
 * that earns ENCLAVE_VERIFIED. A rotated enclave key is verified afresh once; if the enclave
 * cannot be verified, nothing is sent - someone who picked an enclave did so to keep their
 * voice and text off ordinary servers, and quietly downgrading to plain HTTPS would break
 * exactly that.
 *
 * Shared by speech recognition and text cleanup, so both reach the enclave the same way.
 */
class SecureChannel(
    private val baseUrl: String,
    private val displayName: String,
    private val transport: HttpTransport,
    private val verifier: AttestationVerifier?,
    private val plainTrust: TrustLevel,
) {

    class Sent(val response: HttpResponse, val trust: TrustLevel)

    /** [host] is the attested host a pinned route may talk to; null for a plain route. */
    private class Route(val transport: HttpTransport, val trust: TrustLevel, val host: String? = null)

    /** [what] names the payload in messages: "recording" or "text". */
    suspend fun send(request: HttpRequest, what: String = "recording"): Result<Sent> {
        var route = route(forceFresh = false, what).getOrElse { return Result.failure(it) }
        route.host?.let { attested ->
            val target = runCatching { URI(request.url) }.getOrNull()
            if (target?.scheme != "https" || !attested.equals(target.host, ignoreCase = true)) {
                return Result.failure(
                    SttFailure(
                        userMessage = "The request did not go to the verified enclave. The $what was not sent.",
                        technical = "request ${target?.scheme}://${target?.host}, attested $attested",
                    ),
                )
            }
        }
        return try {
            val response = try {
                route.transport.execute(request)
            } catch (e: SSLPeerUnverifiedException) {
                // The server's key is not the attested one. Enclaves rotate their keys, so
                // verify afresh once; if the new proof does not match either, give up.
                if (verifier == null) throw e
                route = route(forceFresh = true, what).getOrElse { return Result.failure(it) }
                route.transport.execute(request)
            }
            Result.success(Sent(response, route.trust))
        } catch (e: SSLPeerUnverifiedException) {
            Result.failure(
                SttFailure(
                    userMessage = "The server of \"$displayName\" does not hold the verified " +
                        "enclave key. The $what was not sent.",
                    technical = "pin mismatch: ${e.message}",
                    cause = e,
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(
                SttFailure(
                    // The call's own time limit; a socket timeout means no connection at all.
                    userMessage = if (e is java.io.InterruptedIOException && e !is java.net.SocketTimeoutException) {
                        "\"$displayName\" did not answer in time."
                    } else {
                        "No connection to \"$displayName\"."
                    },
                    technical = e.toString(),
                    cause = e,
                ),
            )
        }
    }

    /** The current attestation, or null without a verifier. */
    suspend fun attestation(): AttestationResult? = verifier?.let { verify(it, forceFresh = false) }

    /** Verifies ahead of first use, so the first request does not pay for the fetch. */
    suspend fun warmUp() {
        verifier?.let { runCatching { it.verify(baseUrl) } }
    }

    private suspend fun route(forceFresh: Boolean, what: String): Result<Route> {
        val verifier = verifier ?: return Result.success(Route(transport, plainTrust))

        val result = verify(verifier, forceFresh)
        val proof = result.proof
        if (!result.verified || proof == null) {
            return Result.failure(
                SttFailure(
                    userMessage = if (result.unreachable) {
                        "No connection to \"$displayName\"."
                    } else {
                        "The enclave of \"$displayName\" could not be verified. The $what was not sent."
                    },
                    technical = "attestation: ${result.detail}",
                    // Offline is offline, enclave or not: a model on the phone may take over.
                    cause = if (result.unreachable) java.io.IOException(result.detail) else null,
                ),
            )
        }
        val host = runCatching { URI(baseUrl.trim()).host }.getOrNull()
        if (!proof.host.equals(host, ignoreCase = true)) {
            return Result.failure(
                SttFailure(
                    userMessage = "The verified enclave does not belong to the configured address.",
                    technical = "attested ${proof.host}, endpoint $host",
                ),
            )
        }
        val pinnable = transport as? PinnableTransport
            ?: return Result.failure(
                SttFailure(
                    userMessage = "This connection cannot be bound to the enclave.",
                    technical = "transport ${transport::class.simpleName} cannot pin",
                ),
            )
        return Result.success(Route(pinnable.pinnedTo(proof.host, proof.tlsKeySha256Hex), TrustLevel.ENCLAVE_VERIFIED, proof.host))
    }

    private suspend fun verify(verifier: AttestationVerifier, forceFresh: Boolean): AttestationResult =
        try {
            verifier.verify(baseUrl, forceFresh)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AttestationResult(false, "Verification failed: ${e.message}", unreachable = e is java.io.IOException)
        }
}
