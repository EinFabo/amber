package app.amethyst.core.engine

import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.FallbackMode
import app.amethyst.core.settings.FallbackSettings

/**
 * When a server cannot take a recording, another engine may - the user's pick, or by default
 * the best model on the phone. Never a less private one: the voice does not go somewhere
 * further than the user chose for it (a Tinfoil dictation is never handed to Groq). And only
 * for failures another engine could fix: no connection, a timeout, a server down - and a server
 * that is there but cannot serve, because its model is gone or the account has no credit left
 * ([SttFailure.serverCannotServe]); the user is then told what the server said, since the
 * fallback would otherwise hide it for good. A refused key or a bad request needs the user,
 * not a detour.
 *
 * An engine on the phone falls back too, but only when its model ran out of memory or failed
 * ([SttFailure.localModelFailed]) and another engine is there to take over - never to itself.
 * The failed model is let go of first: on a 3 GB phone two models never fit at once.
 */
object Fallback {

    /**
     * The failure another engine may recover from, or null when it should stand. For an engine
     * on the phone, [fallbacks] are the engines that would take over ([candidates]): without
     * one, there is nothing to fall back to.
     */
    fun recoverable(error: Throwable?, engineTrust: TrustLevel, mode: FallbackMode, fallbacks: List<EngineConfig> = emptyList()): SttFailure? {
        val failure = (error as? SttFailure)?.takeIf { mode != FallbackMode.OFF } ?: return null
        return if (engineTrust == TrustLevel.ON_DEVICE) {
            failure.takeIf { it.localModelFailed && fallbacks.isNotEmpty() }
        } else {
            failure.takeIf { it.worthAnotherEngine }
        }
    }

    /**
     * The trust a text recognized elsewhere after [failure] really has: the first server's,
     * unless the connection provably never opened - and never better than the fallback's own.
     */
    fun trustAfter(failure: SttFailure, serverTrust: TrustLevel, fallbackTrust: TrustLevel = TrustLevel.ON_DEVICE): TrustLevel {
        val first = if (failure.recordingMayHaveLeft) serverTrust else TrustLevel.ON_DEVICE
        return listOf(first, fallbackTrust).maxBy { it.strictness }
    }

    /**
     * The trust of a text recognized elsewhere while the server still had the request - it
     * was too slow, not gone: the upload may have reached it, so never better than its trust.
     */
    fun trustAfterSlowServer(serverTrust: TrustLevel, fallbackTrust: TrustLevel): TrustLevel =
        listOf(serverTrust, fallbackTrust).maxBy { it.strictness }

    /**
     * The engines the user may pick as the fallback for [primary]: any other engine at least
     * as private. [trustOf] says where an engine sends the voice (the Android recognizer's
     * depends on the phone).
     */
    fun choices(engines: List<EngineConfig>, primary: EngineConfig, trustOf: (EngineConfig) -> TrustLevel): List<EngineConfig> {
        val limit = trustOf(primary).strictness
        return engines.filter { it.id != primary.id && trustOf(it).strictness <= limit }
    }

    /**
     * [candidates] that may take a recording after its model on the phone failed for [problem].
     * After running out of memory, only those whose model fits the phone's memory
     * ([EngineAdvice.fitsInMemory], [totalRamMb] as Android reports it, 0 unknown): on a 3 GB
     * phone Parakeet's 700 MB after a Whisper that ran out would only get the app killed. A
     * Whisper file the user picked is not known here and stays. Any other failure: all of them.
     */
    fun fitAfter(problem: Problem?, candidates: List<EngineConfig>, totalRamMb: Long): List<EngineConfig> {
        if (problem != Problem.OutOfMemory || totalRamMb <= 0) return candidates
        return candidates.filter { config -> EngineAdvice.optionOf(config)?.let { EngineAdvice.fitsInMemory(it, totalRamMb) } ?: true }
    }

    /** The engines to try, best first, for a recording [primary] could not take. */
    fun candidates(
        settings: FallbackSettings,
        engines: List<EngineConfig>,
        primary: EngineConfig,
        trustOf: (EngineConfig) -> TrustLevel,
    ): List<EngineConfig> = when (settings.mode) {
        FallbackMode.OFF -> emptyList()
        // Parakeet (fast, accurate), then Whisper.
        FallbackMode.AUTOMATIC -> engines
            .filter { it.id != primary.id && (it.kind == EngineKind.LOCAL_SHERPA || it.kind == EngineKind.WHISPER_CPP) }
            .sortedBy { if (it.kind == EngineKind.LOCAL_SHERPA) 0 else 1 }
        FallbackMode.ENGINE -> choices(engines, primary, trustOf).filter { it.id == settings.engineId }
    }
}
