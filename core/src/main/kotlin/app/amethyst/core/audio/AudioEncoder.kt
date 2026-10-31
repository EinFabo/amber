package app.amethyst.core.audio

/**
 * Compresses a recording for its upload. An interface, so :core stays pure JVM: the real one
 * (Ogg Opus through Android's MediaCodec) lives in :app.
 */
fun interface AudioEncoder {
    /**
     * [audio] compressed, or null when this phone or this recording cannot be - then the WAV
     * goes up as always. Blocking, so never called on the main thread.
     */
    fun encode(audio: PcmAudio): EncodedAudio?
}

/** A compressed recording as it goes into the upload's `file` part. */
class EncodedAudio(val bytes: ByteArray, val fileName: String, val mimeType: String) {
    companion object {
        /**
         * Opus in an Ogg container, mono - what a server's list calls `opus` (Amber's phone API,
         * "Compressed uploads"). The server sniffs the first bytes and resamples by itself.
         */
        fun oggOpus(bytes: ByteArray) = EncodedAudio(bytes, "dictation.ogg", "audio/ogg")
    }
}
