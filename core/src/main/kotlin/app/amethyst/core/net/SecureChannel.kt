package app.amethyst.core.net

import app.amethyst.core.engine.NotSentException
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
            if (!reaches(route, request.url)) {
                val target = runCatching { URI(request.url) }.getOrNull()
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
        } catch (e: CleartextRefusedException) {
            Result.failure(SttFailure(userMessage = e.message!!, technical = "cleartext refused: ${e.detail}", cause = e))
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

    /**
     * Opens the connection a request to [url] would take, while the user is still speaking, so
     * the request after the stop does not also pay for DNS, TCP and TLS - nor, for an enclave,
     * for the attestation, which is verified first; the connection is then opened through the
     * pinned client, exactly as a request would be. A HEAD with no Authorization header and no
     * payload: whatever the server answers, the connection is open and kept for the request.
     * An Amber PC or Amber Server carries its key in the address's path, so the HEAD carries it
     * there too - to the server it belongs to, as every request does. True when the connection
     * is open; a failure - a proof whose key cannot be pinned included - is silent, never
     * thrown, and changes nothing: the request opens its own connection, and an enclave that
     * cannot be verified is refused there, as always. [url] is never logged.
     */
    suspend fun warmUp(url: String): Boolean = try {
        val route = route(forceFresh = false, what = "nothing").getOrNull()
        if (route == null || !reaches(route, url)) {
            false
        } else {
            route.transport.execute(HttpRequest(method = "HEAD", url = url, timeoutMs = WARM_UP_TIMEOUT_MS))
            true
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** Whether [url] may go over [route]: anywhere on a plain one, only to the attested host over https on a pinned one. */
    private fun reaches(route: Route, url: String): Boolean {
        val attested = route.host ?: return true
        val target = runCatching { URI(url) }.getOrNull()
        return target?.scheme == "https" && attested.equals(target.host, ignoreCase = true)
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
                    cause = if (result.unreachable) NotSentException(result.detail) else null,
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

    private companion object {
        /**
         * A warm-up that takes longer is no help to the request it is for - and on a dead
         * network it must give way to the real upload soon (see [ConnectionWarmUp.Pool]).
         */
        const val WARM_UP_TIMEOUT_MS = 5_000L
    }
}
