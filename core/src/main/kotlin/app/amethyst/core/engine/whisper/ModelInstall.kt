package app.amethyst.core.engine.whisper

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

    /** Streams [input] to [output], hashing on the way. [onProgress] gets bytes so far. */
    fun copyWithDigest(
        input: InputStream,
        output: OutputStream,
        onProgress: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Copied {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            if (isCancelled()) throw InterruptedException("model copy cancelled")
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            total += read
            onProgress(total)
        }
        output.flush()
        return Copied(total, digest.digest().toHex())
    }

    /**
     * Writes into a temporary sibling of [target], checks it, then renames it over [target].
     * [expected] pins size and hash for a known download; an imported file has no pinned
     * hash and is checked by its header alone.
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
                copyWithDigest(input, out, onProgress, isCancelled)
            }
            verify(partial, copied, expected).onSuccess {
                if (!partial.renameTo(target)) {
                    target.delete()
                    if (!partial.renameTo(target)) {
                        return Result.failure(IllegalStateException("The model could not be saved."))
                    }
                }
            }
        } catch (e: InterruptedException) {
            Result.failure(e)
        } catch (e: java.io.IOException) {
            Result.failure(IllegalStateException("Transfer interrupted: ${e.message}", e))
        } finally {
            partial.delete()
        }
    }

    /** Reads the header of an installed model, or explains why it is unusable. */
    fun inspect(file: File): Result<WhisperModelInfo> {
        if (!file.isFile) return Result.failure(IllegalStateException("No model installed."))
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
                    IllegalStateException(
                        "Download incomplete: ${copied.bytes} of ${expected.sizeBytes} bytes.",
                    ),
                )
            }
            if (!copied.sha256.equals(expected.sha256, ignoreCase = true)) {
                return Result.failure(
                    IllegalStateException(
                        "The checksum does not match - the file is damaged or was altered.",
                    ),
                )
            }
        }
        return inspect(file)
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
