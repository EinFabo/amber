package app.amethyst.core.text

import app.amethyst.core.privacy.TrustLevel

/** What an engine returns for one dictation. */
data class Transcript(
    val text: String,
    val engineId: String,
    val trustLevel: TrustLevel,
    val latencyMs: Long,
    /** False for interim results of a streaming engine; only final results get inserted. */
    val isFinal: Boolean = true,
    val detectedLanguage: String? = null,
    /** The enclave that answered, as named by the service (Tinfoil: the Tinfoil-Enclave header). */
    val servedBy: String? = null,
    /**
     * Rewritten by a cleanup model, so its line breaks are meant (paragraphs of an email, a
     * list in a note). An engine's raw output has none worth keeping.
     */
    val formatted: Boolean = false,
)
