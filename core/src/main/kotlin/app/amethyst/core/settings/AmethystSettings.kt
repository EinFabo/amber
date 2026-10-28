package app.amethyst.core.settings

import app.amethyst.core.audio.VadConfig
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.EnginePreset
import app.amethyst.core.engine.EnginePresets
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

    fun toNormalizeOptions(): NormalizeOptions = NormalizeOptions(stripFillers = stripFillers)

    /**
     * The watchdog always has to outlast the recording itself, or a long dictation would be
     * killed just as it was about to be transcribed.
     */
    fun effectiveTotalTimeoutMs(): Long =
        maxOf(totalTimeoutMs.toLong(), maxDurationMs.toLong() + TRANSCRIPTION_HEADROOM_MS)

    private companion object {
        const val TRANSCRIPTION_HEADROOM_MS = 60_000L
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
)

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
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    val activeEngine: EngineConfig?
        get() = engines.firstOrNull { it.id == activeEngineId }

    fun withEngine(config: EngineConfig): AmethystSettings {
        val replaced = engines.map { if (it.id == config.id) config else it }
        return copy(engines = if (replaced.any { it.id == config.id }) replaced else engines + config)
    }

    companion object {
        /** 2: Whisper takes a list of languages; see [SettingsCodec.migrate]. */
        const val SCHEMA_VERSION = 2

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
        return settings.copy(
            engines = settings.engines.map { engine ->
                if (engine.kind == EngineKind.WHISPER_CPP && engine.languageTag == "de-DE") {
                    engine.copy(languageTag = AmethystSettings.WHISPER_DEFAULT_LANGUAGES)
                } else {
                    engine
                }
            },
            schemaVersion = AmethystSettings.SCHEMA_VERSION,
        )
    }
}
