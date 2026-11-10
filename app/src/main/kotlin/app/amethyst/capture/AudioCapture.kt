package app.amethyst.capture

import app.amethyst.R
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import app.amethyst.core.audio.DiscardReason
import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.audio.StopReason
import app.amethyst.core.audio.VadConfig
import app.amethyst.core.audio.VadDecision
import app.amethyst.core.audio.VadPolicy
import app.amethyst.core.capture.ChunkPlanner
import app.amethyst.core.capture.PausableRecording
import app.amethyst.core.capture.PcmAccumulator
import app.amethyst.core.capture.RecordingPause
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
        /** [message]: a string resource for the user; [technical] for the log. */
        data class Failed(val message: Int, val technical: String) : Outcome
    }

    @SuppressLint("MissingPermission")
    suspend fun record(
        config: VadConfig,
        stopRequested: () -> Boolean,
        onAmplitude: (Float) -> Unit,
        /**
         * How long the recording is so far (ms, paused time not counted), after each frame
         * kept and before its [onAmplitude] - for the sign that the longest recording is near.
         */
        onRecorded: (Long) -> Unit = {},
        /**
         * Receives each finished part of a running recording, cut at a pause, so it can be
         * recognized while the user goes on speaking. Null records in one piece.
         */
        onChunk: ((PcmAudio) -> Unit)? = null,
        /** The shortest chunk worth cutting off; see EngineCapabilities.minChunkMs. */
        minChunkMs: Int = 4_000,
        /**
         * Paused from the bubble's menu: the microphone stays open, what it hears is dropped
         * ([PausableRecording]) and the recording goes on seamlessly after it.
         */
        pause: RecordingPause = RecordingPause(),
    ): Outcome = withContext(Dispatchers.IO) {
        val frameSamples = sampleRate * config.frameMs / 1000
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            return@withContext Outcome.Failed(
                R.string.capture_no_16khz,
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
            return@withContext Outcome.Failed(R.string.capture_not_opened, e.toString())
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@withContext Outcome.Failed(
                R.string.capture_busy,
                "AudioRecord state ${recorder.state}",
            )
        }

        val vad = VadPolicy(config)
        val gate = PausableRecording(vad, pause, clock = SystemClock::elapsedRealtime)
        val recorded = PcmAccumulator(sampleRate)
        val planner = onChunk?.let { ChunkPlanner(sampleRate, config.frameMs, minChunkMs = minChunkMs) }
        var chunks = 0
        val frame = ShortArray(frameSamples)

        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return@withContext Outcome.Failed(
                    R.string.capture_refused,
                    "recordingState ${recorder.recordingState} - likely a background mic block",
                )
            }

            while (currentCoroutineContext().isActive) {
                val read = recorder.read(frame, 0, frameSamples)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_DEAD_OBJECT) {
                        return@withContext Outcome.Failed(
                            R.string.capture_interrupted,
                            "AudioRecord.read returned $read",
                        )
                    }
                    continue
                }

                val rms = PcmAudio.rms(frame, 0, read)
                val decision = when (val step = gate.onFrame(rms, stopRequested())) {
                    // Paused: never stored, never chunked, the level shown as silence.
                    PausableRecording.Step.Drop -> {
                        onAmplitude(0f)
                        continue
                    }
                    is PausableRecording.Step.End -> step.decision
                    is PausableRecording.Step.Keep -> {
                        recorded.append(frame, read)
                        onRecorded(recorded.durationMs)
                        onAmplitude(PcmAccumulator.normalizeLoudness(rms))
                        planner?.onFrame(recorded.sampleCount, rms, speech = rms > vad.speechThreshold)?.let { chunk ->
                            onChunk?.invoke(recorded.slice(chunk.start, chunk.end))
                            chunks++
                        }
                        step.decision
                    }
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
