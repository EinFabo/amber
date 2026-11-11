package app.amethyst.core.text

import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.WritingStyle

/**
 * A chat model that tidies a transcript: [ChatCleanup] for every server that speaks the OpenAI
 * dialect, [ClaudeCleanup] for Claude. Both send the same prompt and worked examples, and both
 * hand the reply to [CleanupGuard]; any failure leaves the caller with the original transcript.
 */
interface TextCleanup {

    /** The chat model's id, for the log. */
    val model: String

    data class Cleaned(val text: String, val latencyMs: Long, val trust: TrustLevel)

    /** Opens the connection while the user is still speaking; true when it is open, silent otherwise. */
    suspend fun warmUp(): Boolean

    /**
     * [style] and [instruction] come from the target app's profile; the default only tidies.
     * [generalInstruction] is the user's own addition for every app. [language] is the
     * dictation's language as far as the caller knows it ("de", "en"; see [LanguageGuess]):
     * the prompt names it, and null keeps the general language rule.
     * [onProgress] hears how much of the reply has come (0..1, against the dictation's length)
     * while a streamed reply arrives; a reply in one piece never calls it.
     */
    suspend fun clean(
        text: String,
        style: WritingStyle = WritingStyle.AS_SPOKEN,
        instruction: String? = null,
        generalInstruction: String? = null,
        language: String? = null,
        onProgress: ((Float) -> Unit)? = null,
    ): Result<Cleaned>

    companion object {
        /** What a connection test of a cleanup-only server sends, as if dictated: cheap, and nothing private. */
        const val TEST_DICTATION = "okay thanks"
    }
}
