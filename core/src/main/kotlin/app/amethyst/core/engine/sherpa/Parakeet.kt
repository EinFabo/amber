package app.amethyst.core.engine.sherpa

import app.amethyst.core.audio.LongAudio
import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.engine.ChunkedTranscription
import app.amethyst.core.engine.EngineCapabilities
import app.amethyst.core.engine.SttEngine
import app.amethyst.core.engine.download.DownloadTransport
import app.amethyst.core.engine.download.FileDownload
import app.amethyst.core.engine.Problem
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.engine.TranscribeOptions
import app.amethyst.core.engine.whisper.WhisperInput
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.text.Transcript
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * NVIDIA's Parakeet TDT 0.6B v3, int8, as exported for sherpa-onnx: a transducer that reads
 * the whole recording at once - no 30 s window like Whisper - so on a phone CPU it runs far
 * faster than Whisper large-v3-turbo at close to its accuracy (German FLEURS WER 5.0 vs 4.3).
 * 25 European languages, detected by itself, with punctuation and capitalization.
 *
 * Four files, pinned by repository revision, size and SHA-256.
 */
object ParakeetModel {

    data class FileSpec(val name: String, val sizeBytes: Long, val sha256: String)

    const val ID = "parakeet-tdt-0.6b-v3-int8"
    const val DISPLAY_NAME = "Parakeet TDT 0.6B v3"

    private const val REPO = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
    private const val REVISION = "2bda32ec70b097a55adaa07d9a7173915b43cc78"

    val FILES = listOf(
        FileSpec("encoder.int8.onnx", 652_184_281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
        FileSpec("decoder.int8.onnx", 11_845_275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
        FileSpec("joiner.int8.onnx", 6_355_277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
        FileSpec("tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
    )

    val TOTAL_BYTES: Long = FILES.sumOf { it.sizeBytes }

    fun url(file: FileSpec): String = "https://huggingface.co/$REPO/resolve/$REVISION/${file.name}"

    /** Every file present at its pinned size. Hashes were checked when the files were written. */
    fun isComplete(dir: File): Boolean = FILES.all { File(dir, it.name).length() == it.sizeBytes }

    /**
     * One file's download: what arrived stays next to its target and is continued by the next
     * run, and the file takes its place only with its pinned size and hash ([FileDownload]) -
     * the directory never holds a half-written model.
     */
    fun download(file: FileSpec, dir: File, transport: DownloadTransport): FileDownload =
        FileDownload(url(file), File(dir, file.name), file.sizeBytes, file.sha256, transport)

    /** The files that are not in place yet. */
    fun missing(dir: File): List<FileSpec> = FILES.filter { File(dir, it.name).length() != it.sizeBytes }

    /** What still has to come over the network: the model without its complete files and without what was kept of the others. */
    fun missingBytes(dir: File): Long = FILES.sumOf { FileDownload.missingBytes(File(dir, it.name), it.sizeBytes) }

    /** Lets go of what was kept of unfinished files - a download nobody wants any more. */
    fun discardKept(dir: File) = FILES.forEach { FileDownload.discardKept(File(dir, it.name)) }
}

/** The native side, behind an interface so the engine is tested on the JVM. */
interface TransducerRuntime {
    /** Loads the model in [modelDir] unless it is already loaded. */
    fun ensureLoaded(modelDir: String)

    /** Recognizes mono 16 kHz float samples. Blocks. */
    fun transcribe(samples: FloatArray): String
}

/** Parakeet on the device through sherpa-onnx. The audio never leaves the phone. */
class ParakeetEngine(
    override val id: String,
    override val displayName: String,
    private val modelDir: String,
    private val runtime: TransducerRuntime,
    private val clock: () -> Long = System::currentTimeMillis,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : SttEngine {

    override val capabilities = EngineCapabilities(
        trustLevel = TrustLevel.ON_DEVICE,
        worksOffline = true,
        needsModelDownload = true,
        // Its cost follows the audio length, so chunks pay off; a little longer than Whisper's
        // keeps the sentence context it is good with.
        chunkable = true,
        minChunkMs = 8_000,
        typicalRate = 0.1,
        overheadMs = 100,
    )

    override suspend fun warmUp(): Result<Unit> = withContext(compute) {
        guarded { runtime.ensureLoaded(modelDir) }
    }

    override suspend fun transcribe(audio: PcmAudio, options: TranscribeOptions): Result<Transcript> {
        val startedAt = clock()
        // Language and prompt do not apply: Parakeet detects the language and keeps no prompt.
        // Nor do the user's own words: sherpa-onnx boosts hotwords for this model only with
        // modified_beam_search instead of the greedy search, and only with the model's
        // SentencePiece vocabulary (bpe.vocab) to split them into its tokens - which this export
        // does not ship. A vocabulary rebuilt from tokens.txt would guess the splits, a word
        // split differently from how the model writes it is never boosted, and the beam search
        // would change every dictation's text and speed. Left out until a model brings it.
        val samples = WhisperInput.prepare(audio)
        // Its encoder's memory grows with the square of the length: a long recording goes in
        // pieces cut at pauses ([LongAudio]), one after another.
        val pieces = LongAudio.pieces(samples)
        val texts = mutableListOf<String>()
        for (piece in pieces) {
            val part = if (pieces.size == 1) samples else samples.copyOfRange(piece.first, piece.last + 1)
            val raw = withContext(compute) {
                guarded {
                    runtime.ensureLoaded(modelDir)
                    runtime.transcribe(part)
                }
            }.getOrElse { return Result.failure(it) }
            raw.trim().takeIf { it.isNotEmpty() }?.let(texts::add)
        }
        val text = ChunkedTranscription.join(texts)
        if (text.isEmpty()) return Result.failure(SttFailure(SttFailure.NOTHING_RECOGNIZED, "parakeet returned no text"))
        return Result.success(Transcript(text, id, TrustLevel.ON_DEVICE, clock() - startedAt))
    }

    private inline fun <T> guarded(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: OutOfMemoryError) {
        Result.failure(SttFailure(Problem.OutOfMemory, "parakeet: out of memory"))
    } catch (e: Exception) {
        Result.failure(
            SttFailure(
                problem = Problem.LocalModelFailed,
                technical = "parakeet: ${e.message}",
                cause = e,
            ),
        )
    }
}
