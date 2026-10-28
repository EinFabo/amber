package app.amethyst.dictation

import app.amethyst.core.engine.SttFailure
import app.amethyst.core.insert.InsertionStrategy
import app.amethyst.core.text.Transcript

sealed interface DictationState {

    data object Idle : DictationState

    /** Permissions checked, engine being built, microphone opening. */
    data object Starting : DictationState

    data class Listening(val amplitude: Float = 0f, val partial: String? = null) : DictationState

    data object Transcribing : DictationState

    data class Done(
        val transcript: Transcript,
        val strategy: InsertionStrategy,
        val insertedIntoApp: String?,
    ) : DictationState

    data class Failed(
        val message: String,
        val remedy: SttFailure.Remedy? = null,
    ) : DictationState
}
