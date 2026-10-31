package app.amethyst.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import app.amethyst.core.audio.AudioEncoder
import app.amethyst.core.audio.EncodedAudio
import app.amethyst.core.audio.PcmAudio
import java.io.File

/**
 * Ogg Opus for an Amber server that takes it: Android's own Opus encoder (MediaCodec) and Ogg
 * muxer (MediaMuxer), both there from Android 10. Mono, 24 kbit/s, the encoder's 20 ms frames -
 * about a seventh to a tenth of the WAV with the Ogg framing, and whisper hears no difference.
 *
 * Null whenever it cannot - Android 9, the main thread, an encoder that fails, output that is
 * not Ogg Opus - and the WAV goes up as always. MediaMuxer writes only to a file: the recording
 * is in the app's own cache for the milliseconds encoding takes, and deleted right after,
 * failed or not; a file a dying process left behind goes at the next start ([clearLeftovers]).
 */
class OpusEncoder(private val context: Context) : AudioEncoder {

    /** One encoding at a time, so the scratch file is never shared and a leftover can go. */
    private val lock = Any()

    override fun encode(audio: PcmAudio): EncodedAudio? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        // Encoding blocks; on the main thread the WAV is the better answer.
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        if (audio.channels != 1 || audio.sampleRate !in SAMPLE_RATES || audio.samples.isEmpty()) return null
        return synchronized(lock) {
            try {
                encodeOgg(audio)?.let(EncodedAudio::oggOpus)
            } catch (e: Exception) {
                Log.w(TAG, "Opus encoding failed, the recording goes up as WAV", e)
                null
            }
        }
    }

    /** Deletes what a process that died mid-encode left behind. Blocking: not on the main thread. */
    fun clearLeftovers() {
        synchronized(lock) { scratchDir().listFiles()?.forEach { it.delete() } }
    }

    private fun scratchDir() = File(context.cacheDir, SCRATCH_DIR)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun encodeOgg(audio: PcmAudio): ByteArray? {
        val dir = scratchDir().apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "upload.ogg")
        val pcm = littleEndian(audio.samples)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, audio.sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        var muxer: MediaMuxer? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val output = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            muxer = output
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            var fed = 0
            var inputDone = false
            var track = -1
            var outputDone = false
            while (!outputDone) {
                if (SystemClock.elapsedRealtime() > deadline) return null
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(WAIT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index) ?: return null
                        buffer.clear()
                        // Whole samples only.
                        val size = minOf(buffer.remaining() and 1.inv(), pcm.size - fed)
                        buffer.put(pcm, fed, size)
                        val timeUs = fed / 2 * 1_000_000L / audio.sampleRate
                        fed += size
                        inputDone = fed >= pcm.size
                        codec.queueInputBuffer(index, 0, size, timeUs, if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                }
                // Everything that is ready; once all input is in, wait for the rest.
                while (true) {
                    if (SystemClock.elapsedRealtime() > deadline) return null
                    val index = codec.dequeueOutputBuffer(info, if (inputDone) WAIT_US else 0)
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // The format carries the Opus header (csd-0) the Ogg file starts with;
                        // a started muxer takes no other.
                        if (track < 0) {
                            track = output.addTrack(codec.outputFormat)
                            output.start()
                        }
                        continue
                    }
                    if (index < 0) continue
                    val buffer = codec.getOutputBuffer(index) ?: return null
                    val header = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!header && info.size > 0) {
                        if (track < 0) return null
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        output.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                        break
                    }
                }
            }
            if (track < 0) return null
            output.stop()
            output.release()
            muxer = null
            // What the server was promised: an Ogg page that opens with the Opus header.
            return file.readBytes().takeIf(EncodedAudio::isOggOpus)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            // Left early (deadline, a failure): a started muxer is stopped first, and released
            // either way - stop throws if it never started, which changes nothing here.
            muxer?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            file.delete()
        }
    }

    private fun littleEndian(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private companion object {
        const val TAG = "Amber"

        /**
         * Amber's phone API recommends 24 kbit/s: under a tenth of 16 kHz WAV (256 kbit/s)
         * before the Ogg framing, and plenty for speech.
         */
        const val BIT_RATE = 24_000

        /** The input rates Android's Opus encoder takes; capture records 16 kHz. */
        val SAMPLE_RATES = setOf(8_000, 12_000, 16_000, 24_000, 48_000)

        const val WAIT_US = 10_000L

        /** A two-minute recording encodes in well under a second; past this, WAV is quicker. */
        const val TIMEOUT_MS = 5_000L

        const val SCRATCH_DIR = "opus-upload"
    }
}
