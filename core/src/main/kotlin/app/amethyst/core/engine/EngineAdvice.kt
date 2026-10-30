package app.amethyst.core.engine

import app.amethyst.core.engine.sherpa.ParakeetModel
import app.amethyst.core.engine.whisper.WhisperModels

/** What the introduction needs to know about the phone to suggest an engine. */
data class PhoneFacts(
    val totalRamMb: Long,
    val freeStorageMb: Long,
    /** whisper.cpp is built for dot-product and fp16 instructions; older cores cannot run it. */
    val whisperCpu: Boolean,
    /** Whether Android's recognizer keeps the voice on the phone. */
    val androidOnDevice: Boolean,
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

    fun recommend(phone: PhoneFacts): Advice {
        val recommended = listOf(EngineOption.PARAKEET, EngineOption.FUTO_244, EngineOption.FUTO_74)
            .firstOrNull { fits(it, phone) } ?: EngineOption.ANDROID
        val local = listOf(EngineOption.ACFT_LARGE, EngineOption.PARAKEET, EngineOption.FUTO_244, EngineOption.FUTO_74)
            .filter { it != recommended && fits(it, phone) }
        val others = local + listOfNotNull(EngineOption.ANDROID.takeIf { it != recommended }) + EngineOption.SERVER
        return Advice(recommended, others)
    }

    fun fits(option: EngineOption, phone: PhoneFacts): Boolean {
        val room = option.downloadBytes / 1_000_000 + STORAGE_MARGIN_MB <= phone.freeStorageMb
        val ramGb = nominalRamGb(phone.totalRamMb)
        return when (option) {
            EngineOption.PARAKEET -> room && ramGb >= PARAKEET_MIN_RAM_GB
            EngineOption.ACFT_LARGE -> room && phone.whisperCpu && ramGb >= PARAKEET_MIN_RAM_GB
            EngineOption.FUTO_244 -> room && phone.whisperCpu && ramGb >= WHISPER_MIN_RAM_GB
            EngineOption.FUTO_74 -> room && phone.whisperCpu
            EngineOption.ANDROID, EngineOption.SERVER -> true
        }
    }
}
