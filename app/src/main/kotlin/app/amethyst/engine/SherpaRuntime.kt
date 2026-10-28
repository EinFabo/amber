package app.amethyst.engine

import android.os.Process
import android.util.Log
import app.amethyst.core.engine.sherpa.ParakeetModel
import app.amethyst.core.engine.sherpa.TransducerRuntime
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors

/**
 * Parakeet through sherpa-onnx's offline recognizer. One recognizer stays loaded between
 * dictations (reading 650 MB on every tap would cost seconds) until [release].
 *
 * Runs on its own thread at display priority, like Whisper: through the bubble the process is
 * only a bound accessibility service, and onnxruntime's worker threads inherit the priority of
 * the thread that creates them.
 */
class SherpaRuntime : TransducerRuntime {

    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var loadedDir: String? = null

    private val executor = Executors.newSingleThreadExecutor { work ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
            work.run()
        }, "parakeet")
    }

    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    override fun ensureLoaded(modelDir: String) {
        synchronized(lock) {
            if (recognizer != null && loadedDir == modelDir) return
            releaseLocked()
            // sherpa-onnx reads a missing file as an empty buffer and onnxruntime then aborts
            // the whole process from native code; a Kotlin exception ends just the dictation.
            if (!ParakeetModel.isComplete(File(modelDir))) throw IllegalStateException("model files missing: $modelDir")
            val started = System.currentTimeMillis()
            val config = OfflineRecognizerConfig(
                // The NeMo model carries its own feature settings; sherpa-onnx reads them from
                // the model and overrides these defaults.
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = File(modelDir, "encoder.int8.onnx").path,
                        decoder = File(modelDir, "decoder.int8.onnx").path,
                        joiner = File(modelDir, "joiner.int8.onnx").path,
                    ),
                    tokens = File(modelDir, "tokens.txt").path,
                    numThreads = NativeWhisperRuntime.defaultThreads(),
                    modelType = "nemo_transducer",
                ),
                decodingMethod = "greedy_search",
            )
            recognizer = OfflineRecognizer(config = config)
            loadedDir = modelDir
            Log.i(TAG, "parakeet ready in ${System.currentTimeMillis() - started} ms")
        }
    }

    override fun transcribe(samples: FloatArray): String = synchronized(lock) {
        val current = recognizer ?: throw IllegalStateException("model not loaded")
        val started = System.currentTimeMillis()
        val stream = current.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            current.decode(stream)
            current.getResult(stream).text.also {
                Log.i(TAG, "parakeet: ${samples.size / 16} ms audio -> ${System.currentTimeMillis() - started} ms")
            }
        } finally {
            stream.release()
        }
    }

    fun isLoaded(modelDir: String): Boolean = loadedDir == modelDir

    /** Frees the ~700 MB the recognizer holds. The next dictation loads it again. */
    fun release() {
        synchronized(lock) { releaseLocked() }
    }

    /**
     * Unloads and runs [block] while no load or transcription can start - for deleting the
     * files, which a load in progress would otherwise read half-gone. Waits for one that runs.
     */
    fun <T> whileUnloaded(block: () -> T): T = synchronized(lock) {
        releaseLocked()
        block()
    }

    private fun releaseLocked() {
        recognizer?.release()
        if (recognizer != null) Log.i(TAG, "parakeet released")
        recognizer = null
        loadedDir = null
    }

    private companion object {
        const val TAG = "Amber"
        const val SAMPLE_RATE = 16_000
    }
}
