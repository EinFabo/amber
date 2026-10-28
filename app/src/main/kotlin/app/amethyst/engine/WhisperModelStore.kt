package app.amethyst.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import app.amethyst.core.engine.whisper.ModelInstall
import app.amethyst.core.engine.whisper.ModelSlots
import app.amethyst.core.engine.whisper.ModelSlots.SlotState
import app.amethyst.core.engine.whisper.WhisperModelInfo
import app.amethyst.core.engine.whisper.WhisperModelSpec
import app.amethyst.core.engine.whisper.WhisperModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The Whisper models on this device: one file per catalog entry plus one slot for a file the
 * user picked, each with its own state, so several can be downloaded and switched between.
 */
class WhisperModelStore(
    private val context: Context,
    private val scope: CoroutineScope,
    private val runtime: NativeWhisperRuntime,
) {

    /** noBackupFilesDir: hundreds of MB of model have no business in a cloud backup. */
    private val directory = File(context.noBackupFilesDir, "models")

    private val slots: ModelSlots

    val states: StateFlow<Map<String, SlotState>>

    /** The HTTP call of each running download, so a cancel can cut the connection itself. */
    private val calls = ConcurrentHashMap<String, Call>()

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    init {
        migrateSingleSlot()
        slots = ModelSlots(
            scope = scope,
            dispatcher = Dispatchers.IO,
            ids = WhisperModels.all.map { it.id } + WhisperModels.IMPORTED_ID,
            inspect = ::readState,
        )
        states = slots.states
    }

    fun fileFor(id: String): File = File(directory, "$id.bin")

    fun state(id: String): SlotState = slots.state(id)

    fun isReady(id: String): Boolean = state(id) is SlotState.Ready

    /** Imported files that matched no catalog entry are treated as stock Whisper. */
    fun shortContextFor(id: String): Boolean = WhisperModels.byId(id)?.shortContext ?: false

    fun download(spec: WhisperModelSpec) = slots.run(spec.id, "Downloading") { report, isCancelled ->
        val free = directory.apply { mkdirs() }.usableSpace
        if (free < spec.sizeBytes + SPACE_MARGIN_BYTES) {
            return@run Result.failure(
                IllegalStateException(
                    "Not enough storage: ${spec.sizeBytes / 1_000_000} MB needed, " +
                        "${free / 1_000_000} MB free.",
                ),
            )
        }
        val call = http.newCall(Request.Builder().url(spec.url).build())
        calls[spec.id] = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    return@run Result.failure(IllegalStateException("The server answered ${response.code}."))
                }
                val body = response.body
                    ?: return@run Result.failure(IllegalStateException("Empty answer from the server."))
                install(body.byteStream(), spec.id, spec, spec.sizeBytes, report, isCancelled)
            }
        } catch (e: IOException) {
            if (isCancelled()) Result.failure(InterruptedException("cancelled"))
            else Result.failure(IllegalStateException("No connection: ${e.message}", e))
        } finally {
            calls.remove(spec.id)
        }
    }

    /**
     * A picked file goes into the slot of the catalog model it matches by size - so an
     * imported FUTO file keeps its short context and gets its hash checked - or into the
     * slot for foreign files.
     */
    fun import(uri: Uri) {
        val size = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }
        }.getOrNull()
        val known = WhisperModels.matchingSize(size)
        val id = known?.id ?: WhisperModels.IMPORTED_ID

        slots.run(id, "Copying") { report, isCancelled ->
            val input = context.contentResolver.openInputStream(uri)
                ?: return@run Result.failure(IllegalStateException("The file cannot be opened."))
            input.use { install(it, id, known, size, report, isCancelled) }
        }
    }

    fun cancel(id: String) {
        // Cut the connection first: a download blocked in read() would otherwise only notice
        // the cancel when the next chunk arrives.
        calls.remove(id)?.cancel()
        slots.cancel(id)
    }

    fun delete(id: String) {
        cancel(id)
        slots.set(id, SlotState.Missing)
        // The runtime may hold exactly this file; it reloads whatever is needed next. On its
        // own thread, since release waits for a transcription that may still be running.
        scope.launch(runtime.dispatcher) {
            runtime.release()
            fileFor(id).delete()
            slots.set(id, SlotState.Missing)
        }
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
            info to fileFor(id).length()
        }

    private fun readState(id: String): SlotState =
        ModelInstall.inspect(fileFor(id)).fold(
            onSuccess = { info -> SlotState.Ready(info, fileFor(id).length()) },
            onFailure = { SlotState.Missing },
        )

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
        const val TAG = "Amber"
        const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024
    }
}
