package app.amethyst.core.engine.whisper

import app.amethyst.core.engine.FileProblem
import app.amethyst.core.engine.ModelFileException
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
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
        note = "The previous large model. It always works through a full 30 s, so even a short " +
            "dictation waits several seconds. The tuned large-v3-turbo above is as accurate " +
            "and about three times faster. Switch to it and delete this one.",
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

    /**
     * The largest file taken as an own model: Whisper large in 16-bit floats is 3.1 GB, every
     * model a phone can run is far smaller. A bigger file is not one - and must not fill the
     * phone while it is copied.
     */
    const val MAX_IMPORT_BYTES = 4_000_000_000L

    /** "ggml" as a little-endian uint32, which is how whisper.cpp writes it. */
    private const val GGML_MAGIC = 0x67676d6c

    /** "GGUF" read the same way: llama.cpp's format, which whisper.cpp does not read. */
    private const val GGUF_MAGIC = 0x46554747

    /**
     * Reads the header of a whisper.cpp model file. A failure names the problem in plain words,
     * because the most likely cause is the user picking the wrong file - an APK, a zip, a
     * model for a different runtime - and native code would only crash on it.
     *
     * Every field whisper.cpp sizes memory or divides by is held to what Whisper has: its
     * loader takes them as they are (whisper_model_load), and a head count of 0 or a layer
     * count in the millions ends the process, not the load.
     */
    fun inspect(header: ByteArray): Result<WhisperModelInfo> {
        if (header.size < HEADER_BYTES) {
            return failure(FileProblem.TooSmallForWhisper, "The file is too small to be a Whisper model.")
        }
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        when (buffer.getInt(0)) {
            GGML_MAGIC -> Unit
            GGUF_MAGIC -> return failure(FileProblem.NotWhisperCpp, "A GGUF file: whisper.cpp reads ggml model files only.")
            else -> return failure(FileProblem.NotWhisperCpp, "This is not a whisper.cpp model (no ggml header).")
        }
        // Layout after the magic: n_vocab, n_audio_ctx, n_audio_state, n_audio_head,
        // n_audio_layer, n_text_ctx, n_text_state, n_text_head, n_text_layer, n_mels, ftype.
        val vocabulary = buffer.getInt(4)
        val audioCtx = buffer.getInt(8)
        val audioState = buffer.getInt(12)
        val audioHeads = buffer.getInt(16)
        val audioLayers = buffer.getInt(20)
        val textCtx = buffer.getInt(24)
        val textState = buffer.getInt(28)
        val textHeads = buffer.getInt(32)
        val textLayers = buffer.getInt(36)
        val mels = buffer.getInt(40)
        val ftype = buffer.getInt(44)

        val plausible = vocabulary in 50_000..60_000 &&
            audioCtx == 1500 &&
            audioState in 256..2048 &&
            audioHeads in 1..64 && audioState % audioHeads == 0 &&
            audioLayers in 1..64 &&
            textCtx in 1..4096 &&
            // whisper.cpp asserts it (cross-attention joins the two).
            textState == audioState &&
            textHeads in 1..64 && textState % textHeads == 0 &&
            textLayers in 1..64 &&
            (mels == 80 || mels == 128) &&
            ftype >= 0
        if (!plausible) {
            return failure(FileProblem.NotWhisper, "The file has a ggml header but does not look like Whisper.")
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

    /**
     * The whole of a model file of [size] bytes, read from [input] the way whisper.cpp's loader
     * walks it (whisper_model_load), before an own file is handed to it: the header
     * ([inspect]), the mel filters, the vocabulary and every tensor's header - each length
     * within bounds before anything is sized by it, each part inside the file, and exactly the
     * tensors Whisper has. The loader trusts these lengths: a token "4 GB long" or a filter
     * table of a billion floats ends the process in an allocation, and a file cut off midway
     * gives it half a model.
     *
     * A catalog model needs none of it: its size and hash are pinned. Tensor data is skipped,
     * not read. A tensor type this check does not know the size of stops it there, the file
     * taken as far as it got: refusing it would refuse a model whisper.cpp may well load.
     */
    fun check(input: InputStream, size: Long): Result<WhisperModelInfo> {
        if (size > MAX_IMPORT_BYTES) {
            return failure(FileProblem.TooLarge(MAX_IMPORT_BYTES / 1_000_000), "Larger than $MAX_IMPORT_BYTES bytes.")
        }
        val reader = Reader(input, size)
        return try {
            val header = reader.bytes(minOf(HEADER_BYTES.toLong(), size).toInt())
            val info = inspect(header).getOrElse { return Result.failure(it) }

            // The mel filters: n_mel times n_fft floats.
            val filterMels = reader.int()
            val filterFft = reader.int()
            if (filterMels !in 1..MAX_FILTER_SIDE || filterFft !in 1..MAX_FILTER_SIDE) {
                return failure(FileProblem.NotWhisper, "Mel filters of $filterMels x $filterFft.")
            }
            reader.skip(filterMels.toLong() * filterFft * 4)

            // The vocabulary: a count, then each token as its length and its bytes.
            val tokens = reader.int()
            if (tokens !in 0..info.vocabularySize) {
                return failure(FileProblem.NotWhisper, "$tokens tokens for a vocabulary of ${info.vocabularySize}.")
            }
            repeat(tokens) {
                val length = reader.int()
                if (length !in 0..MAX_TOKEN_BYTES) return failure(FileProblem.NotWhisper, "A token of $length bytes.")
                reader.skip(length.toLong())
            }

            // The tensors, until the file ends.
            val expected = TENSORS_BESIDE_LAYERS + TENSORS_PER_AUDIO_LAYER * info.audioLayers + TENSORS_PER_TEXT_LAYER * info.textLayers
            var count = 0
            while (reader.remaining > 0) {
                val dims = reader.int()
                val nameLength = reader.int()
                val type = reader.int()
                if (dims !in 0..4 || nameLength !in 1..MAX_NAME_BYTES || type !in 0 until GGML_TYPE_COUNT) {
                    return failure(FileProblem.NotWhisper, "Tensor ${count + 1}: $dims dimensions, a name of $nameLength bytes, type $type.")
                }
                var elements = 1L
                repeat(dims) {
                    val extent = reader.int()
                    if (extent < 0) return failure(FileProblem.NotWhisper, "Tensor ${count + 1}: an extent of $extent.")
                    // No ggml type packs 16 elements into a byte: more cannot be in the file.
                    // Asked before multiplying, so four extents cannot overflow.
                    if (extent != 0 && elements > size * MAX_ELEMENTS_PER_BYTE / extent) {
                        return failure(FileProblem.CutShort, "Tensor ${count + 1}: more elements than the file holds.")
                    }
                    elements *= extent
                }
                reader.skip(nameLength.toLong())
                val (blockBytes, blockElements) = TYPE_SIZES[type] ?: return Result.success(info)
                if (elements % blockElements != 0L) {
                    return failure(FileProblem.NotWhisper, "Tensor ${count + 1}: $elements elements in blocks of $blockElements.")
                }
                reader.skip(elements / blockElements * blockBytes)
                count++
                if (count > expected) return failure(FileProblem.NotWhisper, "More than the $expected tensors Whisper has.")
            }
            if (count < expected) return failure(FileProblem.CutShort, "$count of $expected tensors.")
            Result.success(info)
        } catch (_: CutOff) {
            failure(FileProblem.CutShort, "The file ends inside the model.")
        }
    }

    /** Little-endian fields read in order, never past [size]. */
    private class Reader(input: InputStream, size: Long) {
        private val stream = DataInputStream(input)
        var remaining = size
            private set

        fun bytes(count: Int): ByteArray {
            if (count > remaining) throw CutOff()
            return ByteArray(count).also { buffer ->
                try {
                    stream.readFully(buffer)
                } catch (_: EOFException) {
                    throw CutOff()
                }
                remaining -= count
            }
        }

        fun int(): Int = ByteBuffer.wrap(bytes(4)).order(ByteOrder.LITTLE_ENDIAN).int

        fun skip(count: Long) {
            if (count < 0 || count > remaining) throw CutOff()
            var left = count
            while (left > 0) {
                val skipped = stream.skip(left)
                if (skipped > 0) {
                    left -= skipped
                } else if (stream.read() < 0) {
                    throw CutOff()
                } else {
                    left--
                }
            }
            remaining -= count
        }
    }

    /** The file ends before the part being read does. */
    private class CutOff : Exception()

    /**
     * The tensors whisper.cpp's loader creates and wants each of, no more, no less: seven of the
     * encoder's own (positions, two convolutions, the last norm) and four of the decoder's
     * (positions, token embedding, its norm), 15 per encoder layer and 24 per decoder layer
     * (the 15 plus 9 of cross-attention) - 587 for large-v3-turbo's 32 and 4 layers.
     */
    private const val TENSORS_BESIDE_LAYERS = 11
    private const val TENSORS_PER_AUDIO_LAYER = 15
    private const val TENSORS_PER_TEXT_LAYER = 24

    /** More than any ggml type packs into a byte (the densest, 1-bit ones, come to about 8). */
    private const val MAX_ELEMENTS_PER_BYTE = 16L

    /** Whisper's mel filters are 80 or 128 by 201; anything near a thousand is not one. */
    private const val MAX_FILTER_SIDE = 1024

    /** Whisper's longest token is a few dozen bytes. */
    private const val MAX_TOKEN_BYTES = 1024

    /** Tensor names like "decoder.blocks.31.cross_attn.value.weight". */
    private const val MAX_NAME_BYTES = 256

    /** ggml's GGML_TYPE_COUNT in the whisper.cpp this app is built with: the loader refuses others. */
    private const val GGML_TYPE_COUNT = 43

    /**
     * Bytes per block and elements per block of the ggml types a Whisper model is made of
     * (ggml-common.h): floats, the 32-element quantizations and the 256-element K ones.
     */
    private val TYPE_SIZES: Map<Int, Pair<Long, Long>> = mapOf(
        0 to (4L to 1L), // F32
        1 to (2L to 1L), // F16
        2 to (18L to 32L), // Q4_0
        3 to (20L to 32L), // Q4_1
        6 to (22L to 32L), // Q5_0
        7 to (24L to 32L), // Q5_1
        8 to (34L to 32L), // Q8_0
        9 to (36L to 32L), // Q8_1
        10 to (84L to 256L), // Q2_K
        11 to (110L to 256L), // Q3_K
        12 to (144L to 256L), // Q4_K
        13 to (176L to 256L), // Q5_K
        14 to (210L to 256L), // Q6_K
        15 to (292L to 256L), // Q8_K
        30 to (2L to 1L), // BF16
    )

    private fun failure(problem: FileProblem, detail: String): Result<WhisperModelInfo> =
        Result.failure(ModelFileException(problem, detail))
}
