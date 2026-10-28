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
    /** Refuse to send unless the connection is a verified enclave; see EngineConfig. */
    val requireEnclave: Boolean = false,
)

object EnginePresets {

    /** Tinfoil's confidential model router; see [TINFOIL]. */
    const val TINFOIL_BASE_URL = "https://inference.tinfoil.sh/v1"

    /**
     * Hosts whose enclave Amber can verify, and the repository whose signed release the
     * enclave must run. The one list both the verifier and the settings go by. The router is
     * the trust anchor: it verifies each model enclave itself before forwarding (the trusted
     * model repos are compiled into its measured code).
     */
    val ENCLAVE_REPOS = mapOf("inference.tinfoil.sh" to "tinfoilsh/confidential-model-router")

    val ENCLAVE_HOSTS: Set<String> get() = ENCLAVE_REPOS.keys

    /** The lowercase host of an address as typed, or null when it has none. */
    fun hostOf(baseUrl: String?): String? =
        runCatching { java.net.URI(baseUrl.orEmpty().trim()).host?.lowercase() }.getOrNull()

    /**
     * The repository an address's enclave must have been built from, or null when Amber cannot
     * verify it. Whatever the scheme: a verifiable host gets verified, and the secure channel
     * itself refuses anything but https to it.
     */
    fun enclaveRepoFor(baseUrl: String?): String? = hostOf(baseUrl)?.let { ENCLAVE_REPOS[it] }

    /** An address that meets "enclave required": https to a host Amber can verify. */
    fun isEnclaveHost(baseUrl: String?): Boolean =
        enclaveRepoFor(baseUrl) != null && baseUrl.orEmpty().trim().startsWith("https://", ignoreCase = true)

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
        note = "Close to Whisper large-v3 accuracy, but fast on a phone: it reads the whole " +
            "recording at once instead of in 30 s windows. 25 European languages, detected by " +
            "itself, with punctuation. One-time 670 MB download, ~700 MB memory while in use.",
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
        // Voxtral: more accurate than Whisper here and about a hundredth of its price, since
        // Whisper is billed per request; see TINFOIL_SPEECH_MODELS.
        modelId = "voxtral-small-24b",
        needsApiKey = true,
        requireEnclave = true,
        note = "Confidential computing in an enclave. Shows " +
            "\"Enclave verified\" only once the attestation is confirmed.",
    )

    /**
     * A model offered in the settings: the id the service expects, a few plain words, and for
     * chat models the switch that turns their reasoning off ([noThinking], sent as
     * chat_template_kwargs) - tidying punctuation needs no thinking, and thinking is both the
     * slow part and most of the bill.
     */
    data class ModelChoice(
        val id: String,
        val label: String,
        val note: String,
        val recommended: Boolean = false,
        val noThinking: Map<String, Boolean> = emptyMap(),
        /** Price in US dollars: per million tokens in and out, per request, or per audio minute. */
        val usdPerMIn: Double = 0.0,
        val usdPerMOut: Double = 0.0,
        val usdPerRequest: Double = 0.0,
        val usdPerMinute: Double = 0.0,
        /** Top-level request fields a chat model needs, e.g. Groq's reasoning_effort. */
        val extraParams: Map<String, String> = emptyMap(),
    )

    /*
     * Tinfoil prices from its public model config (api.tinfoil.sh/api/config/models), October
     * 2026, worked out for a ~15 s dictation: about 190 audio tokens in (Voxtral reads ~12.5 a
     * second) and 60 text tokens out; a cleanup about 220 tokens in and 70 out, reasoning off.
     */

    /** Tinfoil's speech models behind the router (POST /v1/audio/transcriptions). */
    val TINFOIL_SPEECH_MODELS = listOf(
        // $0.20 / $0.60 per million tokens; English, German, French, Spanish, Italian,
        // Portuguese, Dutch, Hindi.
        ModelChoice("voxtral-small-24b", "Voxtral Small 24B", "most accurate · ~\$0.0001 per dictation", recommended = true, usdPerMIn = 0.20, usdPerMOut = 0.60),
        // $0.01 per request whatever its length; 90+ languages.
        ModelChoice("whisper-large-v3-turbo", "Whisper large-v3-turbo", "90+ languages · \$0.01 per dictation", usdPerRequest = 0.01),
    )

    /** Tinfoil's chat models, for cleaning up transcripts; recommended first. */
    val TINFOIL_CLEANUP_MODELS = listOf(
        // $0.65 / $1.45; reasoning on by default, switched off here.
        ModelChoice(
            "deepseek-v4-1-flash", "DeepSeek V4.1 Flash", "smart and fast · ~\$0.00025",
            recommended = true, noThinking = mapOf("thinking" to false), usdPerMIn = 0.65, usdPerMOut = 1.45,
        ),
        // $0.40 / $1.00; reasoning on by default, switched off here.
        ModelChoice("gemma4-31b", "Gemma 4 31B", "cheapest · ~\$0.00016", noThinking = mapOf("enable_thinking" to false), usdPerMIn = 0.40, usdPerMOut = 1.00),
        // $0.15 / $0.60, but it always reasons first: cheap tokens, slower answers.
        ModelChoice("gpt-oss-120b", "gpt-oss 120B", "always reasons, slower · ~\$0.0002", usdPerMIn = 0.15, usdPerMOut = 0.60),
        // $1.75 / $2.75; no reasoning.
        ModelChoice("llama3-3-70b", "Llama 3.3 70B", "steady · ~\$0.0006", usdPerMIn = 1.75, usdPerMOut = 2.75),
        // $1.80 / $5.75; its reasoning cannot be switched off.
        ModelChoice("glm-5-3", "GLM 5.3", "reasons, pricier · ~\$0.0008+", usdPerMIn = 1.80, usdPerMOut = 5.75),
        // $4 / $20.
        ModelChoice("kimi-k3", "Kimi K3", "overkill here · ~\$0.0023", usdPerMIn = 4.0, usdPerMOut = 20.0),
    )

    /** No address or model filled in: a made-up one only looked configured and never worked. */
    val SELF_HOSTED = EnginePreset(
        id = "self-hosted",
        displayName = "Own server",
        kind = EngineKind.OPENAI_COMPATIBLE,
        trustLevel = TrustLevel.CLOUD,
        note = "Speaches, faster-whisper-server, LocalAI or whisper.cpp's server - anything " +
            "with /v1/audio/transcriptions. http:// works for addresses in your own network.",
    )

    val GROQ = EnginePreset(
        id = "groq",
        displayName = "Groq",
        kind = EngineKind.OPENAI_COMPATIBLE,
        trustLevel = TrustLevel.CLOUD,
        baseUrl = "https://api.groq.com/openai/v1",
        modelId = "whisper-large-v3-turbo",
        needsApiKey = true,
        note = "Very fast and cheap (about \$0.04 per hour of audio), no privacy guarantee.",
    )

    val OPENAI = EnginePreset(
        id = "openai",
        displayName = "OpenAI",
        kind = EngineKind.OPENAI_COMPATIBLE,
        trustLevel = TrustLevel.CLOUD,
        baseUrl = "https://api.openai.com/v1",
        modelId = "gpt-4o-mini-transcribe",
        needsApiKey = true,
        note = "Accurate, about \$0.003 per minute of audio, no privacy guarantee.",
    )

    /** Never implemented; no longer offered, and dropped from stored settings (schema 6). */
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
    )

    fun byId(id: String): EnginePreset? = all.firstOrNull { it.id == id }
}
