package app.amethyst.engine

import android.content.Context
import android.util.Log
import app.amethyst.core.engine.FileProblem
import app.amethyst.core.engine.download.DownloadAnswer
import app.amethyst.core.engine.download.DownloadTransport
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
import java.util.concurrent.atomic.AtomicInteger

/** Where the Parakeet model stands on this phone. */
sealed interface ParakeetState {
    data object Missing : ParakeetState
    data class Busy(val received: Long, val total: Long) : ParakeetState
    data class Ready(val sizeBytes: Long) : ParakeetState
    data class Failed(val problem: FileProblem) : ParakeetState
}

/**
 * Downloads Parakeet's four files, each checked against its pinned size and hash before it
 * takes its place. Nothing that arrived is fetched twice: complete files are skipped, and a
 * file cut off halfway is continued where it stopped ([ParakeetModel.download]) - after a lost
 * connection, a failure, or the end of the process. Only a cancel lets go of the unfinished
 * part.
 */
class ParakeetModelStore(
    context: Context,
    private val scope: CoroutineScope,
    private val runtime: SherpaRuntime,
    /** The app's HTTP client made for downloads ([app.amethyst.core.net.OkHttpTransport.downloadClient]); asked on the first download. */
    newHttp: () -> OkHttpClient,
) {

    /** noBackupFilesDir: hundreds of MB of model have no business in a cloud backup. */
    val dir = File(context.noBackupFilesDir, "models/${ParakeetModel.ID}")

    private val _state = MutableStateFlow(currentState())
    val state: StateFlow<ParakeetState> = _state

    private var job: Job? = null

    /** Counts downloads and cancels, so one that was cancelled reports nothing any more. */
    private val turn = AtomicInteger()

    /** Downloads and deletions take turns, so a delete never wipes a download started after it. */
    private val files = Mutex()

    @Volatile
    private var call: Call? = null

    private val http by lazy(newHttp)

    /** The server as a download sees it; "identity" so a byte position means the same on both sides. */
    private val server = DownloadTransport { url, from ->
        val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
            .apply { if (from > 0) header("Range", "bytes=$from-") }
            .build()
        val current = http.newCall(request).also { call = it }
        val response = current.execute()
        DownloadAnswer(
            code = response.code,
            etag = response.header("ETag"),
            lastModified = response.header("Last-Modified"),
            contentRange = response.header("Content-Range"),
            body = response.body?.byteStream(),
            onClose = { response.close() },
        )
    }

    fun isReady(): Boolean = ParakeetModel.isComplete(dir)

    /** The model's bytes on the phone, unfinished files included - for whether a failed try got anywhere. */
    fun bytesOnPhone(): Long = ParakeetModel.TOTAL_BYTES - ParakeetModel.missingBytes(dir)

    /** Busy from this call on, so whoever started it can wait for the state after it. */
    fun download() {
        if (job?.isActive == true) return
        val mine = turn.incrementAndGet()
        _state.value = ParakeetState.Busy(bytesOnPhone(), ParakeetModel.TOTAL_BYTES)
        job = scope.launch(Dispatchers.IO) { files.withLock { install(mine) } }
    }

    private fun install(mine: Int) {
        fun report(state: ParakeetState) {
            if (turn.get() == mine) _state.value = state
        }
        val free = dir.apply { mkdirs() }.usableSpace
        val needed = ParakeetModel.missingBytes(dir)
        if (free < needed + SPACE_MARGIN_BYTES) {
            report(ParakeetState.Failed(FileProblem.NotEnoughStorage(needed / 1_000_000, free / 1_000_000)))
            return
        }
        val missing = ParakeetModel.missing(dir)
        var done = ParakeetModel.TOTAL_BYTES - missing.sumOf { it.sizeBytes }
        for (file in missing) {
            val result = ParakeetModel.download(file, dir, server).run(
                onProgress = { received -> report(ParakeetState.Busy(done + received, ParakeetModel.TOTAL_BYTES)) },
                isCancelled = { turn.get() != mine },
            )
            call = null
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                if (error !is InterruptedException) {
                    Log.w(TAG, "parakeet download failed: ${error?.message}")
                    report(ParakeetState.Failed(FileProblem.of(error)))
                }
                return
            }
            done += file.sizeBytes
        }
        Log.i(TAG, "parakeet model installed")
        report(currentState())
    }

    /** Stops a download and lets go of its unfinished files; complete ones stay for another start. */
    fun cancel() {
        turn.incrementAndGet()
        call?.cancel()
        job?.cancel()
        _state.value = currentState()
        // After the download that was cut has ended, before one started afterwards begins.
        scope.launch(Dispatchers.IO) { files.withLock { ParakeetModel.discardKept(dir) } }
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
                if (job?.isActive != true) _state.value = ParakeetState.Missing
            }
        }
    }

    private fun currentState(): ParakeetState =
        if (ParakeetModel.isComplete(dir)) ParakeetState.Ready(ParakeetModel.TOTAL_BYTES) else ParakeetState.Missing

    private companion object {
        const val TAG = "Embersay"
        const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024
    }
}
