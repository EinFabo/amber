package app.amethyst.core.settings

import app.amethyst.core.audio.VadConfig
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.EnginePreset
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.OpenAiCompatibleEngine
import app.amethyst.core.text.NormalizeOptions
import app.amethyst.core.text.SpliceOptions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EngineConfig(
    val id: String,
    val kind: EngineKind,
    val displayName: String,
    val baseUrl: String? = null,
    val modelId: String? = null,
    /** BCP-47, or null for automatic detection. */
    val languageTag: String? = null,
    /** Alias under which the key lives in EncryptedSharedPreferences - never the key itself. */
    val apiKeyAlias: String? = null,
    val enabled: Boolean = true,
    /**
     * Until schema 7 the cleanup model lived here, tied to the engine. It is only read to move
     * it to [AmethystSettings.cleanup]; nothing else uses it any more.
     */
    val cleanupModel: String? = null,
    /**
     * Send only over a verified enclave connection, or not at all. Set by presets like
     * Tinfoil: whether a connection gets verified is decided by its host, so without this an
     * edited URL would quietly turn the engine into a plain cloud one - with the same key.
     */
    val requireEnclave: Boolean = false,
) {
    companion object {
        fun fromPreset(preset: EnginePreset, languageTag: String? = null) = EngineConfig(
            id = preset.id,
            kind = preset.kind,
            displayName = preset.displayName,
            baseUrl = preset.baseUrl,
            modelId = preset.modelId,
            languageTag = languageTag,
            apiKeyAlias = if (preset.needsApiKey) "key.${preset.id}" else null,
            requireEnclave = preset.requireEnclave,
        )

        /**
         * German for engines that take one language. Whisper gets two to choose from: fixed
         * to one, it translates everything else into it.
         */
        fun defaultLanguageTag(kind: EngineKind): String =
            if (kind == EngineKind.WHISPER_CPP) AmethystSettings.WHISPER_DEFAULT_LANGUAGES else "de-DE"
    }
}

@Serializable
data class CaptureSettings(
    val autoStopOnSilence: Boolean = true,
    /**
     * Hard ceiling for one whole dictation, recording plus transcription. A stuck recognizer
     * or a server that accepts the upload and never answers would otherwise leave the bubble
     * spinning with no way back except force-stopping the app.
     */
    val totalTimeoutMs: Int = 180_000,
    val preRollMs: Int = 300,
    val silenceTimeoutMs: Int = 900,
    val minSpeechMs: Int = 350,
    val maxDurationMs: Int = 120_000,
    val stripFillers: Boolean = true,
    /** Adds the commas German requires before subordinate clauses; rules only, no model. */
    val germanCommas: Boolean = true,
    /** Recognize finished parts while still speaking, for engines that support it. */
    val chunking: Boolean = true,
    /** Shortest part worth cutting off; null keeps each engine's own (see EngineCapabilities). */
    val chunkMinMs: Int? = null,
    /**
     * When a server cannot be reached, recognize the recording with a local model that is
     * ready on the phone. Only ever towards the phone, so privacy never gets weaker.
     */
    @Deprecated("Since schema 7: AmethystSettings.fallback. Read only to migrate.")
    val localFallback: Boolean = true,
) {
    /**
     * [preRollMs] doubles as the calibration window: inside it the policy only learns the
     * room's noise floor and takes no stop decision, so a slow first syllable is never
     * mistaken for silence.
     */
    fun toVadConfig(frameMs: Int = 20): VadConfig = VadConfig(
        frameMs = frameMs,
        preRollMs = preRollMs,
        calibrationFrames = (preRollMs / frameMs).coerceIn(3, 50),
        minSpeechMs = minSpeechMs,
        // Auto-stop off: push the silence timeout out of reach so only a tap or the hard
        // duration cap ends the recording.
        silenceTimeoutMs = if (autoStopOnSilence) silenceTimeoutMs else Int.MAX_VALUE,
        maxDurationMs = maxDurationMs,
    )

    fun toNormalizeOptions(): NormalizeOptions = NormalizeOptions(stripFillers = stripFillers, germanCommas = germanCommas)

    /**
     * The watchdog always has to outlast the longest recording, the server's own time limit
     * for it, and then the phone recognizing it instead - or a dictation the local fallback
     * could still save is killed just before it gets the chance.
     */
    fun effectiveTotalTimeoutMs(): Long {
        val longest = maxDurationMs.toLong()
        return maxOf(totalTimeoutMs.toLong(), longest + OpenAiCompatibleEngine.requestTimeoutMs(longest) + AFTER_SERVER_MS)
    }

    private companion object {
        /** A cold local model loading and recognizing the recording, then the cleanup. */
        const val AFTER_SERVER_MS = 90_000L
    }
}

@Serializable
data class BubbleSettings(
    /**
     * Off hides the overlay without touching the accessibility service. Without this the only
     * way to get the bubble out of the way is the system accessibility screen, which also
     * kills insertion - so the in-app test path would die with it.
     */
    val enabled: Boolean = true,
    /** 0f = left edge, 1f = right edge. Written whenever the bubble is dragged. */
    val horizontalBias: Float = 1f,
    /** 0f = top of screen, 1f = bottom. */
    val verticalBias: Float = 0.72f,
    val sizeDp: Int = 56,
    val haptics: Boolean = true,
    /**
     * Show the bubble only while a text field has focus (and while a dictation is running),
     * so it never covers anything when there is nothing to dictate into.
     */
    val onlyWithTextField: Boolean = true,
    /** How visible the bubble is at rest, 0.2..1; while dictating it is always fully there. */
    val restingOpacity: Float = 1f,
    /** Apps the bubble stays out of (games, apps with their own voice input). */
    val hiddenIn: List<String> = emptyList(),
) {
    companion object {
        val DEFAULT_HORIZONTAL_BIAS = 1f
        val DEFAULT_VERTICAL_BIAS = 0.72f
    }
}

@Serializable
data class InsertionSettings(
    val autoSpace: Boolean = true,
    val autoCapitalize: Boolean = true,
    val gluePunctuation: Boolean = true,
    val rememberStrategyPerApp: Boolean = true,
    /**
     * Off by default: text goes straight into the field, and a dictation that cannot be
     * inserted is reported rather than parked on the clipboard. On allows pasting and the
     * copy-as-last-resort, at the price of every such dictation landing in clipboard history.
     */
    val allowClipboard: Boolean = false,
) {
    fun toSpliceOptions(): SpliceOptions = SpliceOptions(
        autoSpace = autoSpace,
        autoCapitalize = autoCapitalize,
        gluePunctuation = gluePunctuation,
    )
}

@Serializable
data class AmethystSettings(
    val activeEngineId: String = EnginePresets.ANDROID_ON_DEVICE.id,
    val engines: List<EngineConfig> = emptyList(),
    val capture: CaptureSettings = CaptureSettings(),
    val bubble: BubbleSettings = BubbleSettings(),
    val insertion: InsertionSettings = InsertionSettings(),
    /** Per-app writing style and cleanup, edited under In your apps -> App styles. */
    val profiles: List<AppProfile> = AppProfiles.DEFAULTS,
    val stats: StatsSettings = StatsSettings(),
    /**
     * Who rewrites the recognized text - chosen apart from who recognizes it, so local
     * recognition can be cleaned up by a server model (or, later, a local one). Null: nobody.
     */
    val cleanup: CleanupChoice? = null,
    /** Who takes a recording the engine's server could not; see [app.amethyst.core.engine.Fallback]. */
    val fallback: FallbackSettings = FallbackSettings(),
    /** Whether the introduction ran; a fresh install starts without, see [SettingsCodec.migrate]. */
    val onboardingDone: Boolean = false,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    val activeEngine: EngineConfig?
        get() = engines.firstOrNull { it.id == activeEngineId }

    fun withEngine(config: EngineConfig): AmethystSettings {
        val replaced = engines.map { if (it.id == config.id) config else it }
        return copy(engines = if (replaced.any { it.id == config.id }) replaced else engines + config)
    }

    companion object {
        /**
         * 2: Whisper takes a list of languages. 3: Tinfoil moved behind one router.
         * 4: per-app profiles - nothing to migrate, an absent field decodes to the defaults.
         * 5: the Tinfoil engine requires its enclave.
         * 6: ElevenLabs Realtime engines (never implemented) are dropped.
         * 7: cleanup and fallback chosen on their own, no longer per engine.
         * 8: the introduction - settings from before it count as introduced.
         * 9: the cleanup per app is a choice (default, off, a model) instead of a switch.
         * See [SettingsCodec.migrate].
         */
        const val SCHEMA_VERSION = 9

        /** What a new Whisper engine detects between - German and English cover most mixing. */
        const val WHISPER_DEFAULT_LANGUAGES = "de,en"

        /**
         * First launch: only the zero-setup engine is configured, so dictation works before
         * the user has typed a single URL or downloaded a model.
         */
        fun defaults(): AmethystSettings = AmethystSettings(
            activeEngineId = EnginePresets.ANDROID_ON_DEVICE.id,
            engines = listOf(EngineConfig.fromPreset(EnginePresets.ANDROID_ON_DEVICE, "de-DE")),
        )
    }
}

/** Serialises settings for DataStore. Tolerates unknown keys so older builds survive upgrades. */
object SettingsCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(settings: AmethystSettings): String = json.encodeToString(settings)

    /** Falls back to defaults rather than throwing: corrupt settings must not brick the app. */
    fun decode(raw: String): AmethystSettings = runCatching {
        migrate(json.decodeFromString<AmethystSettings>(raw))
    }.getOrElse { AmethystSettings.defaults() }

    /**
     * Version 1 put "de-DE" on every engine it created, Whisper included. Forced to German,
     * Whisper translates English speech instead of writing it down, so that default - never
     * something the user chose - becomes the German/English pair Whisper detects between.
     */
    internal fun migrate(settings: AmethystSettings): AmethystSettings {
        if (settings.schemaVersion >= AmethystSettings.SCHEMA_VERSION) return settings
        // Version 6: ElevenLabs Realtime was offered but never worked.
        val kept = settings.engines.filterNot { it.kind == EngineKind.ELEVENLABS_REALTIME }
        val active = settings.activeEngineId.takeIf { id -> kept.any { it.id == id } }
            ?: kept.firstOrNull()?.id ?: EnginePresets.ANDROID_ON_DEVICE.id
        // Version 7: the cleanup the active engine had becomes the cleanup; the fallback switch
        // becomes the fallback setting.
        val cleanup = settings.cleanup ?: if (settings.schemaVersion < 7) {
            kept.firstOrNull { it.id == active && it.kind == EngineKind.OPENAI_COMPATIBLE }
                ?.let { engine -> engine.cleanupModel?.trim()?.takeIf { it.isNotEmpty() }?.let { CleanupChoice(engine.id, it) } }
        } else {
            null
        }
        val fallback = if (settings.schemaVersion < 7 && !settings.capture.localFallback) {
            FallbackSettings(FallbackMode.OFF)
        } else {
            settings.fallback
        }
        return settings.copy(
            activeEngineId = active,
            cleanup = cleanup,
            fallback = fallback,
            // Version 8: whoever had settings before the introduction existed has set Amber up.
            onboardingDone = settings.onboardingDone || settings.schemaVersion < 8,
            profiles = if (settings.schemaVersion < 9) settings.profiles.map(::migrateProfile) else settings.profiles,
            engines = kept.map { engine ->
                when {
                    engine.kind == EngineKind.WHISPER_CPP && engine.languageTag == "de-DE" ->
                        engine.copy(languageTag = AmethystSettings.WHISPER_DEFAULT_LANGUAGES)
                    // Version 3: Tinfoil's per-model hosts (*.inf.tinfoil.sh) no longer resolve.
                    engine.baseUrl?.contains(".inf.tinfoil.sh") == true ->
                        engine.copy(baseUrl = EnginePresets.TINFOIL_BASE_URL)
                    else -> engine
                }.let { if (it.id == EnginePresets.TINFOIL.id) it.copy(requireEnclave = true) else it }
                    .copy(cleanupModel = null)
            },
            schemaVersion = AmethystSettings.SCHEMA_VERSION,
        )
    }
}

/** Version 9: the per-app switch "never" becomes Off, "follow" and "always" the default. */
@Suppress("DEPRECATION")
private fun migrateProfile(profile: AppProfile): AppProfile = profile.copy(
    appCleanup = if (profile.cleanup == false) AppCleanup(AppCleanupMode.OFF) else profile.appCleanup,
    cleanup = null,
)

/**
 * A cleanup model on one of the configured server engines, whose address, key and enclave
 * check it uses. Later also a model on the phone.
 */
@Serializable
data class CleanupChoice(val engineId: String, val model: String)

@Serializable
enum class FallbackMode {
    /** The best local model that is ready: Parakeet, then Whisper. */
    AUTOMATIC,

    /** A failed server is a failed dictation. */
    OFF,

    /** The engine in [FallbackSettings.engineId], if it is allowed and ready. */
    ENGINE,
}

@Serializable
data class FallbackSettings(val mode: FallbackMode = FallbackMode.AUTOMATIC, val engineId: String? = null)

/** The statistics: kept on this phone only, without text or audio. */
@Serializable
data class StatsSettings(
    val keep: Boolean = true,
    /** What "time saved" compares with; 40 words a minute is a typical phone typist. */
    val typingWpm: Int = 40,
)
