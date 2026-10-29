package app.amethyst.core.engine.whisper

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A downloadable whisper.cpp model. Size and hash are pinned so a truncated download or a
 * file swapped on the server is caught before it is ever handed to native code.
 */
data class WhisperModelSpec(
    val id: String,
    val displayName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    /** User-facing. */
    val note: String,
    /** One or two words placing it among the others, e.g. "fast" or "most accurate". */
    val tier: String = "",
    /**
     * Fine-tuned (ACFT) to work with an encoder context cut to the length of the audio. Only
     * then may the context be shortened; a stock Whisper model loses words when it is.
     */
    val shortContext: Boolean = false,
    /**
     * Recognition time per second of audio on a mid-range phone, before Amber has measured this
     * one itself (RecognitionEstimate learns the real rate).
     */
    val typicalRate: Double = if (shortContext) 0.12 else 3.5,
    /** Context positions beyond the audio, for [shortContext] models; see WhisperInput. */
    val contextSlack: Int = WhisperInput.AUDIO_CTX_SLACK,
)

object WhisperModels {

    /** Whisper tiny, ACFT. FUTO itself does not recommend it for German. */
    val FUTO_MULTILINGUAL_39 = WhisperModelSpec(
        id = "futo-multilingual-39",
        displayName = "FUTO Multilingual-39",
        url = "https://keyboard.futo.org/voice-input-multilingual-39.bin",
        sizeBytes = 43_537_450L,
        sha256 = "07aa4d514144deacf5ffec5cacb36c93dee272fda9e64ac33a801f8cd5cbd953",
        note = "Whisper tiny. Fastest and smallest, but only fair at German.",
        tier = "fastest",
        shortContext = true,
    )

    /** Whisper base, ACFT, q8_0. FUTO's recommendation for German. */
    val FUTO_MULTILINGUAL_74 = WhisperModelSpec(
        id = "futo-multilingual-74",
        displayName = "FUTO Multilingual-74",
        url = "https://keyboard.futo.org/voice-input-multilingual-74.bin",
        sizeBytes = 81_768_602L,
        sha256 = "e44f352c9aa2c3609dece20c733c4ad4a75c28cd9ab07d005383df55fa96efc4",
        note = "Whisper base, fine-tuned for short dictations. FUTO's pick for German.",
        tier = "fast",
        shortContext = true,
    )

    /** Whisper small, ACFT. Noticeably more accurate, still quick thanks to the short context. */
    val FUTO_MULTILINGUAL_244 = WhisperModelSpec(
        id = "futo-multilingual-244",
        displayName = "FUTO Multilingual-244",
        url = "https://keyboard.futo.org/voice-input-multilingual-244.bin",
        sizeBytes = 264_464_624L,
        sha256 = "15ef255465a6dc582ecf1ec651a4618c7ee2c18c05570bbe46493d248d465ac4",
        note = "Whisper small, fine-tuned. Noticeably more accurate, still quick on current phones.",
        tier = "more accurate",
        shortContext = true,
    )

    /**
     * Whisper large-v3-turbo, ACFT fine-tuned (MahmoudAshraf/acft-whisper-large-v3-turbo,
     * Apache-2.0) so its encoder may stop at the end of the speech instead of always running
     * a 30 s window - the same method as the FUTO models. q8_0: ggml repacks q8_0 weights for
     * the dot-product units of every current phone CPU, which q5_0 gets no benefit from.
     *
     * The file is a third-party conversion (DeadBranches/acft-whisper-large-v3-turbo_q8_0);
     * checked tensor by tensor against a conversion of the original weights - all 587
     * tensors, the vocabulary and the mel filters are identical. Pinned by hash.
     */
    val ACFT_LARGE_V3_TURBO_Q8 = WhisperModelSpec(
        id = "acft-whisper-large-v3-turbo-q8_0",
        displayName = "Whisper large-v3-turbo",
        url = "https://huggingface.co/DeadBranches/acft-whisper-large-v3-turbo_q8_0/resolve/main/acft-whisper-large-v3-turbo-q8_0.bin",
        sizeBytes = 874_188_075L,
        sha256 = "6d862034d882b83f76366fefc1bd750cef2057d2c644378c46e41e90b0331a72",
        note = "The large OpenAI model, tuned to stop where your speech stops. As accurate as " +
            "the stock large model, about three times faster on short dictations.",
        tier = "most accurate",
        shortContext = true,
        // Pixel 9a, whisper-cli over adb with the app's build flags (no ADPF boost, so the app
        // is if anything faster): 0.86 s per second of speech; the old q5_0 needed 4.4 s.
        typicalRate = 0.8,
        // As its model card recommends "for stability". Measured on 80 German FLEURS
        // sentences: 4.53 % WER with 128, 4.64 % with 64, for 2.5 % more time.
        contextSlack = 128,
    )

    /**
     * Stock Whisper large-v3-turbo, q5_0: it needs the full 30 s context for any dictation.
     * Kept so a download from before 0.16 keeps working; [ACFT_LARGE_V3_TURBO_Q8] replaces it.
     */
    val LARGE_V3_TURBO_Q5 = WhisperModelSpec(
        id = "whisper-large-v3-turbo-q5_0",
        displayName = "Whisper large-v3-turbo (old)",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
        sizeBytes = 574_041_195L,
        sha256 = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
        note = "The previous large model: always works through a full 30 s, so even a short " +
            "dictation waits several seconds. The tuned large-v3-turbo above is as accurate " +
            "and about three times faster - switch and delete this one.",
        tier = "replaced",
        shortContext = false,
    )

    val all: List<WhisperModelSpec> = listOf(
        FUTO_MULTILINGUAL_39,
        FUTO_MULTILINGUAL_74,
        FUTO_MULTILINGUAL_244,
        ACFT_LARGE_V3_TURBO_Q8,
        LARGE_V3_TURBO_Q5,
    )

    val default: WhisperModelSpec = FUTO_MULTILINGUAL_74

    /** Id under which a file the user picked is stored when it matches no known model. */
    const val IMPORTED_ID = "eigene-datei"

    fun byId(id: String?): WhisperModelSpec? = all.firstOrNull { it.id == id }

    /**
     * Recognises a picked file as one of the known downloads by its size, so an imported FUTO
     * file still gets the short context it was trained for (and its hash checked).
     */
    fun matchingSize(sizeBytes: Long?): WhisperModelSpec? =
        sizeBytes?.let { size -> all.firstOrNull { it.sizeBytes == size } }
}

/** What the header of a ggml whisper model says about it. */
data class WhisperModelInfo(
    val vocabularySize: Int,
    val audioLayers: Int,
    val textLayers: Int,
    val audioState: Int,
) {
    /** English-only Whisper models have 51864 tokens, multilingual ones 51865 or more. */
    val multilingual: Boolean get() = vocabularySize >= MULTILINGUAL_VOCAB

    val sizeName: String
        get() = when (audioState) {
            384 -> "tiny"
            512 -> "base"
            768 -> "small"
            1024 -> "medium"
            1280 -> "large"
            else -> "unknown"
        }

    private companion object {
        const val MULTILINGUAL_VOCAB = 51_865
    }
}

object WhisperModelFile {

    /** Enough bytes to cover the magic and the hyperparameter block. */
    const val HEADER_BYTES = 48

    /** "ggml" as a little-endian uint32, which is how whisper.cpp writes it. */
    private const val GGML_MAGIC = 0x67676d6c

    /**
     * Reads the header of a whisper.cpp model file. A failure names the problem in plain words,
     * because the most likely cause is the user picking the wrong file - an APK, a zip, a
     * model for a different runtime - and native code would only crash on it.
     */
    fun inspect(header: ByteArray): Result<WhisperModelInfo> {
        if (header.size < HEADER_BYTES) {
            return failure("The file is too small to be a Whisper model.")
        }
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.getInt(0) != GGML_MAGIC) {
            return failure(
                "This is not a whisper.cpp model. Expected a .bin file " +
                    "in ggml format, e.g. from FUTO.",
            )
        }
        // Layout after the magic: n_vocab, n_audio_ctx, n_audio_state, n_audio_head,
        // n_audio_layer, n_text_ctx, n_text_state, n_text_head, n_text_layer, n_mels, ftype.
        val vocabulary = buffer.getInt(4)
        val audioCtx = buffer.getInt(8)
        val audioState = buffer.getInt(12)
        val audioLayers = buffer.getInt(20)
        val textLayers = buffer.getInt(36)
        val mels = buffer.getInt(40)

        val plausible = vocabulary in 50_000..60_000 &&
            audioCtx == 1500 &&
            audioState in 256..2048 &&
            audioLayers in 1..64 &&
            textLayers in 1..64 &&
            (mels == 80 || mels == 128)
        if (!plausible) {
            return failure("The file has a ggml header but does not look like Whisper.")
        }

        return Result.success(
            WhisperModelInfo(
                vocabularySize = vocabulary,
                audioLayers = audioLayers,
                textLayers = textLayers,
                audioState = audioState,
            ),
        )
    }

    private fun failure(message: String): Result<WhisperModelInfo> =
        Result.failure(IllegalArgumentException(message))
}
