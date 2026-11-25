package app.amethyst.core.engine

import app.amethyst.core.engine.sherpa.ParakeetModel
import app.amethyst.core.engine.whisper.WhisperModels
import app.amethyst.core.settings.EngineConfig

/** What the introduction needs to know about the phone to suggest an engine. */
data class PhoneFacts(
    val totalRamMb: Long,
    val freeStorageMb: Long,
    /** whisper.cpp is built for dot-product and fp16 instructions; older cores cannot run it. */
    val whisperCpu: Boolean,
    /** Whether Android's recognizer keeps the voice on the phone. */
    val androidOnDevice: Boolean,
    /** Whether the phone has a recognizer of Android's at all; a few come without one. */
    val androidRecognizer: Boolean = true,
)

/** The engines the introduction offers, each with what it downloads. */
enum class EngineOption(val presetId: String, val whisperModelId: String?, val downloadBytes: Long) {
    PARAKEET(EnginePresets.LOCAL_PARAKEET.id, null, ParakeetModel.TOTAL_BYTES),
    ACFT_LARGE(EnginePresets.LOCAL_WHISPER.id, WhisperModels.ACFT_LARGE_V3_TURBO_Q8.id, WhisperModels.ACFT_LARGE_V3_TURBO_Q8.sizeBytes),
    FUTO_244(EnginePresets.LOCAL_WHISPER.id, WhisperModels.FUTO_MULTILINGUAL_244.id, WhisperModels.FUTO_MULTILINGUAL_244.sizeBytes),
    FUTO_74(EnginePresets.LOCAL_WHISPER.id, WhisperModels.FUTO_MULTILINGUAL_74.id, WhisperModels.FUTO_MULTILINGUAL_74.sizeBytes),
    ANDROID(EnginePresets.ANDROID_ON_DEVICE.id, null, 0),

    /** A server with the user's own key - set up in Settings, not in the introduction. */
    SERVER("", null, 0),
}

data class Advice(val recommended: EngineOption, val others: List<EngineOption>)

/** What the welcome step says about where the voice goes ([EngineAdvice.welcomePrivacy]). */
enum class WelcomePrivacy {
    /** The quick setup's pick recognizes on the phone: "Your voice stays on this phone". */
    ON_PHONE,

    /** Android's recognition, which may send the voice to its maker. */
    ANDROID_MAY_SEND,

    /** Nothing on the phone recognizes speech yet (de-Googled, no model fits): a model or a server, the user's choice. */
    NOTHING_YET,
}

/**
 * The engine to suggest on this phone: the most accurate one that is fast here and fits. The
 * thresholds are a first guess (Parakeet needs about 700 MB of memory while dictating) and
 * live here to be tuned on devices.
 */
object EngineAdvice {

    /** Nominal memory, as on the box: 6 GB phones and up. */
    const val PARAKEET_MIN_RAM_GB = 6L
    const val WHISPER_MIN_RAM_GB = 3L
    const val STORAGE_MARGIN_MB = 300L

    /**
     * The memory the phone was sold with. Android's totalMem leaves out what the kernel and the
     * modem keep - a 6 GB phone reports about 5.5 GB - so it is rounded up to whole GiB.
     */
    fun nominalRamGb(totalRamMb: Long): Long = (totalRamMb * 1_000_000 + GIB - 1) / GIB

    private const val GIB = 1L shl 30

    /**
     * The engine to suggest. Where no model fits, Android's recognition is the fallback - but
     * only on a phone that has one ([PhoneFacts.androidRecognizer]): on a de-Googled phone it
     * would be a row that fails every dictation. There the answer is [EngineOption.SERVER], not
     * a new "none": a server is a real way out (Settings sets it up), the engine step already
     * lists it, and the screens can say "download a model, or use a server" from the one value.
     * Android's recognition is not offered at all on such a phone.
     */
    fun recommend(phone: PhoneFacts): Advice {
        val fallback = if (phone.androidRecognizer) EngineOption.ANDROID else EngineOption.SERVER
        val recommended = listOf(EngineOption.PARAKEET, EngineOption.FUTO_244, EngineOption.FUTO_74)
            .firstOrNull { fits(it, phone) } ?: fallback
        val local = listOf(EngineOption.ACFT_LARGE, EngineOption.PARAKEET, EngineOption.FUTO_244, EngineOption.FUTO_74)
            .filter { it != recommended && fits(it, phone) }
        val android = EngineOption.ANDROID.takeIf { it != recommended && phone.androidRecognizer }
        val others = local + listOfNotNull(android, EngineOption.SERVER.takeIf { it != recommended })
        return Advice(recommended, others)
    }

    /**
     * Whether what the quick setup picks here keeps the voice on the phone: any model does;
     * Android's recognition, picked where none fits, only where it recognizes on the phone. The
     * welcome step promises "stays on this phone" only then.
     */
    fun keepsVoiceOnPhone(phone: PhoneFacts): Boolean = when (recommend(phone).recommended) {
        EngineOption.ANDROID -> phone.androidOnDevice
        // Nothing recognizes yet: no promise about where the voice goes.
        EngineOption.SERVER -> false
        else -> true
    }

    /**
     * The welcome step's line about the voice, from the quick setup's pick here: on the phone
     * ([keepsVoiceOnPhone]), Android's recognition that may send it on, or - where the pick is
     * [EngineOption.SERVER] because the phone has no recognizer of Android's and no model fits -
     * that nothing recognizes yet, rather than naming a recognition the phone does not have.
     */
    fun welcomePrivacy(phone: PhoneFacts): WelcomePrivacy = when {
        keepsVoiceOnPhone(phone) -> WelcomePrivacy.ON_PHONE
        recommend(phone).recommended == EngineOption.SERVER -> WelcomePrivacy.NOTHING_YET
        else -> WelcomePrivacy.ANDROID_MAY_SEND
    }

    fun fits(option: EngineOption, phone: PhoneFacts): Boolean {
        val room = option.downloadBytes / 1_000_000 + STORAGE_MARGIN_MB <= phone.freeStorageMb
        val cpu = when (option) {
            EngineOption.ACFT_LARGE, EngineOption.FUTO_244, EngineOption.FUTO_74 -> phone.whisperCpu
            else -> true
        }
        return room && cpu && fitsInMemory(option, phone.totalRamMb)
    }

    /** Whether [option]'s model fits the memory of a phone that reports [totalRamMb] (Android's totalMem). */
    fun fitsInMemory(option: EngineOption, totalRamMb: Long): Boolean {
        val ramGb = nominalRamGb(totalRamMb)
        return when (option) {
            EngineOption.PARAKEET, EngineOption.ACFT_LARGE -> ramGb >= PARAKEET_MIN_RAM_GB
            EngineOption.FUTO_244 -> ramGb >= WHISPER_MIN_RAM_GB
            EngineOption.FUTO_74, EngineOption.ANDROID, EngineOption.SERVER -> true
        }
    }

    /**
     * The option [config] runs, for weighing it against the phone: Parakeet, or a Whisper model
     * from the catalog. Null for a Whisper file the user picked, and for any engine elsewhere.
     */
    fun optionOf(config: EngineConfig): EngineOption? = optionOf(config.kind, config.modelId)

    fun optionOf(kind: EngineKind, modelId: String?): EngineOption? = when (kind) {
        EngineKind.LOCAL_SHERPA -> EngineOption.PARAKEET
        EngineKind.WHISPER_CPP -> {
            val model = modelId?.takeIf { it.isNotBlank() } ?: WhisperModels.default.id
            EngineOption.entries.firstOrNull { it.whisperModelId == model }
        }
        else -> null
    }

    /**
     * Whether Settings marks a [kind]/[modelId] "Too big for this phone's memory" on a phone that
     * reports [totalRamMb]: a catalog model that [fitsInMemory] says no to. Never for a file the
     * user picked (size unknown), another engine, or memory not known (0).
     */
    fun tooBigForMemory(kind: EngineKind, modelId: String?, totalRamMb: Long): Boolean {
        if (totalRamMb <= 0) return false
        val option = optionOf(kind, modelId) ?: return false
        return !fitsInMemory(option, totalRamMb)
    }

    fun tooBigForMemory(preset: EnginePreset, totalRamMb: Long): Boolean =
        tooBigForMemory(preset.kind, preset.modelId, totalRamMb)
}
