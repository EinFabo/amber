package app.amethyst.core

import app.amethyst.core.engine.Problem
import app.amethyst.core.engine.FileProblem
import app.amethyst.core.engine.ModelFileException
import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.engine.TranscribeOptions
import app.amethyst.core.engine.whisper.ModelInstall
import app.amethyst.core.engine.whisper.WhisperCppEngine
import app.amethyst.core.engine.whisper.WhisperInput
import app.amethyst.core.engine.whisper.WhisperModelFile
import app.amethyst.core.engine.whisper.WhisperModelSpec
import app.amethyst.core.engine.whisper.WhisperModels
import app.amethyst.core.engine.whisper.WhisperOutput
import app.amethyst.core.engine.whisper.WhisperRuntime
import app.amethyst.core.engine.whisper.WhisperRuntimeException
import app.amethyst.core.privacy.TrustLevel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WhisperTest {

    // --- model header -------------------------------------------------------------------

    /** The tensors whisper.cpp wants of six encoder and six decoder layers: 11 + 15 * 6 + 24 * 6. */
    private val EXPECTED_TENSORS = 11 + 15 * 6 + 24 * 6

    /** The first 48 bytes of FUTO's voice-input-multilingual-74.bin, as hparams. */
    private fun futoHeader(vocab: Int = 51_865, magic: Int = 0x67676d6c): ByteArray =
        ByteBuffer.allocate(WhisperModelFile.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(magic)
            putInt(vocab) // n_vocab
            putInt(1500) // n_audio_ctx
            putInt(512) // n_audio_state
            putInt(8) // n_audio_head
            putInt(6) // n_audio_layer
            putInt(448) // n_text_ctx
            putInt(512) // n_text_state
            putInt(8) // n_text_head
            putInt(6) // n_text_layer
            putInt(80) // n_mels
            putInt(2007) // ftype q8_0, quantization version 2
        }.array()

    @Test
    fun `the FUTO header is read as a multilingual base model`() {
        val info = WhisperModelFile.inspect(futoHeader()).getOrThrow()
        assertTrue(info.multilingual)
        assertEquals("base", info.sizeName)
        assertEquals(6, info.audioLayers)
    }

    @Test
    fun `an english-only model is recognised as such`() {
        val info = WhisperModelFile.inspect(futoHeader(vocab = 51_864)).getOrThrow()
        assertFalse(info.multilingual)
    }

    @Test
    fun `a file that is not ggml is rejected with a reason`() {
        val zip = "PK\u0003\u0004".toByteArray() + ByteArray(60)
        val error = WhisperModelFile.inspect(zip).exceptionOrNull()
        assertEquals(FileProblem.NotWhisperCpp, FileProblem.of(error))
    }

    @Test
    fun `a truncated header is rejected instead of read out of bounds`() {
        assertTrue(WhisperModelFile.inspect(ByteArray(10)).isFailure)
    }

    /** [futoHeader] with the int at [offset] (bytes, magic at 0) set to [value]. */
    private fun headerWith(offset: Int, value: Int): ByteArray =
        futoHeader().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value) }

    @Test
    fun `header fields whisper cpp sizes memory or divides by are held to whisper's`() {
        for ((what, header) in listOf(
            "no audio heads" to headerWith(16, 0),
            "heads that do not divide the width" to headerWith(16, 7),
            "a million audio layers" to headerWith(20, 1_000_000),
            "no text context" to headerWith(24, 0),
            "a text width unlike the audio one" to headerWith(28, 768),
            "negative text heads" to headerWith(32, -8),
            "no text layers" to headerWith(36, 0),
            "a negative ftype" to headerWith(44, -1),
        )) {
            assertEquals(FileProblem.NotWhisper, FileProblem.of(WhisperModelFile.inspect(header).exceptionOrNull()), what)
        }
        assertEquals(FileProblem.NotWhisperCpp, FileProblem.of(WhisperModelFile.inspect(headerWith(0, 0x46554747)).exceptionOrNull()), "GGUF")
    }

    /** One tensor's header and data as the file has them. */
    private data class Tensor(val dims: List<Int> = listOf(4), val type: Int = 0, val name: String = "t", val dataBytes: Int? = null)

    /**
     * A model file as whisper.cpp writes it, with [futoHeader]'s six and six layers: the mel
     * filters, a few tokens of [tokenLengths], and [tensors] - by default exactly as many as
     * Whisper has, each of four floats. Small: the check walks the structure, not the shapes.
     */
    private fun modelFile(
        header: ByteArray = futoHeader(),
        filters: Pair<Int, Int> = 80 to 201,
        tokenCount: Int? = null,
        tokenLengths: List<Int> = listOf(1, 3, 0, 2),
        tensors: List<Tensor> = List(EXPECTED_TENSORS) { Tensor() },
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun int(value: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        out.write(header)
        int(filters.first)
        int(filters.second)
        out.write(ByteArray(filters.first.coerceAtMost(256) * filters.second.coerceAtMost(256) * 4))
        int(tokenCount ?: tokenLengths.size)
        tokenLengths.forEach { length ->
            int(length)
            out.write(ByteArray(length.coerceIn(0, 64)) { 'a'.code.toByte() })
        }
        tensors.forEach { tensor ->
            int(tensor.dims.size)
            int(tensor.name.length)
            int(tensor.type)
            tensor.dims.forEach(::int)
            out.write(tensor.name.toByteArray())
            out.write(ByteArray(tensor.dataBytes ?: (tensor.dims.fold(1L) { a, b -> a * b } * 4).toInt()))
        }
        return out.toByteArray()
    }

    private fun check(bytes: ByteArray, size: Long = bytes.size.toLong()): Result<*> =
        WhisperModelFile.check(ByteArrayInputStream(bytes), size)

    private fun problem(bytes: ByteArray): FileProblem = FileProblem.of(check(bytes).exceptionOrNull())

    @Test
    fun `a whole model passes the check`() {
        val info = check(modelFile()).getOrThrow() as app.amethyst.core.engine.whisper.WhisperModelInfo
        assertEquals("base", info.sizeName)
        // Quantized and K-quantized weights, sized by their blocks.
        val quantized = List(EXPECTED_TENSORS) { if (it % 2 == 0) Tensor(listOf(64, 2), type = 8, dataBytes = 4 * 34) else Tensor(listOf(256), type = 12, dataBytes = 144) }
        assertTrue(check(modelFile(tensors = quantized)).isSuccess)
    }

    @Test
    fun `a model cut off anywhere is named as cut short`() {
        val whole = modelFile()
        val filtersEnd = WhisperModelFile.HEADER_BYTES + 8 + 80 * 201 * 4
        for (at in listOf(WhisperModelFile.HEADER_BYTES + 4, filtersEnd - 10, filtersEnd + 6, whole.size - 30, whole.size - 2)) {
            assertEquals(FileProblem.CutShort, problem(whole.copyOf(at)), "cut at $at of ${whole.size}")
        }
        // Cut exactly between two tensors: every part whole, a tensor missing.
        assertEquals(FileProblem.CutShort, problem(modelFile(tensors = List(EXPECTED_TENSORS - 1) { Tensor() })))
        assertEquals(FileProblem.TooSmallForWhisper, problem(whole.copyOf(20)))
    }

    @Test
    fun `lengths the loader would allocate by are held to whisper's before anything is sized`() {
        for ((what, file) in listOf(
            "a 2 GB token" to modelFile(tokenLengths = listOf(1, Int.MAX_VALUE)),
            "a negative token length" to modelFile(tokenLengths = listOf(-1)),
            "more tokens than the vocabulary" to modelFile(tokenCount = 60_001),
            "a negative token count" to modelFile(tokenCount = -5),
            "a filter table of a billion floats" to modelFile(filters = 40_000 to 40_000),
            "no filters" to modelFile(filters = 0 to 201),
            "five dimensions" to modelFile(tensors = listOf(Tensor(listOf(1, 1, 1, 1, 1)))),
            "a type ggml does not have" to modelFile(tensors = listOf(Tensor(type = 99))),
            "a negative extent" to modelFile(tensors = listOf(Tensor(listOf(-4), dataBytes = 0))),
            "a q8_0 tensor not in whole blocks" to modelFile(tensors = listOf(Tensor(listOf(33), type = 8, dataBytes = 34))),
            "one tensor more than whisper has" to modelFile(tensors = List(EXPECTED_TENSORS + 1) { Tensor() }),
        )) {
            assertEquals(FileProblem.NotWhisper, problem(file), what)
        }
        // A name of a gigabyte (its length sits before the type, one extent, the name and 16 bytes
        // of data), and extents whose product overflows a Long.
        assertEquals(FileProblem.NotWhisper, problem(modelFile(tensors = listOf(Tensor(name = "x")))
            .also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(it.size - 16 - 1 - 4 - 4 - 4, 1 shl 30) }))
        val huge = Int.MAX_VALUE
        assertEquals(FileProblem.CutShort, problem(modelFile(tensors = listOf(Tensor(listOf(huge, huge, huge, huge), dataBytes = 0)))))
    }

    @Test
    fun `a file larger than any phone model is refused before it is read`() {
        val result = check(ByteArray(0), size = WhisperModelFile.MAX_IMPORT_BYTES + 1)
        assertIs<FileProblem.TooLarge>(FileProblem.of(result.exceptionOrNull()))
    }

    @Test
    fun `a tensor type the check cannot size stops it there, not the model`() {
        // IQ2_XXS: whisper.cpp may load it; refusing would refuse a model that works.
        val tensors = List(10) { Tensor() } + Tensor(listOf(256), type = 16, dataBytes = 66)
        assertTrue(check(modelFile(tensors = tensors)).isSuccess)
    }

    // --- input ---------------------------------------------------------------------------

    @Test
    fun `samples are scaled to -1 to 1`() {
        val prepared = WhisperInput.prepare(PcmAudio(shortArrayOf(Short.MIN_VALUE, 0, 16_384)))
        assertEquals(-1f, prepared[0])
        assertEquals(0f, prepared[1])
        assertEquals(0.5f, prepared[2])
    }

    @Test
    fun `short utterances are padded past whisper's one second floor`() {
        val halfSecond = PcmAudio(ShortArray(8_000) { 1_000 })
        val prepared = WhisperInput.prepare(halfSecond)
        assertEquals(WhisperInput.MIN_SAMPLES, prepared.size)
        assertTrue(prepared.size > WhisperInput.SAMPLE_RATE)
        assertEquals(0f, prepared.last(), "padding must be silence")
    }

    @Test
    fun `stereo is mixed down and other rates are resampled to 16 kHz`() {
        val stereo = PcmAudio(ShortArray(48_000 * 2) { 8_192 }, sampleRate = 48_000, channels = 2)
        val prepared = WhisperInput.prepare(stereo)
        assertEquals(16_000 + 3_200, prepared.size) // one second, padded to 1.2 s
        assertEquals(0.25f, prepared[100], 1e-4f)
    }

    @Test
    fun `audio context follows the length of the dictation`() {
        // 3 s = 150 positions + slack, raised to the floor.
        assertEquals(256, WhisperInput.audioCtxFor(3 * 16_000))
        // 10 s = 500 positions + 64 slack.
        assertEquals(564, WhisperInput.audioCtxFor(10 * 16_000))
    }

    @Test
    fun `dictations near or over 30 seconds use the full context`() {
        assertEquals(0, WhisperInput.audioCtxFor(29 * 16_000))
        assertEquals(0, WhisperInput.audioCtxFor(90 * 16_000))
        // The ACFT large-v3-turbo keeps more room after the speech.
        assertEquals(628, WhisperInput.audioCtxFor(10 * 16_000, slack = 128))
    }

    @Test
    fun `language tags are reduced to what whisper understands`() {
        assertEquals("de", WhisperInput.languageFor("de-DE"))
        assertEquals("pt", WhisperInput.languageFor("pt_BR"))
        assertEquals("auto", WhisperInput.languageFor(null))
        assertEquals("auto", WhisperInput.languageFor("  "))
    }

    @Test
    fun `several languages are passed on as a list for native detection`() {
        assertEquals("de,en", WhisperInput.languageFor("de-DE, en_US"))
        assertEquals("de,en", WhisperInput.languageFor("de,en,DE"))
        assertEquals("fr", WhisperInput.languageFor(",fr,"))
    }

    // --- output --------------------------------------------------------------------------

    @Test
    fun `sound annotations are removed`() {
        assertEquals("Hallo Welt", WhisperOutput.clean(" [Musik] Hallo (Lachen) Welt *Applaus*"))
    }

    @Test
    fun `server text keeps brackets, stars and line breaks and loses only the invented credit`() {
        assertEquals("Ich komme (vielleicht) morgen.\nBis dann.", WhisperOutput.cleanServer("Ich komme (vielleicht) morgen.\nBis dann."))
        assertEquals("Das war *wirklich* [sic] gut.", WhisperOutput.cleanServer(" Das war *wirklich* [sic] gut. "))
        assertEquals(
            "Wir sehen uns morgen.",
            WhisperOutput.cleanServer("Wir sehen uns morgen. Untertitel im Auftrag des ZDF, 2020."),
        )
        assertEquals("", WhisperOutput.cleanServer("Untertitel im Auftrag des ZDF, 2021"))
    }

    @Test
    fun `subtitle credits invented on silence are dropped`() {
        assertEquals("", WhisperOutput.clean(" Untertitel der Amara.org-Community"))
        assertEquals("", WhisperOutput.clean("Untertitel im Auftrag des ZDF, 2021"))
    }

    @Test
    fun `the english amara credit is dropped too`() {
        assertEquals("", WhisperOutput.clean("Subtitles by the Amara.org community"))
        assertEquals("See you tomorrow.", WhisperOutput.cleanServer("See you tomorrow. Subtitles by the Amara.org community"))
    }

    @Test
    fun `a whisper model is known by its size as well as by its name`() {
        // Amber PC and Amber Server name theirs without "whisper" (docs/AMBER-PC.md).
        for (id in listOf("whisper-large-v3-turbo", "whisper-1", "acft-large-v3-turbo-q8_0", "large-v3", "distil-large-v3", "Systran/faster-whisper-small", "medium", "small.en", "base-q5_1", "ggml-tiny")) {
            assertTrue(WhisperOutput.isWhisperFamily(id), id)
        }
        for (id in listOf("gpt-4o-transcribe", "parakeet-tdt-0.6b-v3", "nova-3", "scribe_v1", "voxtral-mini-latest", "canary-1b-flash")) {
            assertFalse(WhisperOutput.isWhisperFamily(id), id)
        }
    }

    @Test
    fun `a real thank-you is kept`() {
        assertEquals("Vielen Dank.", WhisperOutput.clean(" Vielen Dank."))
    }

    @Test
    fun `a hallucinated last sentence after real speech is dropped`() {
        assertEquals(
            "Wir sehen uns morgen.",
            WhisperOutput.clean("Wir sehen uns morgen. Untertitel im Auftrag des ZDF, 2020."),
        )
        assertEquals("See you tomorrow.", WhisperOutput.clean("See you tomorrow. Thanks for watching!"))
    }

    @Test
    fun `thanks for watching alone is dropped`() {
        assertEquals("", WhisperOutput.clean("Thanks for watching!"))
    }

    @Test
    fun `a phrase inside a real sentence stays`() {
        assertEquals("Thanks for watching the kids.", WhisperOutput.clean("Thanks for watching the kids."))
        assertEquals(
            "Danke fürs Zuschauen war nett. Bis morgen.",
            WhisperOutput.clean("Danke fürs Zuschauen war nett. Bis morgen."),
        )
        assertEquals(
            "Ich sage danke fürs Zuschauen und gehe.",
            WhisperOutput.clean("Ich sage danke fürs Zuschauen und gehe."),
        )
    }

    // --- engine --------------------------------------------------------------------------

    private class FakeRuntime(
        private val answer: String = " Hallo Welt.",
        private val fail: Boolean = false,
    ) : WhisperRuntime {
        val loaded = mutableListOf<String>()
        var prompt: String? = null
        var detected: String? = null
        var language: String? = null
        var audioCtx: Int? = null
        var sampleCount = 0

        override fun ensureLoaded(modelPath: String) {
            loaded += modelPath
        }

        override fun transcribe(samples: FloatArray, language: String, audioCtx: Int, threads: Int, prompt: String): String {
            if (fail) throw WhisperRuntimeException("whisper_full returned -7")
            this.language = language
            this.prompt = prompt
            this.audioCtx = audioCtx
            sampleCount = samples.size
            return answer
        }

        override fun abort() = Unit

        override fun lastLanguage(): String? = detected
    }

    @Test
    fun `a chunk's preceding text reaches whisper and the detected language comes back`() = runTest {
        val runtime = FakeRuntime().apply { detected = "en" }
        val transcript = engine(runtime)
            .transcribe(speech(2), TranscribeOptions(languageTag = "de,en", precedingText = "Das ist"))
            .getOrThrow()

        assertEquals("Das ist", runtime.prompt)
        assertEquals("de,en", runtime.language)
        assertEquals("en", transcript.detectedLanguage)
    }

    private fun engine(
        runtime: WhisperRuntime,
        shortContext: Boolean = true,
        clock: () -> Long = { 0L },
    ) = WhisperCppEngine(
        id = "local-whisper",
        displayName = "Whisper lokal",
        modelPath = "/models/whisper.bin",
        runtime = runtime,
        threads = 4,
        shortContext = shortContext,
        clock = clock,
    )

    private fun speech(seconds: Int) = PcmAudio(ShortArray(seconds * 16_000) { 500 })

    @Test
    fun `a transcription is on-device, cleaned and timed`() = runTest {
        val runtime = FakeRuntime()
        var now = 1_000L
        val transcript = engine(runtime, clock = { now.also { now += 420 } })
            .transcribe(speech(3), TranscribeOptions(languageTag = "de-DE"))
            .getOrThrow()

        assertEquals("Hallo Welt.", transcript.text)
        assertEquals(TrustLevel.ON_DEVICE, transcript.trustLevel)
        assertEquals(420, transcript.latencyMs)
        assertEquals("de", runtime.language)
        assertEquals(256, runtime.audioCtx)
        assertEquals(listOf("/models/whisper.bin"), runtime.loaded)
    }

    @Test
    fun `silence that comes back as a subtitle credit is reported as nothing recognised`() = runTest {
        val result = engine(FakeRuntime(answer = " Untertitel der Amara.org-Community"))
            .transcribe(speech(2))
        val failure = assertIs<SttFailure>(result.exceptionOrNull())
        assertEquals(Problem.NothingRecognized, failure.problem)
    }

    @Test
    fun `a native failure becomes a user message instead of a crash`() = runTest {
        val result = engine(FakeRuntime(fail = true)).transcribe(speech(2))
        val failure = assertIs<SttFailure>(result.exceptionOrNull())
        assertEquals(Problem.LocalModelFailed, failure.problem)
        assertTrue(failure.technical.contains("-7"))
    }

    @Test
    fun `a processor without dot-product instructions is named, not blamed on the download`() = runTest {
        val oldCpu = object : WhisperRuntime {
            override fun ensureLoaded(modelPath: String) =
                throw WhisperRuntimeException("${WhisperCppEngine.CPU_UNSUPPORTED}: local Whisper needs ARMv8.2")
            override fun transcribe(samples: FloatArray, language: String, audioCtx: Int, threads: Int, prompt: String) = ""
            override fun abort() = Unit
        }
        val failure = assertIs<SttFailure>(engine(oldCpu).transcribe(speech(2)).exceptionOrNull())
        assertEquals(Problem.ProcessorTooOld, failure.problem)
    }

    @Test
    fun `cancelling a dictation tells the native side to stop`() = runTest {
        val started = java.util.concurrent.CountDownLatch(1)
        val aborted = java.util.concurrent.CountDownLatch(1)
        val blocking = object : WhisperRuntime {
            override fun ensureLoaded(modelPath: String) = Unit
            override fun transcribe(samples: FloatArray, language: String, audioCtx: Int, threads: Int, prompt: String): String {
                started.countDown()
                // Stands in for whisper_full: returns only once abort() was called.
                check(aborted.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "never aborted" }
                throw WhisperRuntimeException("aborted")
            }
            override fun abort() = aborted.countDown()
        }

        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            engine(blocking).transcribe(speech(2))
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            check(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        job.cancel()
        job.join()

        assertEquals(0, aborted.count, "abort() must reach the runtime")
    }

    // --- install -------------------------------------------------------------------------

    private fun tempDir(): File = Files.createTempDirectory("whisper-test").toFile()

    private fun modelBytes(): ByteArray = futoHeader() + ByteArray(1_000) { it.toByte() }

    private fun specFor(bytes: ByteArray) = WhisperModelSpec(
        id = "test",
        displayName = "Test",
        url = "https://example.invalid/model.bin",
        sizeBytes = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) },
        note = "",
    )

    @Test
    fun `a download with the pinned hash is installed`() {
        val bytes = modelBytes()
        val target = File(tempDir(), "whisper.bin")
        val progress = mutableListOf<Long>()

        val info = ModelInstall.install(ByteArrayInputStream(bytes), target, specFor(bytes), { progress += it })
            .getOrThrow()

        assertTrue(info.multilingual)
        assertEquals(bytes.size.toLong(), target.length())
        assertEquals(bytes.size.toLong(), progress.last())
        assertFalse(File(target.parentFile, "whisper.bin.part").exists())
    }

    @Test
    fun `a tampered download is refused and leaves the old model alone`() {
        val bytes = modelBytes()
        val target = File(tempDir(), "whisper.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val tampered = bytes.copyOf().also { it[100] = 99 }

        val result = ModelInstall.install(ByteArrayInputStream(tampered), target, specFor(bytes))

        assertEquals(FileProblem.ChecksumMismatch, FileProblem.of(result.exceptionOrNull()))
        assertEquals(3, target.length(), "the existing file must not be replaced")
        assertFalse(File(target.parentFile, "whisper.bin.part").exists())
    }

    @Test
    fun `a download longer than pinned stops before it fills the phone`() {
        val bytes = modelBytes()
        val target = File(tempDir(), "whisper.bin")
        var written = 0L
        // An endless stream: the copy must stop just past the pinned size.
        val endless = object : java.io.InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = len
        }
        val result = ModelInstall.install(endless, target, specFor(bytes), { written = it })
        assertTrue(result.isFailure)
        assertTrue(written <= bytes.size.toLong() + 64 * 1024, "stopped at $written")
        assertFalse(target.exists())
    }

    @Test
    fun `a truncated download is named as incomplete`() {
        val bytes = modelBytes()
        val result = ModelInstall.install(
            ByteArrayInputStream(bytes.copyOf(500)),
            File(tempDir(), "whisper.bin"),
            specFor(bytes),
        )
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("incomplete"))
    }

    @Test
    fun `an imported file is checked from end to end`() {
        val target = File(tempDir(), "whisper.bin")
        val notAModel = ByteArrayInputStream(ByteArray(2_000) { 7 })

        assertTrue(ModelInstall.install(notAModel, target, expected = null).isFailure)
        assertFalse(target.exists())

        // A good header in front of anything is not a model.
        val headerOnly = ModelInstall.install(ByteArrayInputStream(modelBytes()), target, null)
        assertTrue(headerOnly.isFailure)
        assertFalse(target.exists())

        assertTrue(ModelInstall.install(ByteArrayInputStream(modelFile()), target, null).isSuccess)
        assertTrue(target.exists())
    }

    @Test
    fun `a catalog download is the file its hash names, so its header is all that is read`() {
        // Header and padding only: no imported file would pass, the pinned one does.
        val bytes = modelBytes()
        assertTrue(ModelInstall.install(ByteArrayInputStream(bytes), File(tempDir(), "whisper.bin"), specFor(bytes)).isSuccess)
    }

    @Test
    fun `a connection drop mid-download is reported, not thrown`() {
        val dropping = object : InputStream() {
            var served = 0
            override fun read(): Int = if (served++ < 100) 1 else throw IOException("reset")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served >= 100) throw IOException("connection reset")
                served += 100
                return 100.coerceAtMost(len)
            }
        }
        val result = ModelInstall.install(dropping, File(tempDir(), "whisper.bin"), null)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("interrupted"))
    }

    // --- catalog -------------------------------------------------------------------------

    @Test
    fun `a stock whisper model always gets the full context`() = runTest {
        val runtime = FakeRuntime()
        engine(runtime, shortContext = false).transcribe(speech(3)).getOrThrow()
        assertEquals(0, runtime.audioCtx, "0 tells whisper.cpp to use all 1500 positions")
    }

    @Test
    fun `only the ACFT fine-tunes may shorten the context`() {
        // FUTO's own models and the ACFT large-v3-turbo; stock Whisper loses words with a
        // shortened context (measured: 62.9 % WER for stock turbo on Earnings22).
        WhisperModels.all.forEach { spec ->
            val acft = spec.url.contains("keyboard.futo.org") || spec.id.startsWith("acft-")
            assertEquals(acft, spec.shortContext, spec.id)
        }
    }

    @Test
    fun `catalog entries are unique and pinned`() {
        val ids = WhisperModels.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertFalse(WhisperModels.IMPORTED_ID in ids)
        WhisperModels.all.forEach { spec ->
            assertTrue(Regex("^[0-9a-f]{64}$").matches(spec.sha256), spec.id)
            assertTrue(spec.sizeBytes > 10_000_000, spec.id)
            assertTrue(spec.url.startsWith("https://"), spec.id)
        }
        assertEquals(WhisperModels.FUTO_MULTILINGUAL_74, WhisperModels.default)
    }

    @Test
    fun `a picked file is recognised as a known model by its size`() {
        assertEquals(WhisperModels.FUTO_MULTILINGUAL_244, WhisperModels.matchingSize(264_464_624L))
        assertEquals(null, WhisperModels.matchingSize(12_345L))
        assertEquals(null, WhisperModels.matchingSize(null))
    }
}
