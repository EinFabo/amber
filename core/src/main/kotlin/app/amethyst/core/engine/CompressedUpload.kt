package app.amethyst.core.engine

import app.amethyst.core.audio.AudioEncoder
import app.amethyst.core.audio.EncodedAudio
import app.amethyst.core.audio.PcmAudio
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How a recording went up, for the dictation's line in the diagnostics: the format and its size,
 * and whether the server refused Opus first - never where it went.
 */
data class UploadReport(val oggOpus: Boolean, val bytes: Int, val opusRefused: Boolean = false) {
    /** "upload Ogg Opus 34 KB", "upload WAV 263 KB, the server refused Opus". */
    fun summary(): String = buildString {
        append("upload ").append(if (oggOpus) "Ogg Opus" else "WAV").append(' ')
        append(if (bytes < 1024) "$bytes B" else "${(bytes + 512) / 1024} KB")
        if (opusRefused) append(", the server refused Opus")
    }
}

/**
 * Compressing an engine's recordings before they go up: about a seventh to a tenth of the WAV,
 * which is where the time goes on a weak uplink. Only for a server whose list takes it - see
 * [AmberCatalogs.compressedUpload]. Whatever goes wrong, the WAV goes up instead.
 */
class CompressedUpload(
    private val encoder: AudioEncoder,
    /** The server answered a compressed upload with 400; called once. */
    private val onRefused: () -> Unit = {},
) {
    private val refused = AtomicBoolean(false)

    /** [audio] compressed, or null for WAV: refused before, or the encoder could not. Blocking. */
    fun encode(audio: PcmAudio): EncodedAudio? {
        if (refused.get()) return null
        return try {
            encoder.encode(audio)?.takeIf { it.bytes.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /** The server would not take the compressed upload: WAV from now on. */
    fun refused() {
        if (refused.compareAndSet(false, true)) onRefused()
    }
}
