package app.amethyst.core.engine

import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.EngineConfig

/**
 * When a server cannot take a recording, a model on the phone may. Only ever in that
 * direction - the voice never goes somewhere less private than chosen - and only for
 * failures another engine could fix: no connection, a timeout, a server down. A refused key
 * or a bad request needs the user, not a detour.
 */
object LocalFallback {

    fun applies(error: Throwable?, engineTrust: TrustLevel, enabled: Boolean): Boolean =
        enabled && engineTrust != TrustLevel.ON_DEVICE && error is SttFailure && error.serviceUnavailable

    /** The engines to try, best first: Parakeet (fast, accurate), then Whisper. */
    fun candidates(engines: List<EngineConfig>): List<EngineConfig> = engines
        .filter { it.kind == EngineKind.LOCAL_SHERPA || it.kind == EngineKind.WHISPER_CPP }
        .sortedBy { if (it.kind == EngineKind.LOCAL_SHERPA) 0 else 1 }
}
