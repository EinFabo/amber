package app.nocturne.dictation

import app.nocturne.core.insert.InsertionStrategy
import app.nocturne.core.text.Transcript

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

    data class Failed(val message: String) : DictationState
}
