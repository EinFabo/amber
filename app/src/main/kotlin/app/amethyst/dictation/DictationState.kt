package app.amethyst.dictation

import app.amethyst.core.engine.SttFailure
import app.amethyst.core.insert.InsertionStrategy
import app.amethyst.core.text.Transcript

sealed interface DictationState {

    data object Idle : DictationState

    /** Permissions checked, engine being built, microphone opening. */
    data object Starting : DictationState

    data class Listening(val amplitude: Float = 0f, val partial: String? = null) : DictationState

    /**
     * Turning the recording into text. [expectedMs] is how long that should take, learned from
     * earlier dictations, for the progress ring; null when there is nothing to go by.
     */
    data class Transcribing(val expectedMs: Long? = null) : DictationState

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
