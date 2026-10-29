package app.amethyst.core.engine

import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.EngineConfig

/** What the settings may promise about an engine before anything is sent. */
object EngineTrust {

    /**
     * Where an engine sends the voice. An engine that requires its enclave sends nothing unless
     * the attestation passes, so it shows as verified; any other server is plain cloud. The
     * Android recognizer keeps the voice on the phone only where the system has an on-device
     * recognizer ([onDeviceSpeech], Android 13+); elsewhere "prefer offline" is just a hint.
     */
    fun forDisplay(config: EngineConfig, onDeviceSpeech: Boolean): TrustLevel = when (config.kind) {
        EngineKind.ANDROID_ON_DEVICE -> if (onDeviceSpeech) TrustLevel.ON_DEVICE else TrustLevel.CLOUD
        EngineKind.LOCAL_SHERPA, EngineKind.WHISPER_CPP -> TrustLevel.ON_DEVICE
        EngineKind.OPENAI_COMPATIBLE, EngineKind.ELEVENLABS_REALTIME ->
            if (config.requireEnclave && EnginePresets.isEnclaveHost(config.baseUrl)) TrustLevel.ENCLAVE_VERIFIED else TrustLevel.CLOUD
    }

    /**
     * A server reached over plain http: readable and changeable by anyone on the network. It is
     * only allowed for private addresses, but a private address is private to one network -
     * on another Wi-Fi with the same range, someone else may answer.
     */
    fun unencrypted(config: EngineConfig): Boolean =
        config.kind == EngineKind.OPENAI_COMPATIBLE && config.baseUrl.orEmpty().trim().startsWith("http://", ignoreCase = true)
}
