package app.amethyst.core.settings

import app.amethyst.core.engine.EngineKind

/**
 * Which speech recognition takes the own words as a hint: Whisper on the phone and the servers
 * do; Parakeet and Android's recognizer have no way to be told. The cleanup always gets them.
 * The Own words page says so only where it matters - while the active one cannot.
 */
object OwnWordsReach {

    fun recognitionTakes(kind: EngineKind): Boolean = when (kind) {
        EngineKind.ANDROID_ON_DEVICE, EngineKind.LOCAL_SHERPA -> false
        EngineKind.WHISPER_CPP, EngineKind.OPENAI_COMPATIBLE, EngineKind.ELEVENLABS_REALTIME -> true
    }
}
