package app.amethyst.core.engine

import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.text.Transcript
import kotlinx.coroutines.CoroutineScope
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

    private val queue = Channel<PcmAudio>(Channel.UNLIMITED)
    private val texts = mutableListOf<String>()
    private var lockedLanguage: String? = null
    private var weakestTrust: TrustLevel? = null
    private var servedBy: String? = null
    private var failure: Throwable? = null

    /** Chunks handed over so far, including the final one; for the diagnostics. */
    var chunkCount: Int = 0
        private set

    private val worker = scope.launch {
        for (chunk in queue) {
            if (failure == null) transcribe(chunk)
        }
    }

    /** Queues a finished chunk. Safe to call from the recording thread. */
    fun submit(chunk: PcmAudio) {
        chunkCount++
        queue.trySend(chunk)
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

        val text = join(texts)
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

    private suspend fun transcribe(chunk: PcmAudio) {
        val chunkOptions = options.copy(
            languageTag = lockedLanguage ?: options.languageTag,
            precedingText = join(texts).takeLast(PROMPT_CHARS).ifBlank { null },
        )
        engine.transcribe(chunk, chunkOptions).fold(
            onSuccess = { transcript ->
                texts += transcript.text
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
    }
}
