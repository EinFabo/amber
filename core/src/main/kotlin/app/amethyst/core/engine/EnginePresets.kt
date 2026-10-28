package app.amethyst.core.engine

import app.amethyst.core.privacy.TrustLevel

enum class EngineKind {
    /** Android's own recognizer. No setup, no download - the zero-friction entry point. */
    ANDROID_ON_DEVICE,

    /** Local ONNX model via sherpa-onnx: Parakeet or Moonshine. Not implemented yet. */
    LOCAL_SHERPA,

    /** Whisper in-process through whisper.cpp, with a ggml model such as FUTO's. */
    WHISPER_CPP,

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
    /** Shown under the preset in settings. User-facing. */
    val note: String = "",
)

object EnginePresets {

    /** Tinfoil's confidential model router; see [TINFOIL]. */
    const val TINFOIL_BASE_URL = "https://inference.tinfoil.sh/v1"

    val ANDROID_ON_DEVICE = EnginePreset(
        id = "android-on-device",
        displayName = "Android (on device)",
        kind = EngineKind.ANDROID_ON_DEVICE,
        trustLevel = TrustLevel.ON_DEVICE,
        note = "Uses the system recognizer. No download, no key - " +
            "quality depends on the manufacturer.",
    )

    val LOCAL_WHISPER = EnginePreset(
        id = "local-whisper",
        displayName = "Whisper local (FUTO)",
        kind = EngineKind.WHISPER_CPP,
        trustLevel = TrustLevel.ON_DEVICE,
        modelId = "futo-multilingual-74",
        note = "Fully offline through whisper.cpp, no Google language pack. " +
            "Needs a one-time 82 MB model download.",
    )

    val LOCAL_PARAKEET = EnginePreset(
        id = "local-parakeet",
        displayName = "Parakeet TDT (local)",
        kind = EngineKind.LOCAL_SHERPA,
        trustLevel = TrustLevel.ON_DEVICE,
        modelId = "parakeet-tdt-0.6b-v3-int8",
        note = "Most accurate offline candidate, 25 European languages, no CJK. " +
            "Needs a strong device.",
    )

    /**
     * Tinfoil runs its models inside attested enclaves behind one confidential router,
     * inference.tinfoil.sh, which speaks the ordinary OpenAI audio endpoint. The router picks
     * the model enclave by the `model` field: whisper-large-v3-turbo here, or the larger
     * voxtral-small-24b. (Until 2026 each model had its own subdomain, *.inf.tinfoil.sh; those
     * hosts no longer resolve.)
     */
    val TINFOIL = EnginePreset(
        id = "tinfoil-whisper",
        displayName = "Tinfoil (Enclave)",
        kind = EngineKind.OPENAI_COMPATIBLE,
        trustLevel = TrustLevel.CLOUD, // upgraded to ENCLAVE_VERIFIED once attestation passes
        baseUrl = TINFOIL_BASE_URL,
        modelId = "whisper-large-v3-turbo",
        needsApiKey = true,
        note = "Confidential computing in an enclave. Shows " +
            "\"Enclave verified\" only once the attestation is confirmed. The model can also be " +
            "voxtral-small-24b (more accurate, slower).",
    )

    val SELF_HOSTED = EnginePreset(
        id = "self-hosted",
        displayName = "Own server",
        kind = EngineKind.OPENAI_COMPATIBLE,
        trustLevel = TrustLevel.CLOUD,
        baseUrl = "http://192.168.1.10:8000/v1",
        modelId = "Systran/faster-whisper-large-v3",
        note = "Speaches, faster-whisper-server or whisper.cpp with " +
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
        note = "Very cheap and fast, but without a privacy guarantee.",
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
        note = "Text appears while you speak. Very fast final results, " +
            "but a noticeable wait for the first word.",
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
