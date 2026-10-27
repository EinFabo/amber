package app.nocturne.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import app.nocturne.core.audio.DiscardReason
import app.nocturne.core.audio.PcmAudio
import app.nocturne.core.audio.StopReason
import app.nocturne.core.audio.VadConfig
import app.nocturne.core.audio.VadDecision
import app.nocturne.core.audio.VadPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.log10

/**
 * Records 16 kHz mono PCM and asks [VadPolicy] after every frame whether to stop.
 *
 * VOICE_RECOGNITION is the source rather than MIC: it applies the platform's noise
 * suppression and skips the AGC ramp that makes the first word quiet.
 */
class AudioCapture(private val sampleRate: Int = PcmAudio.DEFAULT_SAMPLE_RATE) {

    sealed interface Outcome {
        data class Captured(val audio: PcmAudio, val reason: StopReason) : Outcome
        data class Discarded(val reason: DiscardReason) : Outcome
        data class Failed(val message: String, val technical: String) : Outcome
    }

    @SuppressLint("MissingPermission")
    suspend fun record(
        config: VadConfig,
        stopRequested: () -> Boolean,
        onAmplitude: (Float) -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        val frameSamples = sampleRate * config.frameMs / 1000
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            return@withContext Outcome.Failed(
                "Das Mikrofon unterstützt 16 kHz nicht.",
                "getMinBufferSize returned $minBufferSize",
            )
        }

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBufferSize, frameSamples * 2 * 8),
            )
        } catch (e: Exception) {
            return@withContext Outcome.Failed("Mikrofon konnte nicht geöffnet werden.", e.toString())
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@withContext Outcome.Failed(
                "Mikrofon ist belegt oder nicht freigegeben.",
                "AudioRecord state ${recorder.state}",
            )
        }

        val vad = VadPolicy(config)
        val chunks = ArrayList<ShortArray>()
        val frame = ShortArray(frameSamples)
        var totalSamples = 0

        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return@withContext Outcome.Failed(
                    "Aufnahme wurde vom System verweigert.",
                    "recordingState ${recorder.recordingState} - likely a background mic block",
                )
            }

            while (currentCoroutineContext().isActive) {
                val read = recorder.read(frame, 0, frameSamples)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_DEAD_OBJECT) {
                        return@withContext Outcome.Failed(
                            "Mikrofon wurde unterbrochen.",
                            "AudioRecord.read returned $read",
                        )
                    }
                    continue
                }

                chunks.add(frame.copyOf(read))
                totalSamples += read

                val rms = PcmAudio.rms(frame, 0, read)
                onAmplitude(normalizeAmplitude(rms))

                val decision = if (stopRequested()) vad.onManualStop() else vad.onFrame(rms)
                when (decision) {
                    is VadDecision.Continue -> Unit
                    is VadDecision.Discard -> return@withContext Outcome.Discarded(decision.reason)
                    is VadDecision.Stop -> return@withContext Outcome.Captured(
                        PcmAudio(flatten(chunks, totalSamples), sampleRate),
                        decision.reason,
                    )
                }
            }
            // Cancelled from outside: treat it as nothing was said.
            Outcome.Discarded(DiscardReason.NO_SPEECH)
        } finally {
            runCatching { recorder.stop() }.onFailure { Log.w(TAG, "stop failed", it) }
            recorder.release()
        }
    }

    private fun flatten(chunks: List<ShortArray>, total: Int): ShortArray {
        val out = ShortArray(total)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }

    /** Log scale, because a linear RMS ring barely moves for normal speech. */
    private fun normalizeAmplitude(rms: Double): Float {
        if (rms <= 1.0) return 0f
        return (log10(rms) / log10(MAX_PCM_VALUE)).toFloat().coerceIn(0f, 1f)
    }

    private companion object {
        const val TAG = "Nocturne"
        const val MAX_PCM_VALUE = 32_768.0
    }
}
