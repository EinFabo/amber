package app.amethyst.core

import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.engine.TranscribeOptions
import app.amethyst.core.engine.sherpa.ParakeetEngine
import app.amethyst.core.engine.sherpa.ParakeetModel
import app.amethyst.core.engine.sherpa.TransducerRuntime
import app.amethyst.core.privacy.TrustLevel
import kotlinx.coroutines.test.runTest
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ParakeetTest {

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `a file lands only when size and hash match`() {
        val dir = createTempDirectory().toFile()
        val bytes = "tokens".encodeToByteArray()
        val good = ParakeetModel.FileSpec("tokens.txt", bytes.size.toLong(), sha(bytes))

        assertTrue(ParakeetModel.install(bytes.inputStream(), dir, good).isSuccess)
        assertTrue(File(dir, "tokens.txt").isFile)

        val tampered = good.copy(name = "joiner.int8.onnx", sha256 = "0".repeat(64))
        assertTrue(ParakeetModel.install(bytes.inputStream(), dir, tampered).isFailure)
        assertFalse(File(dir, "joiner.int8.onnx").exists(), "a bad file never takes the target's place")
        assertFalse(File(dir, "joiner.int8.onnx.part").exists())
    }

    @Test
    fun `the model is complete only with every file at its pinned size`() {
        val dir = createTempDirectory().toFile()
        assertFalse(ParakeetModel.isComplete(dir))
        assertEquals(4, ParakeetModel.FILES.size)
        assertTrue(ParakeetModel.TOTAL_BYTES > 600_000_000)
    }

    private class FakeRuntime(private val answer: String = " Hallo Welt. ", private val fail: Exception? = null) : TransducerRuntime {
        var loaded: String? = null
        var samples = 0
        override fun ensureLoaded(modelDir: String) { loaded = modelDir }
        override fun transcribe(samples: FloatArray): String {
            fail?.let { throw it }
            this.samples = samples.size
            return answer
        }
    }

    private fun engine(runtime: TransducerRuntime) =
        ParakeetEngine("local-parakeet", "Parakeet", "/models/parakeet", runtime, clock = { 0L })

    @Test
    fun `recognizes on the device and trims the text`() = runTest {
        val runtime = FakeRuntime()
        val transcript = engine(runtime).transcribe(PcmAudio(ShortArray(32_000)), TranscribeOptions(languageTag = "de,en")).getOrThrow()
        assertEquals("Hallo Welt.", transcript.text)
        assertEquals(TrustLevel.ON_DEVICE, transcript.trustLevel)
        assertEquals("/models/parakeet", runtime.loaded)
        assertEquals(32_000, runtime.samples)
    }

    @Test
    fun `empty output is nothing recognized, a crash is a readable failure`() = runTest {
        val empty = engine(FakeRuntime(answer = "  ")).transcribe(PcmAudio(ShortArray(16_000)), TranscribeOptions())
        assertTrue(assertIs<SttFailure>(empty.exceptionOrNull()).nothingRecognized)

        val crash = engine(FakeRuntime(fail = IllegalStateException("onnx"))).transcribe(PcmAudio(ShortArray(16_000)), TranscribeOptions())
        assertTrue(assertIs<SttFailure>(crash.exceptionOrNull()).userMessage.contains("local model"))
    }
}
