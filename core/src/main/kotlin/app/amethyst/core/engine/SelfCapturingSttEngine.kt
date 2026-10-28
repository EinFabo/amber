package app.amethyst.core.engine

import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.text.Transcript

/**
 * An engine that records the microphone itself and never hands out the audio.
 *
 * Android's own SpeechRecognizer works this way: it takes no PCM buffer, it opens the
 * microphone on its own and returns text. Rather than pretending it fits [SttEngine.transcribe],
 * such engines get their own entry point, and the dictation controller branches on the type.
 */
interface SelfCapturingSttEngine : SttEngine {

    /** Records until the recognizer decides it is done, or until [requestStop]. */
    suspend fun listen(
        options: TranscribeOptions = TranscribeOptions(),
        callbacks: ListenCallbacks = ListenCallbacks(),
    ): Result<Transcript>

    /** Asks the recognizer to finish and deliver what it has. */
    fun requestStop()

    override suspend fun transcribe(audio: PcmAudio, options: TranscribeOptions): Result<Transcript> =
        Result.failure(
            SttFailure(
                userMessage = "\"$displayName\" nimmt selbst auf und kann keine Aufnahme entgegennehmen.",
                technical = "$id is self-capturing; call listen() instead of transcribe()",
            ),
        )
}

/** Live feedback while a self-capturing engine is listening. */
class ListenCallbacks(
    /** Normalised 0f..1f loudness, for the bubble's amplitude ring. */
    val onAmplitude: (Float) -> Unit = {},
    /** Interim text, shown but never inserted. */
    val onPartial: (String) -> Unit = {},
)
