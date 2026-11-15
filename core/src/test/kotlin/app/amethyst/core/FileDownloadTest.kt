package app.amethyst.core

import app.amethyst.core.engine.FileProblem
import app.amethyst.core.engine.ModelFileException
import app.amethyst.core.engine.download.DownloadAnswer
import app.amethyst.core.engine.download.DownloadTransport
import app.amethyst.core.engine.download.FileDownload
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A model file's download against a faked server: continued after a cut, started over where it must be. */
class FileDownloadTest {

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val content = ByteArray(300_000) { (it * 31 + it / 7).toByte() }
    private val dir = createTempDirectory().toFile()
    private val target = File(dir, "model.bin")
    private val part = File(dir, "model.bin.part")

    /** Sends [limit] bytes at most, then the connection breaks. */
    private class Cut(bytes: ByteArray, private var limit: Int) : InputStream() {
        private val inner = ByteArrayInputStream(bytes)
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (limit <= 0) throw IOException("connection reset")
            val read = inner.read(b, off, minOf(len, limit, 10_000))
            if (read > 0) limit -= read
            return read
        }
    }

    /** A server holding [file] under [etag]; knobs for what real ones do wrong. */
    private inner class Server(var file: ByteArray = content, var etag: String? = "\"v1\"") : DownloadTransport {
        val asked = mutableListOf<Long>()
        var ranges = true
        var cutAfter: Int? = null
        var code: Int? = null
        var down = false

        override fun open(url: String, from: Long): DownloadAnswer {
            asked += from
            if (down) throw IOException("no route to host")
            code?.let { return DownloadAnswer(it, null, null, null, null) }
            val partial = from > 0 && ranges
            if (partial && from >= file.size) return DownloadAnswer(416, etag, null, "bytes */${file.size}", null)
            val bytes = if (partial) file.copyOfRange(from.toInt(), file.size) else file
            val body = cutAfter?.let { Cut(bytes, it) } ?: ByteArrayInputStream(bytes)
            return if (partial) {
                DownloadAnswer(206, etag, null, "bytes $from-${file.size - 1}/${file.size}", body)
            } else {
                DownloadAnswer(200, etag, null, null, body)
            }
        }
    }

    private fun download(server: DownloadTransport, of: ByteArray = content, free: Long = Long.MAX_VALUE, sink: ((File, Boolean) -> OutputStream)? = null) =
        if (sink == null) {
            FileDownload("https://models.example/model.bin", target, of.size.toLong(), sha(of), server, freeBytes = { free })
        } else {
            FileDownload("https://models.example/model.bin", target, of.size.toLong(), sha(of), server, freeBytes = { free }, sink = sink)
        }

    private fun problemOf(result: Result<Unit>): FileProblem = assertIs<ModelFileException>(result.exceptionOrNull()).problem

    private fun leftovers(): List<String> = dir.list().orEmpty().sorted()

    @Test
    fun `a whole file lands checked, with nothing left beside it`() {
        val server = Server()
        val progress = mutableListOf<Long>()
        assertTrue(download(server).run(onProgress = { progress += it }).isSuccess)
        assertContentEquals(content, target.readBytes())
        assertEquals(listOf(0L), server.asked)
        assertEquals(content.size.toLong(), progress.last())
        assertEquals(listOf("model.bin"), leftovers())
    }

    @Test
    fun `after a cut the rest is asked for and the file is whole`() {
        val server = Server().apply { cutAfter = 120_000 }
        assertEquals(FileProblem.Interrupted, problemOf(download(server).run()))
        assertEquals(120_000L, part.length())
        assertFalse(target.exists())

        server.cutAfter = null
        val progress = mutableListOf<Long>()
        assertTrue(download(server).run(onProgress = { progress += it }).isSuccess)
        assertEquals(listOf(0L, 120_000L), server.asked)
        assertEquals(120_000L, progress.first(), "what was kept counts from the start")
        assertContentEquals(content, target.readBytes())
        assertEquals(listOf("model.bin"), leftovers())
    }

    @Test
    fun `cut again and again it still gets there, each time from where it stopped`() {
        val server = Server().apply { cutAfter = 70_000 }
        var tries = 0
        while (download(server).run().isFailure) check(++tries < 10)
        assertEquals(listOf(0L, 70_000L, 140_000L, 210_000L, 280_000L), server.asked)
        assertContentEquals(content, target.readBytes())
    }

    @Test
    fun `no connection keeps what is there`() {
        val server = Server().apply { cutAfter = 50_000 }
        download(server).run()
        server.down = true
        assertEquals(FileProblem.NoConnection, problemOf(download(server).run()))
        assertEquals(50_000L, part.length())
    }

    @Test
    fun `a server that ignores the range sends everything and the file is still right`() {
        val server = Server().apply { cutAfter = 120_000 }
        download(server).run()
        server.cutAfter = null
        server.ranges = false
        val progress = mutableListOf<Long>()
        assertTrue(download(server).run(onProgress = { progress += it }).isSuccess)
        assertEquals(listOf(0L, 120_000L), server.asked)
        assertTrue(progress.any { it < 120_000 }, "the count starts over with the file")
        assertContentEquals(content, target.readBytes())
    }

    @Test
    fun `a file that changed on the server is fetched from the start`() {
        val server = Server().apply { cutAfter = 120_000 }
        download(server).run()

        val newer = ByteArray(content.size) { (it * 13 + 5).toByte() }
        server.file = newer
        server.etag = "\"v2\""
        server.cutAfter = null
        assertTrue(download(server, of = newer).run().isSuccess)
        assertEquals(listOf(0L, 120_000L, 0L), server.asked)
        assertContentEquals(newer, target.readBytes())
    }

    @Test
    fun `a file of another length is not continued`() {
        val server = Server(etag = null).apply { cutAfter = 120_000 }
        download(server).run()
        val longer = content + ByteArray(10)
        server.file = longer
        server.cutAfter = null
        // Still pinned to the old one: it starts over, and what comes is too much.
        assertTrue(download(server).run().isFailure)
        assertEquals(listOf(0L, 120_000L, 0L), server.asked)
        assertFalse(target.exists())
        assertFalse(part.exists())
    }

    @Test
    fun `kept bytes that turn out damaged are thrown away and the file fetched once more`() {
        val server = Server().apply { cutAfter = 120_000 }
        download(server).run()
        part.writeBytes(part.readBytes().also { it[1_000] = (it[1_000] + 1).toByte() })
        server.cutAfter = null
        assertTrue(download(server).run().isSuccess)
        assertEquals(listOf(0L, 120_000L, 0L), server.asked)
        assertContentEquals(content, target.readBytes())
    }

    @Test
    fun `a file that is wrong in one piece fails without a second fetch and leaves nothing`() {
        val server = Server()
        val pinnedToAnother = ByteArray(content.size) { 1 }
        assertEquals(FileProblem.ChecksumMismatch, problemOf(download(server, of = pinnedToAnother).run()))
        assertEquals(listOf(0L), server.asked)
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `a wrong file is tried twice at most`() {
        // Kept bytes and a server whose file never matches the pinned hash.
        val server = Server().apply { cutAfter = 120_000 }
        val pinnedToAnother = ByteArray(content.size) { 1 }
        download(server, of = pinnedToAnother).run()
        server.cutAfter = null
        assertEquals(FileProblem.ChecksumMismatch, problemOf(download(server, of = pinnedToAnother).run()))
        assertEquals(listOf(0L, 120_000L, 0L), server.asked)
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `a part that is complete already needs no request`() {
        part.writeBytes(content)
        val server = Server()
        assertTrue(download(server).run().isSuccess)
        assertEquals(emptyList(), server.asked)
        assertContentEquals(content, target.readBytes())
    }

    @Test
    fun `a part the server cannot continue is started over`() {
        // A part longer than the pinned file is not offered at all.
        val shorter = content.copyOf(90_000)
        part.writeBytes(content.copyOf(100_000))
        val server = Server(file = shorter)
        assertTrue(download(server, of = shorter).run().isSuccess)
        assertEquals(listOf(0L), server.asked)

        // A part longer than the server's file gets a 416, and the file is asked for whole.
        target.delete()
        server.asked.clear()
        part.writeBytes(content.copyOf(80_000))
        server.file = content.copyOf(50_000)
        assertTrue(download(server, of = shorter).run().isFailure)
        assertEquals(listOf(80_000L, 0L), server.asked)
    }

    @Test
    fun `a full phone keeps what was written and says how much is missing`() {
        val server = Server()
        var room = 100_000
        val filling = { file: File, append: Boolean ->
            object : FileOutputStream(file, append) {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (len > room) throw IOException("write failed: ENOSPC (No space left on device)")
                    room -= len
                    super.write(b, off, len)
                }
            }
        }
        val problem = problemOf(download(server, free = 0, sink = filling).run())
        assertEquals(FileProblem.NotEnoughStorage(neededMb = 1, freeMb = 0), problem)
        assertTrue(part.length() in 1..100_000L)
        val kept = part.length()

        // Room again: only the rest is fetched.
        assertTrue(download(server).run().isSuccess)
        assertEquals(listOf(0L, kept), server.asked)
        assertContentEquals(content, target.readBytes())
    }

    @Test
    fun `a write that fails with room left is a file that cannot be saved`() {
        val broken = { _: File, _: Boolean -> throw IOException("read-only file system") }
        assertEquals(FileProblem.CouldNotSave, problemOf(download(Server(), sink = broken).run()))
    }

    @Test
    fun `a cancel stops at once and keeps the part for later`() {
        val server = Server()
        var seen = 0L
        val result = download(server).run(onProgress = { seen = it }, isCancelled = { seen >= 60_000 })
        assertIs<InterruptedException>(result.exceptionOrNull())
        assertFalse(target.exists())
        val kept = part.length()
        assertTrue(kept in 60_000..content.size - 1L)

        assertTrue(download(server).run().isSuccess)
        assertEquals(listOf(0L, kept), server.asked)
        assertContentEquals(content, target.readBytes())
    }

    @Test
    fun `a connection cut by the cancel is a cancel, not a failure`() {
        val server = Server().apply { cutAfter = 50_000 }
        var seen = 0L
        val result = download(server).run(onProgress = { seen = it }, isCancelled = { seen >= 50_000 })
        assertIs<InterruptedException>(result.exceptionOrNull())
    }

    @Test
    fun `a server that refuses says so and the part stays`() {
        val server = Server().apply { cutAfter = 50_000 }
        download(server).run()
        server.code = 503
        assertEquals(FileProblem.ServerAnswered(503), problemOf(download(server).run()))
        assertEquals(50_000L, part.length())
    }

    @Test
    fun `missing bytes count the part, and a discard lets it go`() {
        val size = content.size.toLong()
        assertEquals(size, FileDownload.missingBytes(target, size))
        val server = Server().apply { cutAfter = 120_000 }
        download(server).run()
        assertEquals(size - 120_000, FileDownload.missingBytes(target, size))
        FileDownload.discardKept(target)
        assertEquals(emptyList(), leftovers())
        server.cutAfter = null
        download(server).run()
        assertEquals(0, FileDownload.missingBytes(target, size))
    }
}
