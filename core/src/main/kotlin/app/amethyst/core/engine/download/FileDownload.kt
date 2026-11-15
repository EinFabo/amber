package app.amethyst.core.engine.download

import app.amethyst.core.engine.FileProblem
import app.amethyst.core.engine.ModelFileException
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** A server's answer, as much of it as a download needs. Closing it ends the connection. */
class DownloadAnswer(
    val code: Int,
    val etag: String?,
    val lastModified: String?,
    /** "bytes 1000-1999/5000", on an answer that continues a file (206). */
    val contentRange: String?,
    val body: InputStream?,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        runCatching { body?.close() }
        onClose()
    }
}

/** The network behind a download: the app's HTTP client, or a fake in a test. */
fun interface DownloadTransport {

    /** Asks for [url] from byte [from] on (0: the whole file, without a Range). Throws [IOException] when no answer comes. */
    fun open(url: String, from: Long): DownloadAnswer
}

/**
 * One pinned file, fetched so that an interruption costs nothing: what arrived stays next to
 * the target as `.part`, and the next run asks the server for the rest only (HTTP Range).
 * It starts over where continuing would be wrong - the server ignores the Range (200 instead
 * of 206), or the file it has now is not the one the part came from (its ETag or
 * Last-Modified, kept beside the part, or its length) - and once more if a continued file
 * ends up with the wrong hash. Size and SHA-256 are checked on the whole file before it takes
 * the target's place, so the target is only ever a complete, checked file.
 */
class FileDownload(
    private val url: String,
    private val target: File,
    private val sizeBytes: Long,
    private val sha256: String,
    private val transport: DownloadTransport,
    private val freeBytes: () -> Long = { target.parentFile?.usableSpace ?: Long.MAX_VALUE },
    private val sink: (file: File, append: Boolean) -> OutputStream = { file, append -> FileOutputStream(file, append) },
) {

    private val part = partOf(target)
    private val validator = File(target.path + VALIDATOR_SUFFIX)

    /**
     * Fetches what is missing. [onProgress] gets the bytes of the file that are there, kept
     * ones included. A failure carries a [ModelFileException]; a cancel an
     * [InterruptedException], and leaves the part for the next run.
     */
    fun run(onProgress: (Long) -> Unit = {}, isCancelled: () -> Boolean = { false }): Result<Unit> = try {
        attempt(onProgress, isCancelled, startedOver = false)
    } catch (e: InterruptedException) {
        Result.failure(e)
    }

    private fun attempt(onProgress: (Long) -> Unit, isCancelled: () -> Boolean, startedOver: Boolean): Result<Unit> {
        target.parentFile?.mkdirs()
        fun startOver(otherwise: FileProblem): Result<Unit> {
            discard()
            return if (startedOver) failure(otherwise) else attempt(onProgress, isCancelled, startedOver = true)
        }

        var have = part.length()
        if (have > sizeBytes) {
            discard()
            have = 0
        }
        val digest = MessageDigest.getInstance("SHA-256")
        if (have > 0 && !hashKept(digest, have, isCancelled)) {
            discard()
            digest.reset()
            have = 0
        }
        val continued = have > 0
        if (continued) onProgress(have)

        if (have < sizeBytes) {
            val answer = try {
                transport.open(url, have)
            } catch (e: IOException) {
                if (isCancelled()) throw InterruptedException("cancelled")
                return failure(FileProblem.NoConnection, "No connection: ${e.message}", e)
            }
            answer.use {
                when {
                    answer.code == HTTP_PARTIAL && continued -> if (!continues(answer, have)) return startOver(FileProblem.ServerAnswered(answer.code))
                    // The whole file again: the server ignored the Range, or nothing was kept.
                    answer.code == HTTP_OK -> {
                        discard()
                        digest.reset()
                        have = 0
                        remember(answer)
                    }
                    answer.code == HTTP_RANGE_NOT_SATISFIABLE && continued -> return startOver(FileProblem.ServerAnswered(answer.code))
                    else -> return failure(FileProblem.ServerAnswered(answer.code))
                }
                val body = answer.body ?: return failure(FileProblem.EmptyAnswer)
                val out = try {
                    sink(part, have > 0)
                } catch (e: IOException) {
                    return failure(storage(sizeBytes - have), "Cannot write the model: ${e.message}", e)
                }
                out.use {
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (isCancelled()) throw InterruptedException("cancelled")
                        val read = try {
                            body.read(buffer)
                        } catch (e: IOException) {
                            if (isCancelled()) throw InterruptedException("cancelled")
                            return failure(FileProblem.Interrupted, "Transfer interrupted: ${e.message}", e)
                        }
                        if (read < 0) break
                        if (have + read > sizeBytes) {
                            // Not the pinned file, and it must not fill the phone first.
                            runCatching { out.close() }
                            discard()
                            return failure(FileProblem.Interrupted, "More than the expected $sizeBytes bytes.")
                        }
                        try {
                            out.write(buffer, 0, read)
                        } catch (e: IOException) {
                            return failure(storage(sizeBytes - have), "Cannot write the model: ${e.message}", e)
                        }
                        digest.update(buffer, 0, read)
                        have += read
                        onProgress(have)
                    }
                }
            }
        }

        if (have != sizeBytes) return failure(FileProblem.Incomplete(have, sizeBytes), "Download incomplete: $have of $sizeBytes bytes.")
        if (!digest.digest().toHex().equals(sha256, ignoreCase = true)) {
            // Kept bytes may be the damaged ones: once more from the start. A file that came in
            // one piece and is wrong will be wrong again.
            return if (continued) startOver(FileProblem.ChecksumMismatch) else failure(FileProblem.ChecksumMismatch).also { discard() }
        }
        if (!(part.renameTo(target) || (target.delete() && part.renameTo(target)))) return failure(FileProblem.CouldNotSave)
        validator.delete()
        return Result.success(Unit)
    }

    /** The kept bytes into [digest]; false if they cannot be read. */
    private fun hashKept(digest: MessageDigest, length: Long, isCancelled: () -> Boolean): Boolean = try {
        part.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var left = length
            while (left > 0) {
                if (isCancelled()) throw InterruptedException("cancelled")
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read < 0) return false
                digest.update(buffer, 0, read)
                left -= read
            }
        }
        true
    } catch (e: IOException) {
        false
    }

    /** Whether a 206 goes on exactly where the part ends, with the file the part came from. */
    private fun continues(answer: DownloadAnswer, from: Long): Boolean {
        val range = answer.contentRange?.let { RANGE.find(it.trim()) } ?: return false
        if (range.groupValues[1].toLongOrNull() != from) return false
        range.groupValues[2].toLongOrNull()?.let { total -> if (total != sizeBytes) return false }
        val kept = runCatching { validator.readLines() }.getOrDefault(emptyList())
        val etag = kept.getOrNull(0).orEmpty()
        val modified = kept.getOrNull(1).orEmpty()
        return when {
            etag.isNotEmpty() && answer.etag != null -> etag == answer.etag
            modified.isNotEmpty() && answer.lastModified != null -> modified == answer.lastModified
            else -> true
        }
    }

    /** What tells this file from a later one of the same address, kept beside the part. */
    private fun remember(answer: DownloadAnswer) {
        runCatching { validator.writeText("${answer.etag.orEmpty()}\n${answer.lastModified.orEmpty()}\n") }
    }

    private fun discard() {
        part.delete()
        validator.delete()
    }

    /** A write that failed: the phone is full, or the file cannot be written at all. */
    private fun storage(missing: Long): FileProblem {
        val free = freeBytes()
        return if (free < missing) FileProblem.NotEnoughStorage(mb(missing), free / 1_000_000) else FileProblem.CouldNotSave
    }

    private fun failure(problem: FileProblem, detail: String = problem.toString(), cause: Throwable? = null): Result<Unit> =
        Result.failure(ModelFileException(problem, detail, cause))

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val PART_SUFFIX = ".part"
        private const val VALIDATOR_SUFFIX = ".part.from"
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL = 206
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val BUFFER_SIZE = 64 * 1024
        private val RANGE = Regex("""^bytes\s+(\d+)-\d+/(\d+|\*)$""")

        /** Where the bytes of [target] wait until the file is complete. */
        fun partOf(target: File): File = File(target.path + PART_SUFFIX)

        /** How much of [target] still has to come: nothing once it is in place at [sizeBytes]. */
        fun missingBytes(target: File, sizeBytes: Long): Long =
            if (target.length() == sizeBytes) 0 else sizeBytes - partOf(target).length().coerceIn(0, sizeBytes)

        /** Lets go of what was kept for [target] - a download nobody wants any more. */
        fun discardKept(target: File) {
            partOf(target).delete()
            File(target.path + VALIDATOR_SUFFIX).delete()
        }

        private fun mb(bytes: Long): Long = (bytes + 999_999) / 1_000_000
    }
}
