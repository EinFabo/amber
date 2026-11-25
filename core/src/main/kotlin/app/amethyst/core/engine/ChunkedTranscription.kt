package app.amethyst.core.engine

import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.text.Abbreviations
import app.amethyst.core.text.CommonWords
import app.amethyst.core.text.LanguageGuess
import app.amethyst.core.text.Transcript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Transcribes a recording chunk by chunk while it is still being made, so that when the user
 * stops only the last chunk is left to wait for.
 *
 * Chunks are processed one after another, in order, on [scope]: each gets the text recognized
 * so far as its prompt (sentence flow and spellings carry across the cut), and the language the
 * first chunk was recognized in is kept for the rest, so a dictation cannot switch languages
 * halfway. A chunk in which nothing was recognized - a cough, a pause - is simply skipped.
 *
 * Any other failure makes the chunks worthless as a set, so [finish] then transcribes the whole
 * recording in one go: slower, but never worse than not chunking at all.
 */
class ChunkedTranscription(
    private val engine: SttEngine,
    private val options: TranscribeOptions,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Told the audio still pending ([pendingAudioMs]) each time a chunk is done - for the progress ring. */
    @Volatile
    var onChunkDone: ((pendingMs: Long) -> Unit)? = null

    private class Queued(val audio: PcmAudio, val pauseAfterMs: Int)

    private val queue = Channel<Queued>(Channel.UNLIMITED)
    private val texts = mutableListOf<String>()

    /** For each text, how long the pause was at the cut in front of it ([join]). */
    private val pausesBefore = mutableListOf<Int>()

    /** The pause at the cut in front of the chunk being recognized; only the worker touches it. */
    private var pauseBefore = 0

    /** A chunk before the one being recognized held nothing: the words after it follow a pause. */
    private var skipped = false
    private var lockedLanguage: String? = null
    private var weakestTrust: TrustLevel? = null
    private var servedBy: String? = null
    private var failure: Throwable? = null

    /** Audio handed over but not transcribed yet: what is still to wait for. */
    private val pendingMs = java.util.concurrent.atomic.AtomicLong(0)

    /** Milliseconds of audio queued or being transcribed right now. */
    val pendingAudioMs: Long get() = pendingMs.get()

    /** Chunks handed over so far, including the final one; for the diagnostics. */
    var chunkCount: Int = 0
        private set

    private val worker = scope.launch {
        for (queued in queue) {
            val chunk = queued.audio
            if (failure == null) transcribe(chunk)
            pauseBefore = queued.pauseAfterMs
            val left = pendingMs.addAndGet(-chunk.durationMs)
            onChunkDone?.invoke(left)
        }
    }

    /**
     * Queues a finished chunk; [pauseAfterMs] is the pause the recording was cut at behind it
     * (`ChunkPlanner.Chunk.pauseMs`), 0 for the last chunk and for a cut in speech.
     * Safe to call from the recording thread.
     */
    fun submit(chunk: PcmAudio, pauseAfterMs: Int = 0) {
        chunkCount++
        pendingMs.addAndGet(chunk.durationMs)
        queue.trySend(Queued(chunk, pauseAfterMs))
    }

    /**
     * Adds the last chunk ([tail], null when it holds no speech), waits for everything and
     * joins the result. Its latency is the wait after the user stopped - the number that
     * chunking exists to shrink. [whole] is the full recording, used only if a chunk failed.
     */
    suspend fun finish(tail: PcmAudio?, whole: PcmAudio): Result<Transcript> {
        val stoppedAt = clock()
        tail?.let(::submit)
        queue.close()
        worker.join()

        failure?.let { return engine.transcribe(whole, options) }

        val text = join(texts, pausesBefore, LanguageGuess.forSeam(texts.joinToString(" "), lockedLanguage, options.languageTag))
        if (text.isBlank()) {
            return Result.failure(SttFailure(SttFailure.NOTHING_RECOGNIZED, "no chunk contained speech"))
        }
        return Result.success(
            Transcript(
                text = text,
                engineId = engine.id,
                trustLevel = weakestTrust ?: engine.capabilities.trustLevel,
                latencyMs = clock() - stoppedAt,
                detectedLanguage = lockedLanguage,
                servedBy = servedBy,
            ),
        )
    }

    /** Stops the worker without a result, e.g. when the recording was discarded. */
    fun cancel() {
        queue.close()
        worker.cancel()
    }

    /**
     * [cancel], and waits until a chunk being recognized has stopped - before the engine gets
     * other work, e.g. a dictation a double tap turned into a command: an abort meant for the
     * chunk must not land on the command.
     */
    suspend fun cancelAndJoin() {
        queue.close()
        worker.cancelAndJoin()
    }

    private suspend fun transcribe(chunk: PcmAudio) {
        val chunkOptions = options.copy(
            languageTag = lockedLanguage ?: options.languageTag,
            precedingText = join(texts).takeLast(PROMPT_CHARS).ifBlank { null },
        )
        engine.transcribe(chunk, chunkOptions).fold(
            onSuccess = { transcript ->
                texts += transcript.text
                pausesBefore += if (skipped) LONG_PAUSE_MS else pauseBefore
                skipped = false
                // Only a plain code ("de") is locked; some services report names ("german").
                if (lockedLanguage == null) {
                    lockedLanguage = transcript.detectedLanguage?.lowercase()?.takeIf { LANGUAGE_CODE.matches(it) }
                }
                servedBy = transcript.servedBy ?: servedBy
                // A dictation is only as trustworthy as its least trusted part.
                weakestTrust = listOfNotNull(weakestTrust, transcript.trustLevel).maxByOrNull { it.strictness }
            },
            onFailure = { error ->
                if ((error as? SttFailure)?.nothingRecognized != true) failure = error
                // A chunk with nothing in it was a silence: the next words follow a pause.
                else skipped = true
            },
        )
    }

    companion object {
        /** Whisper's prompt holds about 224 tokens; the end of the text is what matters. */
        const val PROMPT_CHARS = 200

        private val LANGUAGE_CODE = Regex("^[a-z]{2,3}$")

        /** Joins chunk texts with single spaces; a chunk never starts with stray punctuation. */
        fun join(parts: List<String>): String =
            parts.map { it.trim() }
                .filter { it.isNotEmpty() }
                .fold("") { acc, part ->
                    when {
                        acc.isEmpty() -> part
                        part.first() in ",.;:!?" -> acc + part
                        else -> "$acc $part"
                    }
                }

        /** A pause this long ends a sentence: the cut there keeps its full stop and capital. */
        const val LONG_PAUSE_MS = 800

        /**
         * [join] for chunks cut at known pauses: [pausesMs] holds, for each part, the pause at
         * the cut in front of it (the first one is not looked at). A cut at a breath - under
         * [LONG_PAUSE_MS] - is often inside a sentence: when the part before ends in a plain
         * "." (no abbreviation's, no "...") and this one starts with a small word of [language]
         * ([CommonWords]), the "." goes and the word is written lower-case ("Ich wollte dir
         * sagen." + "Dass ich komme." - no comma is guessed). After a pause, or before any other
         * word, both stay.
         */
        fun join(parts: List<String>, pausesMs: List<Int>, language: String?): String {
            val kept = parts.indices.filter { parts[it].isNotBlank() }
            var text = ""
            for ((n, i) in kept.withIndex()) {
                val part = parts[i].trim()
                text = when {
                    n == 0 -> part
                    part.first() in ",.;:!?" -> text + part
                    else -> {
                        val pause = pausesMs.getOrElse(i) { LONG_PAUSE_MS }
                        val lowered = if (pause < LONG_PAUSE_MS && endsPlainSentence(text)) CommonWords.lowerLeading(part, language) else part
                        if (lowered != part) text.dropLast(1) + " " + lowered else "$text $part"
                    }
                }
            }
            return text
        }

        private fun endsPlainSentence(text: String): Boolean =
            text.endsWith('.') && !text.endsWith("..") && !text.endsWith("…") && !Abbreviations.endsWith(text)
    }
}
