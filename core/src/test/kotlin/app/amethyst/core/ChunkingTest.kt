package app.amethyst.core

import app.amethyst.core.engine.Problem
import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.capture.ChunkPlanner
import app.amethyst.core.capture.PcmAccumulator
import app.amethyst.core.engine.ChunkedTranscription
import app.amethyst.core.engine.EngineCapabilities
import app.amethyst.core.engine.SttEngine
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.engine.TranscribeOptions
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.text.Transcript
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunkingTest {

    private val rate = 16_000
    private val frame = rate / 50 // 20 ms

    /** Feeds [speech] frames of speech then [silence] frames of silence; returns the chunks. */
    private fun ChunkPlanner.feed(pattern: List<Pair<Boolean, Int>>, start: Int = 0): Pair<List<ChunkPlanner.Chunk>, Int> {
        val chunks = mutableListOf<ChunkPlanner.Chunk>()
        var samples = start
        for ((speech, frames) in pattern) {
            repeat(frames) {
                samples += frame
                onFrame(samples, if (speech) 2_000.0 else 50.0, speech)?.let(chunks::add)
            }
        }
        return chunks to samples
    }

    private fun seconds(s: Double) = (s * 50).toInt()

    // --- planner -------------------------------------------------------------------------

    @Test
    fun `cuts in the middle of a pause once the chunk is long enough`() {
        val planner = ChunkPlanner(rate)
        val (chunks, _) = planner.feed(listOf(true to seconds(5.0), false to seconds(1.0)))

        val chunk = chunks.single()
        assertEquals(0, chunk.start)
        // The pause reaches 800 ms after 40 frames; the cut goes 400 ms back from there, into
        // the middle of the pause: 5 s of speech + 400 ms.
        assertEquals(5 * rate + 400 * rate / 1000, chunk.end)
        assertEquals(800, chunk.pauseMs)
    }

    @Test
    fun `a breath of 450 ms does not cut an early chunk but cuts in the last quarter before the longest`() {
        val early = ChunkPlanner(rate)
        assertTrue(early.feed(listOf(true to seconds(5.0), false to seconds(0.7))).first.isEmpty())

        val late = ChunkPlanner(rate)
        val chunk = late.feed(listOf(true to seconds(19.0), false to seconds(0.6))).first.single()
        assertEquals(460, chunk.pauseMs)
    }

    @Test
    fun `a forced cut says there was no pause`() {
        val planner = ChunkPlanner(rate, maxChunkMs = 10_000, quietSearchMs = 2_000)
        val chunks = planner.feed(listOf(true to seconds(11.0))).first
        assertEquals(0, chunks.single().pauseMs)
    }

    @Test
    fun `a short phrase followed by a pause is not cut off on its own`() {
        val planner = ChunkPlanner(rate)
        val (chunks, _) = planner.feed(listOf(true to seconds(2.0), false to seconds(0.8), true to seconds(1.0)))
        assertTrue(chunks.isEmpty(), "too short to carry context: $chunks")
    }

    @Test
    fun `a breath shorter than the pause does not cut`() {
        val planner = ChunkPlanner(rate)
        val (chunks, _) = planner.feed(listOf(true to seconds(5.0), false to seconds(0.3), true to seconds(2.0)))
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `speech without any pause is cut at the quietest moment before the limit`() {
        val planner = ChunkPlanner(rate, maxChunkMs = 10_000, quietSearchMs = 2_000)
        val chunks = mutableListOf<ChunkPlanner.Chunk>()
        var samples = 0
        repeat(seconds(11.0)) { i ->
            samples += frame
            // One quieter (but still speech) frame at 9 s.
            val rms = if (i == seconds(9.0)) 900.0 else 2_000.0
            planner.onFrame(samples, rms, speech = true)?.let(chunks::add)
        }
        assertEquals((seconds(9.0) + 1) * frame, chunks.single().end)
    }

    @Test
    fun `long silence without speech is skipped, not chunked`() {
        val planner = ChunkPlanner(rate, maxChunkMs = 10_000)
        val (chunks, end) = planner.feed(listOf(false to seconds(12.0)))
        assertTrue(chunks.isEmpty())
        assertTrue(planner.chunkStart > 0 && planner.chunkStart <= end)
        assertTrue(!planner.speechSinceCut)
    }

    @Test
    fun `a pause cut leaves no speech pending, new speech does`() {
        val planner = ChunkPlanner(rate)
        planner.feed(listOf(true to seconds(5.0), false to seconds(1.0)))
        assertTrue(!planner.speechSinceCut)
        planner.onFrame(seconds(6.0) * frame, 2_000.0, speech = true)
        assertTrue(planner.speechSinceCut)
    }

    @Test
    fun `words after a forced cut stay pending when the limit lands on a quiet frame`() {
        val planner = ChunkPlanner(rate, maxChunkMs = 10_000, quietSearchMs = 2_000)
        val chunks = mutableListOf<ChunkPlanner.Chunk>()
        var samples = 0
        // Speech without a pause; its quietest moment at 9 s, then a short breath right at the
        // limit - the cut goes back to 9 s, the words from 9 s to 10 s come after it.
        repeat(seconds(10.0)) { i ->
            samples += frame
            val last = i == seconds(10.0) - 1
            val rms = when {
                i == seconds(9.0) -> 10.0
                last -> 50.0
                else -> 2_000.0
            }
            planner.onFrame(samples, rms, speech = !last)?.let(chunks::add)
        }
        assertEquals((seconds(9.0) + 1) * frame, chunks.single().end)
        assertTrue(planner.speechSinceCut, "the last second of words would be dropped when the user stops now")
    }

    @Test
    fun `a forced cut at the current frame leaves no speech pending`() {
        val planner = ChunkPlanner(rate, maxChunkMs = 10_000, quietSearchMs = 2_000)
        val chunks = mutableListOf<ChunkPlanner.Chunk>()
        var samples = 0
        // The quietest moment is the frame at the limit itself: the cut takes all the words.
        repeat(seconds(10.0)) { i ->
            samples += frame
            planner.onFrame(samples, if (i == seconds(10.0) - 1) 10.0 else 2_000.0, speech = true)?.let(chunks::add)
        }
        assertEquals(samples, chunks.single().end)
        assertTrue(!planner.speechSinceCut)
    }

    // --- slicing ---------------------------------------------------------------------------

    @Test
    fun `slices across frame boundaries without building the whole buffer`() {
        val acc = PcmAccumulator(rate)
        acc.append(ShortArray(3) { (it + 1).toShort() })
        acc.append(ShortArray(3) { (it + 4).toShort() })
        assertEquals(listOf<Short>(2, 3, 4, 5), acc.slice(1, 5).samples.toList())
        assertEquals(listOf<Short>(5, 6), acc.slice(4).samples.toList())
        assertEquals(0, acc.slice(6).samples.size)
    }

    // --- joining and the chunk queue -------------------------------------------------------

    @Test
    fun `chunk texts join with single spaces and keep punctuation attached`() {
        assertEquals("Hallo Welt, wie geht's?", ChunkedTranscription.join(listOf("Hallo Welt", ", wie geht's?")))
        assertEquals("Erster Satz. Zweiter Satz.", ChunkedTranscription.join(listOf(" Erster Satz. ", "", "Zweiter Satz.")))
    }

    @Test
    fun `a cut at a breath joins a small next word into the sentence, a pause keeps both`() {
        val parts = listOf("Ich wollte dir sagen.", "Dass ich komme.")
        assertEquals(
            "Ich wollte dir sagen dass ich komme.",
            ChunkedTranscription.join(parts, listOf(0, 450), "de"),
        )
        assertEquals(
            "Ich wollte dir sagen. Dass ich komme.",
            ChunkedTranscription.join(parts, listOf(0, 800), "de"),
        )
        // A cut in speech (0 ms) is as short as a breath.
        assertEquals(
            "Wir treffen uns am Bahnhof.",
            ChunkedTranscription.join(listOf("Wir treffen uns.", "Am Bahnhof."), listOf(0, 0), "de"),
        )
    }

    @Test
    fun `a next word not on the list keeps the full stop and its capital`() {
        assertEquals(
            "I met him. Peter came too.",
            ChunkedTranscription.join(listOf("I met him.", "Peter came too."), listOf(0, 450), "en"),
        )
        assertEquals(
            "Das war gut. Morgen kommt er.",
            ChunkedTranscription.join(listOf("Das war gut.", "Morgen kommt er."), listOf(0, 450), "de"),
        )
    }

    @Test
    fun `a full stop that ends an abbreviation stays at a breath`() {
        assertEquals(
            "Wir brauchen Stifte, Papier etc. Und Kleber.",
            ChunkedTranscription.join(listOf("Wir brauchen Stifte, Papier etc.", "Und Kleber."), listOf(0, 450), "de"),
        )
        assertEquals(
            "Das ist z. B. So.",
            ChunkedTranscription.join(listOf("Das ist z. B.", "So."), listOf(0, 450), "de"),
        )
        assertEquals(
            "Ich frage Dr. Meyer.",
            ChunkedTranscription.join(listOf("Ich frage Dr.", "Meyer."), listOf(0, 450), "de"),
        )
    }

    @Test
    fun `only a plain full stop goes at a breath`() {
        assertEquals(
            "Wirklich? Und dann?",
            ChunkedTranscription.join(listOf("Wirklich?", "Und dann?"), listOf(0, 450), "de"),
        )
        assertEquals(
            "Moment... Und weiter",
            ChunkedTranscription.join(listOf("Moment...", "Und weiter"), listOf(0, 450), "de"),
        )
    }

    @Test
    fun `finish joins by the pause each chunk was cut at`() = runTest {
        val engine = ScriptedEngine(listOf(Result.success("Ich wollte dir sagen."), Result.success("Dass ich komme."), Result.success("Und so.")), language = "de")
        val chunked = ChunkedTranscription(engine, TranscribeOptions(), this)
        chunked.submit(audio(10), pauseAfterMs = 450)
        chunked.submit(audio(10), pauseAfterMs = 800)
        val result = chunked.finish(audio(10), audio(30)).getOrThrow()
        assertEquals("Ich wollte dir sagen dass ich komme. Und so.", result.text)
    }

    /** Answers each chunk with the next text; records what options it was called with. */
    private class ScriptedEngine(private val answers: List<Result<String>>, private val language: String? = "en") : SttEngine {
        override val id = "test"
        override val displayName = "Test"
        override val capabilities = EngineCapabilities(TrustLevel.ON_DEVICE, chunkable = true)
        val calls = mutableListOf<Pair<Int, TranscribeOptions>>()

        override suspend fun transcribe(audio: PcmAudio, options: TranscribeOptions): Result<Transcript> {
            calls += audio.samples.size to options
            val answer = answers.getOrElse(calls.size - 1) { Result.success("whole") }
            return answer.map { Transcript(it, id, TrustLevel.ON_DEVICE, 0, detectedLanguage = language) }
        }
    }

    private fun audio(n: Int) = PcmAudio(ShortArray(n), rate)

    @Test
    fun `chunks are transcribed in order with the text so far as prompt and the language locked`() = runTest {
        val engine = ScriptedEngine(listOf(Result.success("Das ist"), Result.success("ein Test.")))
        val chunked = ChunkedTranscription(engine, TranscribeOptions(languageTag = "de,en"), this)

        chunked.submit(audio(100))
        val transcript = chunked.finish(tail = audio(50), whole = audio(150)).getOrThrow()

        assertEquals("Das ist ein Test.", transcript.text)
        assertEquals(listOf(100, 50), engine.calls.map { it.first })
        assertEquals("de,en", engine.calls[0].second.languageTag)
        assertNull(engine.calls[0].second.precedingText)
        assertEquals("en", engine.calls[1].second.languageTag, "the first chunk's language is kept")
        assertEquals("Das ist", engine.calls[1].second.precedingText)
        assertEquals(2, chunked.chunkCount)
    }

    @Test
    fun `a chunk without speech is skipped, not fatal`() = runTest {
        val engine = ScriptedEngine(
            listOf(Result.success("Hallo"), Result.failure(SttFailure(SttFailure.NOTHING_RECOGNIZED, "silence")), Result.success("du")),
        )
        val chunked = ChunkedTranscription(engine, TranscribeOptions(), this)
        chunked.submit(audio(10))
        chunked.submit(audio(10))
        assertEquals("Hallo du", chunked.finish(audio(10), audio(30)).getOrThrow().text)
    }

    @Test
    fun `a real failure falls back to the whole recording in one go`() = runTest {
        val engine = ScriptedEngine(listOf(Result.success("Hallo"), Result.failure(SttFailure(Problem.RecognitionFailed, "x"))))
        val chunked = ChunkedTranscription(engine, TranscribeOptions(languageTag = "de"), this)
        chunked.submit(audio(10))
        chunked.submit(audio(10))

        val transcript = chunked.finish(tail = null, whole = audio(99)).getOrThrow()
        assertEquals("whole", transcript.text)
        assertEquals(99, engine.calls.last().first)
        assertEquals("de", engine.calls.last().second.languageTag)
    }

    @Test
    fun `a language name instead of a code is not locked`() = runTest {
        val engine = ScriptedEngine(listOf(Result.success("a"), Result.success("b")), language = "german")
        val chunked = ChunkedTranscription(engine, TranscribeOptions(languageTag = "de-DE"), this)
        chunked.submit(audio(10))
        chunked.finish(audio(10), audio(20))
        assertEquals("de-DE", engine.calls[1].second.languageTag)
    }

    @Test
    fun `nothing in any chunk is reported as nothing recognized`() = runTest {
        val silent = Result.failure<String>(SttFailure(SttFailure.NOTHING_RECOGNIZED, "silence"))
        val chunked = ChunkedTranscription(ScriptedEngine(listOf(silent, silent)), TranscribeOptions(), this)
        chunked.submit(audio(10))
        val failure = assertIs<SttFailure>(chunked.finish(audio(10), audio(20)).exceptionOrNull())
        assertTrue(failure.nothingRecognized)
    }

    /** Recognizes nothing; its one call runs until cancelled, then notes that it stopped. */
    private class EndlessEngine : SttEngine {
        override val id = "test"
        override val displayName = "Test"
        override val capabilities = EngineCapabilities(TrustLevel.ON_DEVICE, chunkable = true)
        var started = false
        var stopped = false

        override suspend fun transcribe(audio: PcmAudio, options: TranscribeOptions): Result<Transcript> {
            started = true
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
    }

    @Test
    fun `a dictation turned into a command waits until the chunk in flight has stopped`() = runTest {
        val engine = EndlessEngine()
        val chunked = ChunkedTranscription(engine, TranscribeOptions(), backgroundScope)
        chunked.submit(audio(10))
        runCurrent()
        assertTrue(engine.started)

        chunked.cancelAndJoin()
        assertTrue(engine.stopped, "the engine is free for the command's own passes")
    }
}
