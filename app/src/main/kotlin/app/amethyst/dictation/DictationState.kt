package app.amethyst.dictation

import app.amethyst.core.engine.SttFailure
import app.amethyst.core.engine.WaitProgress
import app.amethyst.core.insert.InsertionStrategy
import app.amethyst.core.text.Transcript

sealed interface DictationState {

    data object Idle : DictationState

    /** Permissions checked, engine being built, microphone opening. */
    data object Starting : DictationState

    /** [paused]: from the bubble's menu; the microphone stays open, nothing is kept until it goes on. */
    data class Listening(
        val amplitude: Float = 0f,
        val partial: String? = null,
        val paused: Boolean = false,
        /**
         * In the last stretch before the longest recording: what is left of it, 1f down to 0f
         * ([app.amethyst.core.capture.RecordingLimit]); null before it, and while a recognizer
         * that listens by itself runs.
         */
        val limitLeft: Float? = null,
    ) : DictationState

    /**
     * Turning the recording into text, and cleaning it. [progress] is how far that has come,
     * for the progress ring; null when there is nothing to go by.
     */
    data class Transcribing(val progress: WaitProgress? = null) : DictationState

    data class Done(
        val transcript: Transcript,
        val strategy: InsertionStrategy,
        val insertedIntoApp: String?,
    ) : DictationState

    /** A voice command was carried out: [heard] as recognized, [did] what came of it. */
    data class Commanded(val heard: String, val did: String) : DictationState

    data class Failed(
        val message: String,
        val remedy: SttFailure.Remedy? = null,
    ) : DictationState
}
