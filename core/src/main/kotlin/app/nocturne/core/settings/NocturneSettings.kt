package app.nocturne.core.settings

import app.nocturne.core.audio.VadConfig
import app.nocturne.core.engine.EngineKind
import app.nocturne.core.engine.EnginePreset
import app.nocturne.core.engine.EnginePresets
import app.nocturne.core.text.NormalizeOptions
import app.nocturne.core.text.SpliceOptions
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
    }
}

@Serializable
data class CaptureSettings(
    val autoStopOnSilence: Boolean = true,
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
}

@Serializable
enum class BubbleEdge { LEFT, RIGHT }

@Serializable
data class BubbleSettings(
    val edge: BubbleEdge = BubbleEdge.RIGHT,
    /** 0f = top of screen, 1f = bottom. */
    val verticalBias: Float = 0.72f,
    val sizeDp: Int = 56,
    val haptics: Boolean = true,
    /** Fade the bubble out while no editable field is focused, so it stays out of the way. */
    val dimWhenNoTextField: Boolean = true,
)

@Serializable
data class InsertionSettings(
    val autoSpace: Boolean = true,
    val autoCapitalize: Boolean = true,
    val gluePunctuation: Boolean = true,
    val rememberStrategyPerApp: Boolean = true,
) {
    fun toSpliceOptions(): SpliceOptions = SpliceOptions(
        autoSpace = autoSpace,
        autoCapitalize = autoCapitalize,
        gluePunctuation = gluePunctuation,
    )
}

@Serializable
data class NocturneSettings(
    val activeEngineId: String = EnginePresets.ANDROID_ON_DEVICE.id,
    val engines: List<EngineConfig> = emptyList(),
    val capture: CaptureSettings = CaptureSettings(),
    val bubble: BubbleSettings = BubbleSettings(),
    val insertion: InsertionSettings = InsertionSettings(),
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    val activeEngine: EngineConfig?
        get() = engines.firstOrNull { it.id == activeEngineId }

    fun withEngine(config: EngineConfig): NocturneSettings {
        val replaced = engines.map { if (it.id == config.id) config else it }
        return copy(engines = if (replaced.any { it.id == config.id }) replaced else engines + config)
    }

    companion object {
        const val SCHEMA_VERSION = 1

        /**
         * First launch: only the zero-setup engine is configured, so dictation works before
         * the user has typed a single URL or downloaded a model.
         */
        fun defaults(): NocturneSettings = NocturneSettings(
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

    fun encode(settings: NocturneSettings): String = json.encodeToString(settings)

    /** Falls back to defaults rather than throwing: corrupt settings must not brick the app. */
    fun decode(raw: String): NocturneSettings = runCatching {
        json.decodeFromString<NocturneSettings>(raw)
    }.getOrElse { NocturneSettings.defaults() }
}
