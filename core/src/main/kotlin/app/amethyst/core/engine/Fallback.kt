package app.amethyst.core.engine

import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.FallbackMode
import app.amethyst.core.settings.FallbackSettings

/**
 * When a server cannot take a recording, another engine may - the user's pick, or by default
 * the best model on the phone. Never a less private one: the voice does not go somewhere
 * further than the user chose for it (a Tinfoil dictation is never handed to Groq). And only
 * for failures another engine could fix: no connection, a timeout, a server down. A refused
 * key or a bad request needs the user, not a detour.
 */
object Fallback {

    /** The failure another engine may recover from, or null when it should stand. */
    fun recoverable(error: Throwable?, engineTrust: TrustLevel, mode: FallbackMode): SttFailure? =
        (error as? SttFailure)?.takeIf { mode != FallbackMode.OFF && engineTrust != TrustLevel.ON_DEVICE && it.serviceUnavailable }

    /**
     * The trust a text recognized elsewhere after [failure] really has: the first server's,
     * unless the connection provably never opened - and never better than the fallback's own.
     */
    fun trustAfter(failure: SttFailure, serverTrust: TrustLevel, fallbackTrust: TrustLevel = TrustLevel.ON_DEVICE): TrustLevel {
        val first = if (failure.recordingMayHaveLeft) serverTrust else TrustLevel.ON_DEVICE
        return listOf(first, fallbackTrust).maxBy { it.strictness }
    }

    /**
     * The engines the user may pick as the fallback for [primary]: any other engine at least
     * as private. [trustOf] says where an engine sends the voice (the Android recognizer's
     * depends on the phone).
     */
    fun choices(engines: List<EngineConfig>, primary: EngineConfig, trustOf: (EngineConfig) -> TrustLevel): List<EngineConfig> {
        val limit = trustOf(primary).strictness
        return engines.filter { it.id != primary.id && trustOf(it).strictness <= limit }
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
