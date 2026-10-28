package app.amethyst.core.engine.whisper

import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.engine.EngineCapabilities
import app.amethyst.core.engine.SttEngine
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.engine.TranscribeOptions
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.text.Transcript
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The native side, behind an interface so the engine's decisions - parameters, padding,
 * cleanup, error wording - are tested on the JVM without whisper.cpp.
 */
interface WhisperRuntime {

    /** Loads [modelPath] unless it is already loaded. Expensive the first time only. */
    fun ensureLoaded(modelPath: String)

    /** Runs whisper_full and returns the concatenated segment text. Blocks. */
    fun transcribe(samples: FloatArray, language: String, audioCtx: Int, threads: Int): String

    /** Makes a running [transcribe] return early. Safe to call from any thread. */
    fun abort()
}

/** Thrown by a [WhisperRuntime] when native code reports a failure. */
class WhisperRuntimeException(message: String) : Exception(message)

/**
 * Whisper running in-process through whisper.cpp. The audio never leaves the device, which is
 * the whole reason this engine exists next to the system recognizer.
 */
class WhisperCppEngine(
    override val id: String,
    override val displayName: String,
    private val modelPath: String,
    private val runtime: WhisperRuntime,
    private val threads: Int,
    /** True only for ACFT models; see [WhisperModelSpec.shortContext]. */
    private val shortContext: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : SttEngine {

    override val capabilities = EngineCapabilities(
        trustLevel = TrustLevel.ON_DEVICE,
        worksOffline = true,
        needsModelDownload = true,
    )

    override suspend fun warmUp(): Result<Unit> = withContext(compute) {
        guarded("load") { runtime.ensureLoaded(modelPath) }
    }

    override suspend fun transcribe(
        audio: PcmAudio,
        options: TranscribeOptions,
    ): Result<Transcript> {
        val startedAt = clock()
        val samples = WhisperInput.prepare(audio)
        return abortable {
            guarded("transcribe") {
                runtime.ensureLoaded(modelPath)
                runtime.transcribe(
                    samples = samples,
                    language = WhisperInput.languageFor(options.languageTag),
                    audioCtx = if (shortContext) WhisperInput.audioCtxFor(samples.size) else 0,
                    threads = threads,
                )
            }
        }.mapCatching { raw ->
            val text = WhisperOutput.clean(raw)
            if (text.isBlank()) {
                throw SttFailure(SttFailure.NOTHING_RECOGNIZED, "whisper returned no speech: \"$raw\"")
            }
            Transcript(
                text = text,
                engineId = id,
                trustLevel = TrustLevel.ON_DEVICE,
                latencyMs = clock() - startedAt,
            )
        }
    }

    /**
     * Native inference cannot be interrupted like a JVM thread. When the dictation is cancelled
     * - the user tapped stop, or the watchdog fired - a sibling coroutine tells whisper.cpp to
     * give up, so the CPU is not kept busy for an answer nobody will read.
     */
    private suspend fun <T> abortable(block: () -> T): T = coroutineScope {
        // Written on the compute thread, read by the watcher on the caller's.
        val finished = AtomicBoolean(false)
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) runtime.abort()
            }
        }
        try {
            withContext(compute) { block() }
        } finally {
            finished.set(true)
            watcher.cancel()
        }
    }

    private inline fun <T> guarded(step: String, block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: WhisperRuntimeException) {
        Result.failure(
            SttFailure(
                userMessage = "The local model failed to run. " +
                    "Download it again in the settings.",
                technical = "whisper $step: ${e.message}",
                cause = e,
            ),
        )
    } catch (e: OutOfMemoryError) {
        Result.failure(
            SttFailure(
                userMessage = "Not enough memory for the local model.",
                technical = "whisper $step: out of memory",
            ),
        )
    }
}
