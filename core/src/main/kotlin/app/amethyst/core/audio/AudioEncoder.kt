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

        /**
         * Whether [bytes] open as an Ogg Opus file must: an Ogg page ("OggS") whose first packet
         * is the Opus identification header ("OpusHead"). The packet follows the page's 27-byte
         * header and its lacing values - one for Android's encoder, so it starts at byte 28.
         */
        fun isOggOpus(bytes: ByteArray): Boolean {
            if (bytes.size < OGG_PAGE_HEADER || !bytes.hasAt(0, OGG_MAGIC)) return false
            val lacing = bytes[OGG_PAGE_HEADER - 1].toInt() and 0xFF
            return bytes.hasAt(OGG_PAGE_HEADER + lacing, OPUS_HEAD)
        }

        private const val OGG_PAGE_HEADER = 27
        private const val OGG_MAGIC = "OggS"
        private const val OPUS_HEAD = "OpusHead"

        private fun ByteArray.hasAt(offset: Int, ascii: String): Boolean =
            offset + ascii.length <= size && ascii.indices.all { this[offset + it] == ascii[it].code.toByte() }
    }
}
