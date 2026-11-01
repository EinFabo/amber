package app.amethyst.core.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A server connection being opened while the user speaks ([SecureChannel.warmUp]), and whether
 * it was ready when the request went out - for the dictation's Diagnostics. Started in a scope
 * of its own, so the dictation never waits for it; a warm-up that fails or throws changes
 * nothing but this line.
 */
class ConnectionWarmUp private constructor() {

    enum class State { RUNNING, WARM, FAILED }

    @Volatile
    var state: State = State.RUNNING
        private set

    /** What the request found, read when it goes out. */
    fun describe(): String = when (state) {
        State.RUNNING -> "connection still warming up"
        State.WARM -> "connection warm"
        State.FAILED -> "connection cold (warm-up failed)"
    }

    /**
     * At most one warm-up per server at a time: one still running for the same [start] key is
     * joined rather than started again - rapid taps on a dead network would otherwise queue
     * HEAD after HEAD ahead of the real upload (OkHttp runs five calls per host at once).
     */
    class Pool(private val scope: CoroutineScope) {
        private val latest = HashMap<String, ConnectionWarmUp>()

        /** [key] names the server - its API root; kept in memory only, never logged (it may carry a key). */
        fun start(key: String, warm: suspend () -> Boolean): ConnectionWarmUp = synchronized(latest) {
            latest[key]?.takeIf { it.state == State.RUNNING }
                ?: ConnectionWarmUp.start(scope, warm).also { latest[key] = it }
        }
    }

    companion object {
        /** Runs [warm] in [scope]; true from it means the connection is open. */
        fun start(scope: CoroutineScope, warm: suspend () -> Boolean): ConnectionWarmUp {
            val warmUp = ConnectionWarmUp()
            scope.launch {
                warmUp.state = try {
                    if (warm()) State.WARM else State.FAILED
                } catch (e: CancellationException) {
                    warmUp.state = State.FAILED
                    throw e
                } catch (e: Exception) {
                    State.FAILED
                }
            }
            return warmUp
        }
    }
}
