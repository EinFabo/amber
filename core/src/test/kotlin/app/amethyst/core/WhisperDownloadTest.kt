package app.amethyst.core

import app.amethyst.core.engine.FileProblem
import app.amethyst.core.engine.ModelFileException
import app.amethyst.core.engine.download.DownloadAnswer
import app.amethyst.core.engine.download.DownloadTransport
import app.amethyst.core.engine.whisper.ModelInstall
import app.amethyst.core.engine.whisper.WhisperModelFile
import app.amethyst.core.engine.whisper.WhisperModelSpec
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A catalog Whisper model's download: continued after a break, and nothing kept trusted without the pinned hash. */
class WhisperDownloadTest {

    /** A multilingual base model's header (FUTO's), then bytes standing in for the tensors. */
    private val model: ByteArray = ByteBuffer.allocate(WhisperModelFile.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
        intArrayOf(0x67676d6c, 51_865, 1500, 512, 8, 6, 448, 512, 8, 6, 80, 2007).forEach(::putInt)
    }.array() + ByteArray(200_000) { (it * 7 + it / 13).toByte() }

    private val spec = WhisperModelSpec(
        id = "test",
        displayName = "Test",
        url = "https://models.example/model.bin",
        sizeBytes = model.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(model).joinToString("") { "%02x".format(it) },
        note = "",
    )

    private val dir = createTempDirectory().toFile()
    private val target = File(dir, "test.bin")

    /** Where an older version copied a download to - and where a download keeps its part now. */
    private val part = File(dir, "test.bin.part")

    /** Sends [limit] bytes, then the connection breaks. */
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

    private inner class Server : DownloadTransport {
        val asked = mutableListOf<Long>()
        var cutAfter: Int? = null

        override fun open(url: String, from: Long): DownloadAnswer {
            asked += from
            val bytes = model.copyOfRange(from.toInt(), model.size)
            val body = cutAfter?.let { Cut(bytes, it) } ?: ByteArrayInputStream(bytes)
            return if (from > 0) {
                DownloadAnswer(206, "\"v1\"", null, "bytes $from-${model.size - 1}/${model.size}", body)
            } else {
                DownloadAnswer(200, "\"v1\"", null, null, body)
            }
        }
    }

    private fun download(server: Server) = ModelInstall.download(spec, target, server)

    @Test
    fun `a download cut halfway goes on where it stopped and installs the model`() {
        val server = Server().apply { cutAfter = 90_000 }
        val failed = download(server)
        assertEquals(FileProblem.Interrupted, assertIs<ModelFileException>(failed.exceptionOrNull()).problem)
        assertFalse(target.exists(), "nothing half-written where the loader looks")
        assertEquals(spec.sizeBytes - 90_000, ModelInstall.missingBytes(spec, target))

        server.cutAfter = null
        val progress = mutableListOf<Long>()
        val info = ModelInstall.download(spec, target, server, onProgress = { progress += it }).getOrThrow()
        assertTrue(info.multilingual)
        assertEquals(listOf(0L, 90_000L), server.asked)
        assertEquals(90_000L, progress.first(), "the kept bytes count from the start")
        assertContentEquals(model, target.readBytes())
        assertEquals(0, ModelInstall.missingBytes(spec, target))
        assertEquals(listOf("test.bin"), dir.list().orEmpty().toList())
    }

    @Test
    fun `a part an older version left is continued only because the whole file then has the pinned hash`() {
        // Its first 80 000 bytes, with no origin beside it - as a process that died mid-copy left it.
        part.writeBytes(model.copyOf(80_000))
        val server = Server()
        assertTrue(download(server).isSuccess)
        assertEquals(listOf(80_000L), server.asked)
        assertContentEquals(model, target.readBytes())
    }

    @Test
    fun `a damaged part an older version left is not trusted - the model is fetched again whole`() {
        part.writeBytes(model.copyOf(80_000).also { it[5_000] = (it[5_000] + 1).toByte() })
        val server = Server()
        assertTrue(download(server).isSuccess)
        assertEquals(listOf(80_000L, 0L), server.asked)
        assertContentEquals(model, target.readBytes())
    }

    @Test
    fun `a part longer than the model is thrown away before anything is asked`() {
        part.writeBytes(model + ByteArray(10))
        val server = Server()
        assertTrue(download(server).isSuccess)
        assertEquals(listOf(0L), server.asked)
    }

    @Test
    fun `a discard lets go of what was kept`() {
        download(Server().apply { cutAfter = 50_000 })
        ModelInstall.discardKept(target)
        assertEquals(emptyList(), dir.list().orEmpty().toList())
        assertEquals(spec.sizeBytes, ModelInstall.missingBytes(spec, target))
    }
}
