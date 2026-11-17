package app.amethyst.capture

import app.amethyst.R
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import app.amethyst.core.audio.DiscardReason
import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.audio.StopReason
import app.amethyst.core.audio.VadConfig
import app.amethyst.core.audio.VadDecision
import app.amethyst.core.audio.VadPolicy
import app.amethyst.core.capture.ChunkPlanner
import app.amethyst.core.capture.MicrophoneWatch
import app.amethyst.core.capture.PausableRecording
import app.amethyst.core.capture.PcmAccumulator
import app.amethyst.core.capture.RecordingPause
import app.amethyst.core.capture.StartToneMute
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
            /** Why it ended, for the log, when the microphone was lost ([MicrophoneWatch]). */
            val technical: String? = null,
        ) : Outcome
        data class Discarded(val reason: DiscardReason, val technical: String? = null) : Outcome
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
        /** The microphone is really open: the recorder records. On the recorder's thread. */
        onStarted: () -> Unit = {},
        /** The recording's first moments silenced, for the start tone that plays then ([StartToneMute]); 0 for none. */
        silenceFirstMs: Int = 0,
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
        val silence = SilencedWatch()
        val watch = MicrophoneWatch(config.frameMs, silenceKnown = silence.known)
        val toneMute = StartToneMute(sampleRate, silenceFirstMs)

        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return@withContext Outcome.Failed(
                    R.string.capture_refused,
                    "recordingState ${recorder.recordingState} - likely a background mic block",
                )
            }
            onStarted()

            silence.watch(recorder)
            // How the recording ends when the microphone is gone or a tap comes while it
            // delivers nothing: as a tap would end it, with what was recorded so far.
            fun ended(decision: VadDecision, technical: String? = null): Outcome = when (decision) {
                is VadDecision.Stop -> Outcome.Captured(
                    audio = recorded.build(),
                    reason = decision.reason,
                    tail = when {
                        planner == null || chunks == 0 -> null
                        planner.speechSinceCut -> recorded.slice(planner.chunkStart)
                        else -> null
                    },
                    chunks = chunks,
                    technical = technical,
                )
                // Nothing worth sending and no microphone: the caller may try another way to it.
                is VadDecision.Discard -> if (decision.reason == DiscardReason.MIC_LOST) {
                    Outcome.Failed(R.string.capture_interrupted, technical.orEmpty())
                } else {
                    Outcome.Discarded(decision.reason, technical)
                }
                VadDecision.Continue -> error("a recording that goes on has not ended")
            }

            while (currentCoroutineContext().isActive) {
                val read = recorder.read(frame, 0, frameSamples)
                val stop = stopRequested()
                val allZero = read > 0 && (0 until read).all { frame[it] == 0.toShort() }
                when (val step = watch.onRead(read, allZero, silenced = silence.silenced, stopRequested = stop)) {
                    MicrophoneWatch.Step.Frame -> Unit
                    MicrophoneWatch.Step.Again -> continue
                    MicrophoneWatch.Step.Stopped -> return@withContext ended(vad.onManualStop())
                    is MicrophoneWatch.Step.Lost -> {
                        Log.w(TAG, "recording ended: ${step.technical}")
                        return@withContext ended(MicrophoneWatch.ending(step.why, vad.onManualStop()), step.technical)
                    }
                }

                // After the check for a silenced microphone, which must see what it delivered.
                toneMute.apply(frame, read)
                val rms = PcmAudio.rms(frame, 0, read)
                val decision = when (val step = gate.onFrame(rms, stop)) {
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
                if (decision != VadDecision.Continue) return@withContext ended(decision)
            }
            // Cancelled from outside: treat it as nothing was said.
            Outcome.Discarded(DiscardReason.NO_SPEECH)
        } finally {
            silence.release(recorder)
            runCatching { recorder.stop() }.onFailure { Log.w(TAG, "stop failed", it) }
            recorder.release()
        }
    }

    /**
     * Whether another app has silenced this recording - a call, another recorder: from Android
     * 10 the microphone then delivers zeros without any error. Android reports every change of
     * the recording's configuration; the state at the start is asked for once.
     */
    private class SilencedWatch {
        /** False below Android 10: nobody to ask, the zeros themselves have to tell. */
        val known: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        @Volatile
        var silenced: Boolean = false
            private set

        private var callback: Any? = null

        fun watch(recorder: AudioRecord) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val changes = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                    // Only this recorder's configuration is reported; none while it is not recording.
                    silenced = configs.any { it.isClientSilenced }
                }
            }
            runCatching {
                // The callback only sets a flag: it may run on whatever thread delivers it.
                recorder.registerAudioRecordingCallback({ it.run() }, changes)
                callback = changes
                silenced = recorder.activeRecordingConfiguration?.isClientSilenced == true
            }.onFailure { Log.w(TAG, "could not watch for a silenced microphone", it) }
        }

        fun release(recorder: AudioRecord) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            (callback as? AudioManager.AudioRecordingCallback)?.let { runCatching { recorder.unregisterAudioRecordingCallback(it) } }
            callback = null
        }
    }

    private companion object {
        const val TAG = "Embersay"
    }
}
