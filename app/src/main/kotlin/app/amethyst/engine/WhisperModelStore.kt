package app.amethyst.engine

import app.amethyst.R
import app.amethyst.core.engine.FileProblem
import app.amethyst.core.engine.ModelFileException
import app.amethyst.core.engine.ModelLoadGuard
import app.amethyst.core.engine.ModelLoadMarks
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import app.amethyst.core.engine.whisper.ModelInstall
import app.amethyst.core.engine.whisper.ModelSlots
import app.amethyst.core.engine.whisper.ModelSlots.SlotState
import app.amethyst.core.engine.whisper.WhisperModelFile
import app.amethyst.core.engine.whisper.WhisperModelInfo
import app.amethyst.core.engine.whisper.WhisperModelSpec
import app.amethyst.core.engine.whisper.WhisperModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import app.amethyst.core.engine.download.DownloadAnswer
import app.amethyst.core.engine.download.DownloadTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * The Whisper models on this device: one file per catalog entry plus one slot for a file the
 * user picked, each with its own state, so several can be downloaded and switched between.
 *
 * A download cut off halfway is continued where it stopped ([ModelInstall.download]) - after a
 * lost connection, a failure, or the end of the process. Only a cancel or a delete lets go of
 * the unfinished part.
 *
 * A model under suspicion ([ModelLoadGuard]) is not ready: while its file is checked
 * ([recheck]), and once found damaged, which its slot shows as [FileProblem.Damaged] until it is
 * downloaded or picked anew.
 */
class WhisperModelStore(
    private val context: Context,
    private val scope: CoroutineScope,
    private val runtime: NativeWhisperRuntime,
    private val marks: ModelLoadMarks,
    /** The app's HTTP client made for downloads ([app.amethyst.core.net.OkHttpTransport.downloadClient]); asked on the first download. */
    newHttp: () -> OkHttpClient,
) {

    /** noBackupFilesDir: hundreds of MB of model have no business in a cloud backup. */
    private val directory = File(context.noBackupFilesDir, "models")

    private val room = ModelRoom(context, directory)

    private val slots: ModelSlots

    val states: StateFlow<Map<String, SlotState>>

    /** The HTTP call of each running download, so a cancel can cut the connection itself. */
    private val calls = ConcurrentHashMap<String, Call>()

    private val http by lazy(newHttp)

    /**
     * Per slot, downloads, copies and the discard of a cancelled download's part take turns:
     * they share the slot's `.part`, and a download cut by a cancel only stops at its next
     * chunk. One lock per slot, so different models still download and copy side by side.
     */
    private val files: Map<String, Mutex> = slotIds().associateWith { Mutex() }

    private fun filesOf(id: String): Mutex = files.getValue(id)

    /** The server as a download sees it; "identity" so a byte position means the same on both sides. */
    private fun server(id: String) = DownloadTransport { url, from ->
        val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
            .apply { if (from > 0) header("Range", "bytes=$from-") }
            .build()
        val call = http.newCall(request).also { calls[id] = it }
        val response = call.execute()
        DownloadAnswer(
            code = response.code,
            etag = response.header("ETag"),
            lastModified = response.header("Last-Modified"),
            contentRange = response.header("Content-Range"),
            body = response.body?.byteStream(),
            onClose = { response.close() },
        )
    }

    init {
        migrateSingleSlot()
        // Left over when the app died between renaming a deleted model and freeing it.
        directory.listFiles { file -> file.name.endsWith(DELETING) }?.forEach { it.delete() }
        slots = ModelSlots(
            scope = scope,
            dispatcher = Dispatchers.IO,
            ids = slotIds(),
            inspect = ::readState,
        )
        states = slots.states
    }

    fun fileFor(id: String): File = File(directory, "$id.bin")

    fun state(id: String): SlotState = slots.state(id)

    fun isReady(id: String): Boolean = state(id) is SlotState.Ready && health(id) == null

    /** Null while nothing points at [id]'s file; else checking or damaged ([ModelLoadGuard]). */
    fun health(id: String): ModelLoadGuard.Health? = marks.health(id)

    /** Imported files that matched no catalog entry are treated as stock Whisper. */
    fun shortContextFor(id: String): Boolean = WhisperModels.byId(id)?.shortContext ?: false

    /** The model's bytes on the phone for [id], an unfinished part included - for whether a failed try got anywhere. */
    fun bytesOnPhone(id: String): Long {
        val spec = WhisperModels.byId(id) ?: return 0
        return spec.sizeBytes - ModelInstall.missingBytes(spec, fileFor(id))
    }

    fun download(spec: WhisperModelSpec) = slots.run(spec.id, context.getString(R.string.slot_downloading)) { report, isCancelled ->
        filesOf(spec.id).withLock {
            val target = fileFor(spec.id)
            val free = room.available()
            val needed = ModelInstall.missingBytes(spec, target)
            if (free < needed + SPACE_MARGIN_BYTES || !room.claim(needed + SPACE_MARGIN_BYTES)) {
                return@withLock Result.failure(
                    ModelFileException(FileProblem.NotEnoughStorage(needed / 1_000_000, free / 1_000_000)),
                )
            }
            try {
                ModelInstall.download(spec, target, server(spec.id), { received -> report(received, spec.sizeBytes) }, isCancelled)
                    .map { info ->
                        Log.i(TAG, "whisper model ${spec.id} installed: ${info.sizeName}, multilingual=${info.multilingual}")
                        // A file fetched whole and checked: a damaged one is fine again.
                        marks.clear(spec.id)
                        info to target.length()
                    }
                    .onFailure { error -> if (error !is InterruptedException) Log.w(TAG, "whisper download ${spec.id} failed: ${error.message}") }
            } finally {
                calls.remove(spec.id)
            }
        }
    }

    /**
     * A picked file goes into the slot of the catalog model it matches by size - so an
     * imported FUTO file keeps its short context and gets its hash checked - or into the
     * slot for foreign files, checked from end to end before native code sees it
     * ([ModelInstall]).
     */
    fun import(uri: Uri) {
        val size = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }
        }.getOrNull()
        val known = WhisperModels.matchingSize(size)
        val id = known?.id ?: WhisperModels.IMPORTED_ID

        slots.run(id, context.getString(R.string.slot_copying)) { report, isCancelled ->
            // Said at once rather than after gigabytes copied (the copy stops there anyway).
            if (known == null && size != null && size > WhisperModelFile.MAX_IMPORT_BYTES) {
                return@run Result.failure(ModelFileException(FileProblem.TooLarge(WhisperModelFile.MAX_IMPORT_BYTES / 1_000_000)))
            }
            filesOf(id).withLock {
                val input = context.contentResolver.openInputStream(uri)
                    ?: return@withLock Result.failure(ModelFileException(FileProblem.CannotOpen))
                input.use { install(it, id, known, size, report, isCancelled) }
            }
        }
    }

    /** Stops a download or copy and lets go of its unfinished part; another start begins anew. */
    fun cancel(id: String) {
        // Cut the connection first: a download blocked in read() would otherwise only notice
        // the cancel when the next chunk arrives.
        calls.remove(id)?.cancel()
        slots.cancel(id)
        // After the download that was cut has ended, before one started afterwards begins.
        scope.launch(Dispatchers.IO) { filesOf(id).withLock { ModelInstall.discardKept(fileFor(id)) } }
    }

    fun delete(id: String) {
        val file = fileFor(id)
        // Renamed at once (a running transcription has the model in memory already, and a
        // download started right after is never wiped by a late delete); the bytes are freed
        // off the main thread, and the memory only if this model is the one loaded.
        val doomed = File(directory, "$id$DELETING")
        file.renameTo(doomed)
        cancel(id)
        slots.set(id, SlotState.Missing)
        scope.launch(Dispatchers.IO) {
            doomed.delete()
            marks.clear(id)
        }
        scope.launch(runtime.dispatcher) { if (runtime.isLoaded(file.path)) runtime.release() }
    }

    private fun install(
        input: InputStream,
        id: String,
        expected: WhisperModelSpec?,
        total: Long?,
        report: (Long, Long?) -> Unit,
        isCancelled: () -> Boolean,
    ): Result<Pair<WhisperModelInfo, Long>> =
        ModelInstall.install(
            input = input,
            target = fileFor(id),
            expected = expected,
            onProgress = { received -> report(received, total) },
            isCancelled = isCancelled,
        ).map { info ->
            Log.i(TAG, "whisper model $id installed: ${info.sizeName}, multilingual=${info.multilingual}")
            // A file copied anew and checked: a damaged one is fine again.
            marks.clear(id)
            info to fileFor(id).length()
        }

    /**
     * Checks the file of a suspect model ([ModelLoadGuard]) against the catalog's hash - off the
     * main thread, after a download or copy that runs. A catalog file that does not match is
     * deleted, so "Download again" fetches it whole; a picked file has no hash and stays damaged
     * until it is picked anew. The health it ends in, or the one it had when it was not being
     * checked.
     */
    suspend fun recheck(id: String): ModelLoadGuard.Health? = withContext(Dispatchers.IO) {
        val lock = files[id] ?: run {
            // Not a slot of this build (a model since dropped from the catalog): nothing to load.
            marks.clear(id)
            return@withContext null
        }
        lock.withLock {
            val before = health(id)
            if (before != ModelLoadGuard.Health.CHECKING) return@withLock before
            val file = fileFor(id)
            val spec = WhisperModels.byId(id)
            val health = if (!file.exists()) null else ModelLoadGuard.afterCheck(spec?.let { ModelLoadGuard.matches(file, it.sizeBytes, it.sha256) })
            if (health != null && spec != null) {
                Log.w(TAG, "whisper model $id damaged: its file does not match the catalog")
                withContext(runtime.dispatcher) { if (runtime.isLoaded(file.path)) runtime.release() }
                file.delete()
            }
            marks.checked(id, health)
            if (state(id) !is SlotState.Busy) slots.set(id, readState(id))
            health
        }
    }

    private fun slotIds(): List<String> = WhisperModels.all.map { it.id } + WhisperModels.IMPORTED_ID

    private fun readState(id: String): SlotState {
        if (health(id) == ModelLoadGuard.Health.DAMAGED) return SlotState.Failed(FileProblem.Damaged)
        return ModelInstall.inspect(fileFor(id)).fold(
            onSuccess = { info -> SlotState.Ready(info, fileFor(id).length()) },
            onFailure = { SlotState.Missing },
        )
    }

    /** 0.2.0 kept a single model as models/whisper.bin; move it into its slot. */
    private fun migrateSingleSlot() {
        val legacy = File(directory, "whisper.bin")
        if (!legacy.isFile) return
        val id = WhisperModels.matchingSize(legacy.length())?.id ?: WhisperModels.IMPORTED_ID
        val target = fileFor(id)
        if (!target.exists() && legacy.renameTo(target)) {
            Log.i(TAG, "moved the 0.2.0 model into slot $id")
        } else {
            legacy.delete()
        }
    }

    private companion object {
        const val TAG = "Embersay"
        const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024
        const val DELETING = ".deleting"
    }
}
