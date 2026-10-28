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
)
