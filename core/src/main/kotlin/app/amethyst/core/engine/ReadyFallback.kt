package app.amethyst.core.engine

import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.FallbackMode
import app.amethyst.core.settings.FallbackSettings
import app.amethyst.core.text.Transcript
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select

/**
 * A local fallback that is ready before it is needed: while a server engine records, the
 * fallback model on the phone recognizes the same recording chunk by chunk, so when the server
 * fails - or hangs - its text is there at once instead of the whole recording being recognized
 * afterwards. Only for a model on the phone: preparing on another server would upload the
 * voice twice, and bill it twice.
 */
object ReadyFallback {

    /** The shortest wait for a server before a text ready on the phone is taken instead. */
    const val MIN_GRACE_MS = 4_000L

    /** A model that runs on the phone, the only kind that is prepared. */
    fun isLocal(config: EngineConfig): Boolean =
        config.kind == EngineKind.LOCAL_SHERPA || config.kind == EngineKind.WHISPER_CPP

    /**
     * Whether [fallback] is one that could be prepared - Automatic (Parakeet, then Whisper) or a
     * model on the phone picked by name - so the switch is worth showing.
     */
    fun preparable(fallback: FallbackSettings, engines: List<EngineConfig>): Boolean = when (fallback.mode) {
        FallbackMode.AUTOMATIC -> true
        FallbackMode.OFF -> false
        FallbackMode.ENGINE -> engines.firstOrNull { it.id == fallback.engineId }?.let(::isLocal) == true
    }

    /**
     * The engine that prepares [primary]'s fallback: the first fallback candidate that is
     * [ready], if it runs on the phone. Null while preparing is off, for a recognition on the
     * phone itself (no server to fail), and for a fallback on another server.
     */
    fun engineToPrepare(
        settings: AmethystSettings,
        primary: EngineConfig,
        trustOf: (EngineConfig) -> TrustLevel,
        ready: (EngineConfig) -> Boolean,
    ): EngineConfig? {
        if (!settings.fallback.prepare || trustOf(primary) == TrustLevel.ON_DEVICE) return null
        val first = Fallback.candidates(settings.fallback, Servers.recognizers(settings), primary, trustOf).firstOrNull(ready) ?: return null
        return first.takeIf(::isLocal)
    }

    /** How long a server may take before a ready local text wins: twice its usual time, at least [MIN_GRACE_MS]. */
    fun deadlineMs(expectedServerMs: Long): Long = maxOf(MIN_GRACE_MS, 2 * expectedServerMs)

    /** Who [race] went with. */
    sealed interface Outcome {
        /** The server's own answer: its text, or a failure the local text cannot stand in for. */
        data class Server(val result: Result<Transcript>) : Outcome

        /**
         * The text prepared on the phone: the server failed with [serverFailure], or - when it is
         * null - had not answered by the deadline.
         */
        data class Local(val transcript: Transcript, val serverFailure: SttFailure?) : Outcome {
            val serverTooSlow: Boolean get() = serverFailure == null
        }
    }

    /**
     * The [server] request against the text prepared on the phone ([local], which never
     * throws). The server's answer wins; a failure [recoverable] says another engine may fix
     * takes the local text, and so does a server that has not answered [deadlineMs] after the
     * start once the local text is there. A local text that failed leaves the server to finish.
     * The server request is cancelled when the local text wins; [local] is left to the caller.
     */
    suspend fun race(
        server: suspend () -> Result<Transcript>,
        local: Deferred<Result<Transcript>>,
        deadlineMs: Long,
        recoverable: (Throwable?) -> SttFailure?,
    ): Outcome = coroutineScope {
        val answer = async { server() }
        val timer = async { delay(deadlineMs) }
        var prepared: Result<Transcript>? = null
        var pastDeadline = false
        var outcome: Outcome? = null
        try {
            while (outcome == null) {
                val event = select<Event> {
                    answer.onAwait { Event.ServerDone(it) }
                    if (prepared == null) local.onAwait { Event.LocalDone(it) }
                    if (!pastDeadline) timer.onAwait { Event.Deadline }
                }
                outcome = when (event) {
                    is Event.ServerDone -> {
                        val failure = recoverable(event.result.exceptionOrNull())
                        val text = failure?.let { (prepared ?: local.await()).getOrNull() }
                        if (failure != null && text != null) Outcome.Local(text, failure) else Outcome.Server(event.result)
                    }
                    is Event.LocalDone -> {
                        prepared = event.result
                        event.result.getOrNull()?.takeIf { pastDeadline }?.let { Outcome.Local(it, null) }
                    }
                    Event.Deadline -> {
                        pastDeadline = true
                        prepared?.getOrNull()?.let { Outcome.Local(it, null) }
                    }
                }
            }
            checkNotNull(outcome)
        } finally {
            answer.cancel()
            timer.cancel()
        }
    }

    private sealed interface Event {
        class ServerDone(val result: Result<Transcript>) : Event
        class LocalDone(val result: Result<Transcript>) : Event
        data object Deadline : Event
    }
}
