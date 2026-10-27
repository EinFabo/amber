package app.nocturne.core.engine

import app.nocturne.core.privacy.TrustLevel

enum class EngineKind {
    /** Android's own recognizer. No setup, no download - the zero-friction entry point. */
    ANDROID_ON_DEVICE,

    /** Local ONNX model via sherpa-onnx: Whisper, Parakeet or Moonshine. */
    LOCAL_SHERPA,

    /** Anything that speaks POST /v1/audio/transcriptions. */
    OPENAI_COMPATIBLE,

    /** ElevenLabs Scribe v2 Realtime over a WebSocket. */
    ELEVENLABS_REALTIME,
}

/**
 * A ready-made configuration the user can pick in settings instead of typing URLs.
 *
 * Note on the OpenAI-compatible presets: they differ only in base URL, model id and trust
 * level. That is the whole point - one driver, many providers, and a self-hosted server is
 * a first-class citizen rather than an afterthought.
 */
data class EnginePreset(
    val id: String,
    val displayName: String,
    val kind: EngineKind,
    val trustLevel: TrustLevel,
    val baseUrl: String? = null,
    val modelId: String? = null,
    val needsApiKey: Boolean = false,
    /** Shown under the preset in settings. German, user-facing. */
    val note: String = "",
)

object EnginePresets {

    val ANDROID_ON_DEVICE = EnginePreset(
        id = "android-on-device",
        displayName = "Android (auf dem Gerät)",
        kind = EngineKind.ANDROID_ON_DEVICE,
        trustLevel = TrustLevel.ON_DEVICE,
        note = "Nutzt den Erkenner des Systems. Kein Download, kein Schlüssel - " +
            "Qualität hängt vom Hersteller ab.",
    )

    val LOCAL_WHISPER = EnginePreset(
        id = "local-whisper",
        displayName = "Whisper (lokal)",
        kind = EngineKind.LOCAL_SHERPA,
        trustLevel = TrustLevel.ON_DEVICE,
        modelId = "whisper-small-int8",
        note = "Komplett offline, 99 Sprachen. Braucht einen Modell-Download.",
    )

    val LOCAL_PARAKEET = EnginePreset(
        id = "local-parakeet",
        displayName = "Parakeet TDT (lokal)",
        kind = EngineKind.LOCAL_SHERPA,
        trustLevel = TrustLevel.ON_DEVICE,
        modelId = "parakeet-tdt-0.6b-v3-int8",
        note = "Genauester Offline-Kandidat, 25 europäische Sprachen, kein CJK. " +
            "Braucht ein starkes Gerät.",
    )

    /**
     * Tinfoil runs whisper-large-v3-turbo inside an attested enclave, served by vLLM, so the
     * ordinary OpenAI audio endpoint applies. The host follows their subdomain-per-model
     * scheme; confirm the exact domain in your Tinfoil dashboard before first use.
     */
    val TINFOIL = EnginePreset(
        id = "tinfoil-whisper",
        displayName = "Tinfoil (Enclave)",
        kind = EngineKind.OPENAI_COMPATIBLE,
        trustLevel = TrustLevel.CLOUD, // upgraded to ENCLAVE_VERIFIED once attestation passes
        baseUrl = "https://whisper-large-v3-turbo.inf.tinfoil.sh/v1",
        modelId = "whisper-large-v3-turbo",
        needsApiKey = true,
        note = "Vertrauliches Rechnen in einer Enclave. Zeigt " +
            "\"Enclave geprüft\" erst, wenn die Attestierung bestätigt ist.",
    )

    val SELF_HOSTED = EnginePreset(
        id = "self-hosted",
        displayName = "Eigener Server",
        kind = EngineKind.OPENAI_COMPATIBLE,
        trustLevel = TrustLevel.CLOUD,
        baseUrl = "http://192.168.1.10:8000/v1",
        modelId = "Systran/faster-whisper-large-v3",
        note = "Speaches, faster-whisper-server oder whisper.cpp mit " +
            "--inference-path /v1/audio/transcriptions.",
    )

    val GROQ = EnginePreset(
        id = "groq",
        displayName = "Groq",
        kind = EngineKind.OPENAI_COMPATIBLE,
        trustLevel = TrustLevel.CLOUD,
        baseUrl = "https://api.groq.com/openai/v1",
        modelId = "whisper-large-v3-turbo",
        needsApiKey = true,
        note = "Sehr günstig und schnell, dafür ohne Datenschutz-Garantie.",
    )

    val OPENAI = EnginePreset(
        id = "openai",
        displayName = "OpenAI",
        kind = EngineKind.OPENAI_COMPATIBLE,
        trustLevel = TrustLevel.CLOUD,
        baseUrl = "https://api.openai.com/v1",
        modelId = "gpt-4o-mini-transcribe",
        needsApiKey = true,
    )

    val ELEVENLABS = EnginePreset(
        id = "elevenlabs-realtime",
        displayName = "ElevenLabs Scribe v2 Realtime",
        kind = EngineKind.ELEVENLABS_REALTIME,
        trustLevel = TrustLevel.CLOUD,
        baseUrl = "wss://api.elevenlabs.io/v1/speech-to-text/realtime",
        modelId = "scribe_v2_realtime",
        needsApiKey = true,
        note = "Text erscheint beim Sprechen. Sehr schnelle Endergebnisse, " +
            "aber merkbare Zeit bis zum ersten Wort.",
    )

    val all: List<EnginePreset> = listOf(
        ANDROID_ON_DEVICE,
        LOCAL_WHISPER,
        LOCAL_PARAKEET,
        TINFOIL,
        SELF_HOSTED,
        GROQ,
        OPENAI,
        ELEVENLABS,
    )

    fun byId(id: String): EnginePreset? = all.firstOrNull { it.id == id }
}
