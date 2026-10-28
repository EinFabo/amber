package app.amethyst.engine

import android.content.Context
import android.util.Log
import app.amethyst.core.engine.sherpa.ParakeetModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Where the Parakeet model stands on this phone. */
sealed interface ParakeetState {
    data object Missing : ParakeetState
    data class Busy(val received: Long, val total: Long) : ParakeetState
    data class Ready(val sizeBytes: Long) : ParakeetState
    data class Failed(val message: String) : ParakeetState
}

/**
 * Downloads Parakeet's four files, each checked against its pinned size and hash before it
 * takes its place. Files already complete are skipped, so an interrupted download resumes
 * file by file instead of starting the 670 MB over.
 */
class ParakeetModelStore(
    context: Context,
    private val scope: CoroutineScope,
    private val runtime: SherpaRuntime,
) {

    /** noBackupFilesDir: hundreds of MB of model have no business in a cloud backup. */
    val dir = File(context.noBackupFilesDir, "models/${ParakeetModel.ID}")

    private val _state = MutableStateFlow(currentState())
    val state: StateFlow<ParakeetState> = _state

    private var job: Job? = null

    /** Downloads and deletions take turns, so a delete never wipes a download started after it. */
    private val files = Mutex()

    @Volatile
    private var call: Call? = null

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun isReady(): Boolean = ParakeetModel.isComplete(dir)

    fun download() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { files.withLock { install() } }
    }

    private fun install() {
        val free = dir.apply { mkdirs() }.usableSpace
        val missing = ParakeetModel.FILES.filter { File(dir, it.name).length() != it.sizeBytes }
        val needed = missing.sumOf { it.sizeBytes }
        if (free < needed + SPACE_MARGIN_BYTES) {
            _state.value = ParakeetState.Failed("Not enough storage: ${needed / 1_000_000} MB needed, ${free / 1_000_000} MB free.")
            return
        }
        var done = ParakeetModel.TOTAL_BYTES - needed
        for (file in missing) {
            _state.value = ParakeetState.Busy(done, ParakeetModel.TOTAL_BYTES)
            val result = fetch(file) { received ->
                _state.value = ParakeetState.Busy(done + received, ParakeetModel.TOTAL_BYTES)
            }
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                _state.value = if (error is InterruptedException) currentState() else ParakeetState.Failed(error?.message ?: "Download failed.")
                return
            }
            done += file.sizeBytes
        }
        Log.i(TAG, "parakeet model installed")
        _state.value = currentState()
    }

    fun cancel() {
        call?.cancel()
        job?.cancel()
        _state.value = currentState()
    }

    /**
     * Off the main thread (670 MB take a moment to free), after a load or transcription that
     * runs has ended, and before a download started afterwards begins.
     */
    fun delete() {
        cancel()
        _state.value = ParakeetState.Missing
        scope.launch(Dispatchers.IO) {
            files.withLock {
                runtime.whileUnloaded { dir.deleteRecursively() }
                // A cancelled download that only just ended may have shown its files again.
                _state.value = ParakeetState.Missing
            }
        }
    }

    private fun fetch(file: ParakeetModel.FileSpec, onProgress: (Long) -> Unit): Result<Unit> {
        val request = Request.Builder().url(ParakeetModel.url(file)).build()
        val current = http.newCall(request).also { call = it }
        return try {
            current.execute().use { response ->
                if (!response.isSuccessful) return Result.failure(IllegalStateException("The server answered ${response.code}."))
                val body = response.body ?: return Result.failure(IllegalStateException("Empty answer from the server."))
                ParakeetModel.install(body.byteStream(), dir, file, onProgress, isCancelled = { current.isCanceled() })
            }
        } catch (e: IOException) {
            if (current.isCanceled()) Result.failure(InterruptedException("cancelled"))
            else Result.failure(IllegalStateException("No connection: ${e.message}", e))
        } finally {
            call = null
        }
    }

    private fun currentState(): ParakeetState =
        if (ParakeetModel.isComplete(dir)) ParakeetState.Ready(ParakeetModel.TOTAL_BYTES) else ParakeetState.Missing

    private companion object {
        const val TAG = "Amber"
        const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024
    }
}
