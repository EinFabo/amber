package app.amethyst.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import app.amethyst.core.audio.DiscardReason
import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.audio.StopReason
import app.amethyst.core.audio.VadConfig
import app.amethyst.core.audio.VadDecision
import app.amethyst.core.audio.VadPolicy
import app.amethyst.core.capture.ChunkPlanner
import app.amethyst.core.capture.PcmAccumulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Records 16 kHz mono PCM and asks [VadPolicy] after every frame whether to stop.
 *
 * VOICE_RECOGNITION is the source rather than MIC: it applies the platform's noise
 * suppression and skips the AGC ramp that makes the first word quiet.
 */
class AudioCapture(private val sampleRate: Int = PcmAudio.DEFAULT_SAMPLE_RATE) {

    sealed interface Outcome {
        /**
         * [tail] is what came after the last chunk handed to `onChunk`; null when nothing was
         * chunked ([chunks] == 0) or when it holds no speech.
         */
        data class Captured(
            val audio: PcmAudio,
            val reason: StopReason,
            val tail: PcmAudio?,
            val chunks: Int,
        ) : Outcome
        data class Discarded(val reason: DiscardReason) : Outcome
        data class Failed(val message: String, val technical: String) : Outcome
    }

    @SuppressLint("MissingPermission")
    suspend fun record(
        config: VadConfig,
        stopRequested: () -> Boolean,
        onAmplitude: (Float) -> Unit,
        /**
         * Receives each finished part of a running recording, cut at a pause, so it can be
         * recognized while the user goes on speaking. Null records in one piece.
         */
        onChunk: ((PcmAudio) -> Unit)? = null,
        /** The shortest chunk worth cutting off; see EngineCapabilities.minChunkMs. */
        minChunkMs: Int = 4_000,
    ): Outcome = withContext(Dispatchers.IO) {
        val frameSamples = sampleRate * config.frameMs / 1000
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            return@withContext Outcome.Failed(
                "The microphone does not support 16 kHz.",
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
            return@withContext Outcome.Failed("The microphone could not be opened.", e.toString())
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@withContext Outcome.Failed(
                "The microphone is busy or not permitted.",
                "AudioRecord state ${recorder.state}",
            )
        }

        val vad = VadPolicy(config)
        val recorded = PcmAccumulator(sampleRate)
        val planner = onChunk?.let { ChunkPlanner(sampleRate, config.frameMs, minChunkMs = minChunkMs) }
        var chunks = 0
        val frame = ShortArray(frameSamples)

        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return@withContext Outcome.Failed(
                    "The system refused the recording.",
                    "recordingState ${recorder.recordingState} - likely a background mic block",
                )
            }

            while (currentCoroutineContext().isActive) {
                val read = recorder.read(frame, 0, frameSamples)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_DEAD_OBJECT) {
                        return@withContext Outcome.Failed(
                            "The microphone was interrupted.",
                            "AudioRecord.read returned $read",
                        )
                    }
                    continue
                }

                recorded.append(frame, read)

                val rms = PcmAudio.rms(frame, 0, read)
                onAmplitude(PcmAccumulator.normalizeLoudness(rms))

                val decision = if (stopRequested()) vad.onManualStop() else vad.onFrame(rms)
                planner?.onFrame(recorded.sampleCount, rms, speech = rms > vad.speechThreshold)?.let { chunk ->
                    onChunk?.invoke(recorded.slice(chunk.start, chunk.end))
                    chunks++
                }
                when (decision) {
                    is VadDecision.Continue -> Unit
                    is VadDecision.Discard -> return@withContext Outcome.Discarded(decision.reason)
                    is VadDecision.Stop -> return@withContext Outcome.Captured(
                        audio = recorded.build(),
                        reason = decision.reason,
                        tail = when {
                            planner == null || chunks == 0 -> null
                            planner.speechSinceCut -> recorded.slice(planner.chunkStart)
                            else -> null
                        },
                        chunks = chunks,
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

    private companion object {
        const val TAG = "Amber"
    }
}
