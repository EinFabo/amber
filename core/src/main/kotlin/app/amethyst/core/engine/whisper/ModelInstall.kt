package app.amethyst.core.engine.whisper

import app.amethyst.core.engine.FileProblem
import app.amethyst.core.engine.ModelFileException
import app.amethyst.core.engine.download.DownloadTransport
import app.amethyst.core.engine.download.FileDownload
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Copies a model into place - from a download or from a file the user picked - so that the
 * target path only ever holds a complete, checked file. A half-written model handed to native
 * code would crash the process, not fail politely.
 */
object ModelInstall {

    data class Copied(val bytes: Long, val sha256: String)

    /**
     * Streams [input] to [output], hashing on the way. [onProgress] gets bytes so far. Stops as
     * soon as more than [maxBytes] arrive: a pinned download has an exact size, and a server
     * sending more is not going to send the right file - it must not fill the phone first.
     */
    fun copyWithDigest(
        input: InputStream,
        output: OutputStream,
        onProgress: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        maxBytes: Long = Long.MAX_VALUE,
    ): Copied {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            if (isCancelled()) throw InterruptedException("model copy cancelled")
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw TooManyBytes(maxBytes)
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            onProgress(total)
        }
        output.flush()
        return Copied(total, digest.digest().toHex())
    }

    /** More bytes arrived than [maxBytes] - the copy stopped there. */
    class TooManyBytes(val maxBytes: Long) : java.io.IOException("more than the expected $maxBytes bytes")

    /**
     * Writes into a temporary sibling of [target], checks it, then renames it over [target].
     * [expected] pins size and hash for a known download; an imported file has no pinned
     * hash, is held to [WhisperModelFile.MAX_IMPORT_BYTES] and checked from end to end
     * ([WhisperModelFile.check]).
     */
    fun install(
        input: InputStream,
        target: File,
        expected: WhisperModelSpec?,
        onProgress: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Result<WhisperModelInfo> {
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")
        return try {
            val copied = partial.outputStream().use { out ->
                copyWithDigest(input, out, onProgress, isCancelled, maxBytes = expected?.sizeBytes ?: WhisperModelFile.MAX_IMPORT_BYTES)
            }
            verify(partial, copied, expected).onSuccess {
                if (!partial.renameTo(target)) {
                    target.delete()
                    if (!partial.renameTo(target)) {
                        return Result.failure(ModelFileException(FileProblem.CouldNotSave))
                    }
                }
            }
        } catch (e: InterruptedException) {
            Result.failure(e)
        } catch (e: TooManyBytes) {
            if (expected == null) {
                Result.failure(ModelFileException(FileProblem.TooLarge(e.maxBytes / 1_000_000), "Larger than ${e.maxBytes} bytes.", e))
            } else {
                Result.failure(ModelFileException(FileProblem.Interrupted, "Transfer interrupted: ${e.message}", e))
            }
        } catch (e: java.io.IOException) {
            Result.failure(ModelFileException(FileProblem.Interrupted, "Transfer interrupted: ${e.message}", e))
        } finally {
            // The same name a download keeps its part under: whatever was kept for this slot
            // goes, its origin with it - the slot holds the copied file now, or nothing.
            partial.delete()
            FileDownload.discardKept(target)
        }
    }

    /**
     * A catalog model fetched so that a break costs nothing ([FileDownload]): what arrived stays
     * beside [target] as its `.part`, and the next try - after a lost connection, a failure or
     * the end of the process - asks the server for the rest only. A part may also be one an
     * older version left behind (it wrote the same name, without the origin FileDownload keeps
     * beside it): it is hashed along with the rest, and the whole file is held to the pinned
     * size and hash before it takes [target]'s place, so nothing kept is trusted unchecked.
     * Then the header is read, as for any installed model.
     */
    fun download(
        spec: WhisperModelSpec,
        target: File,
        transport: DownloadTransport,
        onProgress: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Result<WhisperModelInfo> =
        FileDownload(spec.url, target, spec.sizeBytes, spec.sha256, transport).run(onProgress, isCancelled).fold(
            // Pinned by size and hash, so the header is the catalog's; one that still does not
            // read must not stay where the loader would find it.
            onSuccess = { inspect(target).onFailure { target.delete() } },
            onFailure = { Result.failure(it) },
        )

    /** The bytes of [spec] still to fetch into [target]: all of them, less a part kept from an earlier try. */
    fun missingBytes(spec: WhisperModelSpec, target: File): Long = FileDownload.missingBytes(target, spec.sizeBytes)

    /** Lets go of a download's part for [target] - a cancel, or a file put there another way. */
    fun discardKept(target: File) = FileDownload.discardKept(target)

    /** Reads the header of an installed model, or explains why it is unusable. */
    fun inspect(file: File): Result<WhisperModelInfo> {
        if (!file.isFile) return Result.failure(ModelFileException(FileProblem.NoModelInstalled))
        val header = ByteArray(WhisperModelFile.HEADER_BYTES)
        val read = file.inputStream().use { it.readNBytesCompat(header) }
        return WhisperModelFile.inspect(header.copyOf(read))
    }

    private fun verify(
        file: File,
        copied: Copied,
        expected: WhisperModelSpec?,
    ): Result<WhisperModelInfo> {
        if (expected != null) {
            if (copied.bytes != expected.sizeBytes) {
                return Result.failure(
                    ModelFileException(
                        FileProblem.Incomplete(copied.bytes, expected.sizeBytes),
                        "Download incomplete: ${copied.bytes} of ${expected.sizeBytes} bytes.",
                    ),
                )
            }
            if (!copied.sha256.equals(expected.sha256, ignoreCase = true)) {
                return Result.failure(
                    ModelFileException(FileProblem.ChecksumMismatch),
                )
            }
            // Pinned by size and hash: the very file the catalog names.
            return inspect(file)
        }
        return checkWhole(file)
    }

    /** [file] from end to end ([WhisperModelFile.check]), for a model nothing pins. */
    private fun checkWhole(file: File): Result<WhisperModelInfo> {
        if (!file.isFile) return Result.failure(ModelFileException(FileProblem.NoModelInstalled))
        return file.inputStream().buffered(BUFFER_SIZE).use { WhisperModelFile.check(it, file.length()) }
    }

    private fun InputStream.readNBytesCompat(buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private const val BUFFER_SIZE = 64 * 1024
}
