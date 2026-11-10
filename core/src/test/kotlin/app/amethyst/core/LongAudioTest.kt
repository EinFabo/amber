package app.amethyst.core

import app.amethyst.core.audio.LongAudio
import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.engine.TranscribeOptions
import app.amethyst.core.engine.sherpa.ParakeetEngine
import app.amethyst.core.engine.sherpa.TransducerRuntime
import app.amethyst.core.engine.whisper.WhisperCppEngine
import app.amethyst.core.engine.whisper.WhisperRuntime
import kotlinx.coroutines.test.runTest
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LongAudioTest {

    private val rate = 16_000

    /** Speech-like tone, quiet for the last [pauseMs] of every [pauseEveryMs]. */
    private fun recording(ms: Int, pauseEveryMs: Int = 7_000, pauseMs: Int = 500): FloatArray = FloatArray((ms.toLong() * rate / 1000).toInt()) { i ->
        val at = i * 1000L / rate
        if (at % pauseEveryMs in (pauseEveryMs - pauseMs.toLong()) until pauseEveryMs) 0.001f else (0.3 * sin(i / 9.0)).toFloat()
    }

    @Test
    fun `a recording up to 28 s stays whole, so chunks recognized while speaking are never cut`() {
        assertEquals(listOf(0 until 28 * rate), LongAudio.pieces(recording(28_000)))
        assertEquals(listOf(0 until 25 * rate), LongAudio.pieces(recording(25_000)))
        assertEquals(emptyList(), LongAudio.pieces(FloatArray(0)))
    }

    @Test
    fun `five minutes go in pieces of at most 25 s that cover every sample once`() {
        val samples = recording(300_000)
        val pieces = LongAudio.pieces(samples)
        assertTrue(pieces.size in 12..14, "${pieces.size} pieces")
        assertEquals(0, pieces.first().first)
        assertEquals(samples.size - 1, pieces.last().last)
        pieces.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        pieces.forEach { piece ->
            val ms = piece.count() * 1000L / rate
            assertTrue(ms in LongAudio.MIN_REST_MS..LongAudio.PIECE_MS, "a piece of $ms ms")
        }
    }

    @Test
    fun `each cut lands in a pause when there is one`() {
        // 1.5 s of quiet every four: the last three seconds of a piece always hold some of it.
        val samples = recording(300_000, pauseEveryMs = 4_000, pauseMs = 1_500)
        LongAudio.pieces(samples).dropLast(1).forEach { piece ->
            val cut = piece.last + 1
            assertTrue(samples[cut] == 0.001f, "cut at ${cut * 1000L / rate} ms is not in a pause")
        }
    }

    @Test
    fun `the last piece is never a scrap`() {
        val pieces = LongAudio.pieces(recording(50_500, pauseEveryMs = 1_000_000))
        assertTrue(pieces.last().count() >= LongAudio.MIN_REST_MS * rate / 1000, "${pieces.map { it.count() }}")
    }

    private class Whisper(private val answers: (Int) -> String = { "Teil ${it + 1}." }) : WhisperRuntime {
        val calls = mutableListOf<Triple<Int, String, String>>()
        var detected: String? = "de"
        override fun ensureLoaded(modelPath: String) = Unit
        override fun transcribe(samples: FloatArray, language: String, audioCtx: Int, threads: Int, prompt: String): String {
            calls += Triple(samples.size, language, prompt)
            return answers(calls.size - 1)
        }
        override fun lastLanguage(): String? = detected
        override fun abort() = Unit
    }

    private fun pcm(ms: Int) = PcmAudio(ShortArray((ms.toLong() * rate / 1000).toInt()) { i -> (8_000 * sin(i / 9.0)).toInt().toShort() })

    private fun whisper(runtime: WhisperRuntime) =
        WhisperCppEngine("local-whisper", "Whisper", "/models/w.bin", runtime, threads = 4, shortContext = true, clock = { 0L })

    @Test
    fun `whisper hears five minutes piece by piece, in the language of the first, with the text so far as prompt`() = runTest {
        val runtime = Whisper()
        val transcript = whisper(runtime).transcribe(pcm(300_000), TranscribeOptions(languageTag = "de,en", precedingText = "Davor")).getOrThrow()
        assertTrue(runtime.calls.size in 12..14)
        assertTrue(runtime.calls.all { it.first <= LongAudio.PIECE_MS * rate / 1000 })
        assertEquals("de,en", runtime.calls[0].second)
        assertTrue(runtime.calls.drop(1).all { it.second == "de" }, "locked to the first piece's language")
        assertEquals("Davor", runtime.calls[0].third)
        assertEquals("Teil 1.", runtime.calls[1].third)
        assertTrue(transcript.text.startsWith("Teil 1. Teil 2. Teil 3."))
        assertEquals("de", transcript.detectedLanguage)
    }

    @Test
    fun `a short dictation is one whisper request, as before`() = runTest {
        val runtime = Whisper()
        whisper(runtime).transcribe(pcm(20_000), TranscribeOptions(languageTag = "de,en")).getOrThrow()
        assertEquals(1, runtime.calls.size)
        assertEquals("de,en", runtime.calls.single().second)
    }

    @Test
    fun `a silent piece is skipped, all silent is nothing recognized`() = runTest {
        val some = whisper(Whisper { if (it == 1) "" else "Teil ${it + 1}." }).transcribe(pcm(60_000), TranscribeOptions()).getOrThrow()
        assertTrue(some.text.startsWith("Teil 1. Teil 3."), some.text)
        val none = whisper(Whisper { "" }).transcribe(pcm(60_000), TranscribeOptions())
        assertTrue(assertIs<SttFailure>(none.exceptionOrNull()).nothingRecognized)
    }

    @Test
    fun `parakeet never gets more than one piece at a time`() = runTest {
        val sizes = mutableListOf<Int>()
        val runtime = object : TransducerRuntime {
            override fun ensureLoaded(modelDir: String) = Unit
            override fun transcribe(samples: FloatArray): String {
                sizes += samples.size
                return " Satz ${sizes.size}. "
            }
        }
        val transcript = ParakeetEngine("local-parakeet", "Parakeet", "/models/p", runtime, clock = { 0L })
            .transcribe(pcm(300_000), TranscribeOptions()).getOrThrow()
        assertTrue(sizes.size in 12..14)
        assertTrue(sizes.all { it <= LongAudio.PIECE_MS * rate / 1000 })
        assertEquals(300 * rate, sizes.sum())
        assertTrue(transcript.text.startsWith("Satz 1. Satz 2."))
    }
}
