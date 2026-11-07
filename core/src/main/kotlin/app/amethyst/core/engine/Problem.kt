package app.amethyst.core.engine

/**
 * Why a dictation, a cleanup or a connection failed - what the user is told, as a value. The
 * sentence for each lives in the app's string resources (English and German), picked by
 * `ui/Texts.kt`; :core only says which one and fills in the names. [technical] in [SttFailure]
 * stays the English detail for the log.
 */
sealed interface Problem {
    /** The audio reached the recognition and it found no speech. */
    data object NothingRecognized : Problem

    data class NoApiKey(val service: String) : Problem
    data class AnswerUnreadable(val service: String) : Problem
    data class NoConnection(val service: String) : Problem
    data class NoAnswerInTime(val service: String) : Problem
    data object KeyRejected : Problem

    /** HTTP 404: the address is wrong. */
    data object AddressNotFound : Problem
    data object RecordingTooLong : Problem
    data object AudioFormatRefused : Problem
    data object RateLimited : Problem
    data class ServiceDown(val code: Int) : Problem
    data class ServerError(val code: Int, val service: String) : Problem

    data object OutOfMemory : Problem
    data object LocalModelFailed : Problem
    data object ProcessorTooOld : Problem
    data object WhisperModelMissing : Problem
    data object ParakeetModelMissing : Problem
    data object NotSupported : Problem
    data class NotReady(val service: String, val issues: List<ConfigIssue>) : Problem
    data class RecordsByItself(val service: String) : Problem

    /** [payload] is what was kept back: the recording, or a text for the cleanup. */
    data class NotToVerifiedEnclave(val payload: Payload) : Problem
    data class EnclaveKeyMismatch(val service: String, val payload: Payload) : Problem
    data class EnclaveNotVerified(val service: String, val payload: Payload) : Problem
    data object EnclaveOtherAddress : Problem
    data object EnclaveCannotBind : Problem
    data object UnencryptedRefused : Problem

    data class CleanupFailed(val code: Int) : Problem
    data object CleanupUnreadable : Problem
    data object CleanupCutOff : Problem

    /** [reason] is the guard's verdict, an English word or two for the log ("negation dropped"). */
    data class CleanupChangedTooMuch(val reason: String) : Problem

    // Android's own recognizer.
    data object NoSystemRecognizer : Problem
    data object LanguageNotInstalled : Problem
    data object MicrophoneUnreadable : Problem
    data object MicrophonePermissionMissing : Problem
    data object RecognizerOffline : Problem
    data object NoSpeechHeard : Problem
    data object RecognizerBusy : Problem
    data object RecognizerCrashed : Problem
    data object RecognitionFailed : Problem

    enum class Payload { RECORDING, TEXT }
}

/** What a speech recognition's settings still lack, from [EngineConfigValidator]. */
sealed interface ConfigIssue {
    data object NoModelSelected : ConfigIssue
    data object NotSupported : ConfigIssue
    data object ModelNameMissing : ConfigIssue
    data object ApiKeyMissing : ConfigIssue

    /** Tinfoil sends only to the address whose enclave Amber can verify. */
    data class EnclaveAddressOnly(val address: String) : ConfigIssue
    data class InvalidLanguageCode(val tag: String) : ConfigIssue
    data object AddressMissing : ConfigIssue

    /** [schemes] such as "http://" and "https://". */
    data class AddressScheme(val schemes: List<String>) : ConfigIssue
    data object AddressNoHost : ConfigIssue
    data object AddressHasSpace : ConfigIssue
    data object HttpOnlyInOwnNetwork : ConfigIssue
}

/**
 * Why a model download or import failed, worded by the app. Carried by [ModelFileException];
 * any other error during a download reads as [Other].
 */
sealed interface FileProblem {
    data class Incomplete(val received: Long, val expected: Long) : FileProblem
    data object ChecksumMismatch : FileProblem
    data object CouldNotSave : FileProblem
    data object Interrupted : FileProblem
    data object NoModelInstalled : FileProblem

    /** [freeMb] when known. */
    data class NotEnoughStorage(val neededMb: Long, val freeMb: Long?) : FileProblem
    data class ServerAnswered(val code: Int) : FileProblem
    data object EmptyAnswer : FileProblem
    data object NoConnection : FileProblem
    data object CannotOpen : FileProblem
    data object TooSmallForWhisper : FileProblem
    data object NotWhisperCpp : FileProblem
    data object NotWhisper : FileProblem
    data object Other : FileProblem

    companion object {
        fun of(error: Throwable?): FileProblem = (error as? ModelFileException)?.problem ?: Other
    }
}

/** A model download or import that failed for [problem]; the message is the English detail for the log. */
class ModelFileException(val problem: FileProblem, detail: String = problem.toString(), cause: Throwable? = null) :
    IllegalStateException(detail, cause)
