package app.amethyst.dictation

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import app.amethyst.Graph
import app.amethyst.capture.AudioCapture
import app.amethyst.capture.MicForegroundService
import app.amethyst.capture.MicProxyActivity
import app.amethyst.core.audio.DiscardReason
import app.amethyst.core.capture.MicrophoneRoute
import app.amethyst.core.capture.MicrophoneRouter
import app.amethyst.core.capture.TriggerSource
import app.amethyst.core.engine.ListenCallbacks
import app.amethyst.core.engine.ChunkedTranscription
import app.amethyst.core.engine.SelfCapturingSttEngine
import app.amethyst.core.engine.SttEngine
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.engine.TranscribeOptions
import app.amethyst.core.engine.whisper.WhisperCppEngine
import app.amethyst.core.engine.whisper.WhisperModels
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.text.Transcript
import app.amethyst.core.text.TranscriptNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives one dictation from tap to inserted text.
 *
 * Two shapes of engine have to be handled: ones that take a PCM buffer (every HTTP and local
 * model engine) and ones that own the microphone themselves (Android's recognizer). The
 * branch is explicit rather than hidden behind a wrapper, because the two paths differ in who
 * decides when speaking has stopped.
 */
class DictationController(
    private val context: Context,
    private val graph: Graph,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<DictationState>(DictationState.Idle)
    val state: StateFlow<DictationState> = _state.asStateFlow()

    private val capture = AudioCapture()
    private val stopRequested = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var job: Job? = null

    @Volatile
    private var selfCapturing: SelfCapturingSttEngine? = null

    /**
     * Push to talk: recording runs until the finger lifts, so a pause to think must not end
     * it. Survives the hand-over to the proxy activity, which calls start() again.
     */
    @Volatile
    private var holdToTalk = false

    /** Timing of the current run; created at the first start(), kept across the proxy hop. */
    @Volatile
    private var trace: RunTrace? = null

    /** A release that came while the proxy activity was still on its way. */
    private val stopDuringHandover = AtomicBoolean(false)

    val isRunning: Boolean
        get() = job?.isActive == true

    /** What the bubble does on a tap. */
    fun toggle() {
        if (isRunning) stop() else start()
    }

    fun start(source: TriggerSource = TriggerSource.BUBBLE, holdToTalk: Boolean = false) {
        if (isRunning) return
        if (source != TriggerSource.PROXY_ACTIVITY) {
            this.holdToTalk = holdToTalk
            stopDuringHandover.set(false)
        }

        val route = MicrophoneRouter.route(
            source = source,
            hasPermission = hasMicrophonePermission(),
            foregroundServiceRunning = MicForegroundService.isRunning,
        )
        trace = if (source == TriggerSource.PROXY_ACTIVITY && trace != null) {
            trace
        } else {
            RunTrace("${source.name}/${route.name}")
        }

        when (route) {
            MicrophoneRoute.NEEDS_PERMISSION -> {
                fail("Microphone permission is missing. Please grant it in Amber.")
                return
            }

            MicrophoneRoute.VIA_PROXY_ACTIVITY -> {
                // Android 14+ refuses a background microphone start and a foreign app is in
                // front, so hand over to the invisible activity and let it call back in.
                Log.i(TAG, "no foreground anchor - routing through the proxy activity")

                // Set synchronously, before the activity exists. The activity waits for the
                // run to *leave* this state; seeing Idle it would finish during its own
                // onCreate - before AudioRecord opened - and the anchor it exists to provide
                // would be gone again.
                _state.value = DictationState.Starting
                MicProxyActivity.launch(context)
                watchProxyHandover()
                return
            }

            MicrophoneRoute.START_SERVICE_THEN_RECORD -> MicForegroundService.start(context)

            MicrophoneRoute.RECORD_NOW -> Unit
        }

        graph.dictationStarting()

        // Re-entry from the proxy keeps a release that happened while it was starting -
        // otherwise a held-then-released bubble would record until the duration cap.
        stopRequested.set(source == TriggerSource.PROXY_ACTIVITY && stopDuringHandover.getAndSet(false))
        // Also set here, synchronously: anything watching the state must see the dictation
        // begin before the coroutine gets its first dispatch.
        _state.value = DictationState.Starting
        job = scope.launch { run(source) }
    }

    /**
     * Background activity launches are dropped on some vendor builds. When that happens the
     * proxy never calls back and a bubble tap would do nothing at all - no state change, no
     * message. So say something after a moment instead of staying silent.
     */
    private fun watchProxyHandover() {
        scope.launch {
            delay(PROXY_HANDOVER_TIMEOUT_MS)
            if (!isRunning && _state.value is DictationState.Starting) {
                fail(
                    "The microphone could not be opened. Open Amber once, " +
                        "then the bubble works directly.",
                )
            }
        }
    }

    /** Finish the current recording and transcribe what was captured. */
    fun stop() {
        stopRequested.set(true)
        if (!isRunning && _state.value is DictationState.Starting) stopDuringHandover.set(true)
        selfCapturing?.requestStop()
    }

    /** Throw the current recording away. */
    fun cancel() {
        job?.cancel()
        _state.value = DictationState.Idle
    }

    private suspend fun run(source: TriggerSource) {
        _state.value = DictationState.Starting

        val settings = graph.settings.value
        val engineConfig = settings.activeEngine
        if (engineConfig == null) {
            fail("No engine selected. Please pick one in the settings.")
            return
        }

        val engine = graph.engineFactory.create(engineConfig).getOrElse { error ->
            fail(userMessageOf(error), remedy = remedyOf(error))
            return
        }

        val options = TranscribeOptions(languageTag = engineConfig.languageTag)
        if (engine is WhisperCppEngine) {
            val modelId = engineConfig.modelId ?: WhisperModels.default.id
            trace?.modelWarm = graph.whisperRuntime.isLoaded(graph.whisperModels.fileFor(modelId).path)
        }

        try {
            // A recognizer that never calls back, or a server that accepts the upload and
            // goes quiet, would otherwise leave the bubble spinning with no way out but
            // force-stopping the app.
            val finished = withTimeoutOrNull(settings.capture.effectiveTotalTimeoutMs()) {
                val transcript = when (engine) {
                    is SelfCapturingSttEngine -> listenWithEngine(engine, options)
                    else -> recordThenTranscribe(engine, options, source, estimateKey = "${engineConfig.id}/${engineConfig.modelId}")
                } ?: return@withTimeoutOrNull Unit // the failure was already reported

                deliver(cleanUp(transcript, engineConfig), settings, engineConfig.id)
            }

            if (finished == null) {
                fail("Timed out - the dictation was cancelled.", engineConfig.id)
            }
        } catch (e: CancellationException) {
            // cancel() already set the state, and a cancelled parent scope is not a dictation
            // error. Rethrow so structured concurrency still sees it.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "dictation failed", e)
            fail("Unexpected error: ${e.message ?: e::class.simpleName}")
        } finally {
            selfCapturing = null
            stopRequested.set(false)
            holdToTalk = false
            runCatching { engine.close() }
            graph.scheduleModelRelease()
        }
    }

    private suspend fun listenWithEngine(
        engine: SelfCapturingSttEngine,
        options: TranscribeOptions,
    ): Transcript? {
        selfCapturing = engine
        _state.value = DictationState.Listening()
        // Recognition overlaps with listening here; the whole call counts as both.
        trace?.recordingStarts()
        trace?.transcribingStarts()

        val result = engine.listen(
            options,
            ListenCallbacks(
                onAmplitude = { amplitude -> updateListening { it.copy(amplitude = amplitude) } },
                onPartial = { partial -> updateListening { it.copy(partial = partial) } },
            ),
        )

        trace?.recordingEnds()
        trace?.transcribingEnds()
        _state.value = DictationState.Transcribing()
        return result.getOrElse { error ->
            fail(userMessageOf(error), remedy = remedyOf(error))
            null
        }
    }

    private suspend fun recordThenTranscribe(
        engine: SttEngine,
        options: TranscribeOptions,
        source: TriggerSource,
        /** Engine and model, for learning how long recognition takes here. */
        estimateKey: String,
    ): Transcript? {
        _state.value = DictationState.Listening()

        trace?.recordingStarts()
        val vadConfig = graph.settings.value.capture.toVadConfig().let { config ->
            if (holdToTalk) config.copy(silenceTimeoutMs = Int.MAX_VALUE) else config
        }
        // Finished parts of a longer dictation are recognized while the user is still speaking,
        // so stopping leaves only the last part to wait for.
        // A child of this dictation's job: cancelling the dictation stops the chunk work too.
        val chunked = if (engine.capabilities.chunkable) {
            ChunkedTranscription(engine, options, CoroutineScope(currentCoroutineContext()))
        } else {
            null
        }
        val outcome = try {
            capture.record(
                config = vadConfig,
                stopRequested = { stopRequested.get() },
                onAmplitude = { amplitude -> updateListening { it.copy(amplitude = amplitude) } },
                onChunk = chunked?.let { it::submit },
                minChunkMs = engine.capabilities.minChunkMs,
            )
        } catch (e: Throwable) {
            chunked?.cancel()
            throw e
        }
        if (outcome !is AudioCapture.Outcome.Captured || outcome.chunks == 0) chunked?.cancel()

        when (outcome) {
            is AudioCapture.Outcome.Discarded -> {
                // Never silent: a dictation that ends with no tick and no text and no word why
                // looks exactly like a bug. Both cases get a message and a diagnostics line.
                fail(
                    when (outcome.reason) {
                        DiscardReason.TOO_SHORT -> "Too short - please speak a little longer."
                        DiscardReason.NO_SPEECH -> "Nothing heard - try again a little closer to the phone."
                    },
                )
                return null
            }

            is AudioCapture.Outcome.Failed -> {
                Log.w(TAG, "capture failed: ${outcome.technical}")
                // Classic symptom of a ROM blocking the microphone for a background
                // service. Retry once through the activity path before giving up.
                if (source.mayUseProxyFallback) {
                    Log.i(TAG, "retrying capture via proxy activity")
                    MicProxyActivity.launch(context)
                } else {
                    fail(outcome.message)
                }
                return null
            }

            is AudioCapture.Outcome.Captured -> {
                trace?.recordingEnds()
                val chunking = chunked != null && outcome.chunks > 0
                // What is left to recognize after the stop: with chunks, only the unfinished
                // ones and the tail.
                val pendingMs = if (chunking) {
                    chunked!!.pendingAudioMs + (outcome.tail?.durationMs ?: 0L)
                } else {
                    outcome.audio.durationMs
                }
                val caps = engine.capabilities
                val coldModel = trace?.modelWarm == false
                val overheadMs = caps.overheadMs + if (coldModel) MODEL_LOAD_MS else 0L
                val expectedMs = graph.recognitionEstimate.expectedMs(estimateKey, pendingMs, caps.typicalRate, overheadMs)
                _state.value = DictationState.Transcribing(expectedMs)
                trace?.transcribingStarts()
                val startedAt = SystemClock.elapsedRealtime()
                val result = if (chunking) {
                    chunked!!.finish(outcome.tail, outcome.audio).also { trace?.chunks = chunked.chunkCount }
                } else {
                    engine.transcribe(outcome.audio, options)
                }
                trace?.transcribingEnds()
                // A cold model's load time would teach the wrong rate.
                if (result.isSuccess && !coldModel) {
                    graph.recognitionEstimate.learn(estimateKey, pendingMs, SystemClock.elapsedRealtime() - startedAt, caps.overheadMs)
                }
                if (engine is WhisperCppEngine) trace?.whisperScheduling = graph.whisperRuntime.schedulingSummary()
                return result.getOrElse { error ->
                    fail(userMessageOf(error), remedy = remedyOf(error))
                    null
                }
            }
        }
    }

    /**
     * Runs the engine's chosen cleanup model over the transcript, if any. Never fails the
     * dictation: on an error, a timeout or an implausible answer the transcript is kept as
     * recognized, and the diagnostics say why. The bubble stays on "working" meanwhile.
     */
    private suspend fun cleanUp(transcript: Transcript, config: EngineConfig): Transcript {
        val cleanup = graph.engineFactory.cleanupFor(config) ?: return transcript
        val result = withTimeoutOrNull(CLEANUP_TIMEOUT_MS) { cleanup.clean(transcript.text) }
        if (result == null) {
            trace?.cleanup = "cleanup timed out (${cleanup.model})"
            return transcript
        }
        return result.fold(
            onSuccess = { cleaned ->
                trace?.cleanup = "cleanup ${cleaned.latencyMs} ms (${cleanup.model})"
                transcript.copy(
                    text = cleaned.text,
                    // The text is only as trustworthy as the weaker of the two trips it made.
                    trustLevel = listOf(transcript.trustLevel, cleaned.trust).maxBy { it.strictness },
                )
            },
            onFailure = { error ->
                trace?.cleanup = "cleanup skipped: ${userMessageOf(error)}"
                Log.w(TAG, "cleanup failed", error)
                transcript
            },
        )
    }

    private suspend fun deliver(
        transcript: Transcript,
        settings: app.amethyst.core.settings.AmethystSettings,
        engineId: String,
    ) {
        val cleaned = TranscriptNormalizer.normalize(
            transcript.text,
            settings.capture.toNormalizeOptions(),
        )
        if (cleaned.isBlank()) {
            fail(SttFailure.NOTHING_RECOGNIZED, engineId)
            return
        }

        val inserter = graph.accessibilityService?.textInserter
        if (inserter == null) {
            fail("The accessibility service is off - the text could not be inserted.", engineId)
            return
        }

        awaitFocusReturn()

        trace?.insertingStarts()
        val insertion = inserter.insert(
            cleaned,
            settings.insertion.toSpliceOptions(),
            allowClipboard = settings.insertion.allowClipboard,
        )
        trace?.insertingEnds()
        Log.i(
            TAG,
            "insertion via ${insertion.strategy} into ${insertion.targetPackage}: " +
                "field=${insertion.insertedIntoField} clipboard=${insertion.copiedToClipboard} " +
                "(engine=${transcript.engineId}, trust=${transcript.trustLevel}, " +
                "${transcript.latencyMs}ms)",
        )

        if (!insertion.insertedIntoField && !insertion.copiedToClipboard) {
            // Neither the field nor the clipboard took it. Quote the words in the message so
            // they survive in the diagnostics card instead of being lost.
            fail(
                "No text field accepted the text: \u201C$cleaned\u201D " +
                    "[${inserter.lastTrace}]",
                engineId,
            )
            return
        }

        trace?.servedBy = transcript.servedBy
        val finalTranscript = transcript.copy(text = cleaned)
        graph.dictationLog.recordSuccess(
            transcript = finalTranscript,
            strategy = insertion.strategy,
            inserted = insertion.insertedIntoField,
            targetPackage = insertion.targetPackage,
            note = inserter.lastTrace,
            timing = trace?.summary(),
        )
        trace?.let { Log.i(TAG, "dictation timing: ${it.summary()}") }

        _state.value = DictationState.Done(
            transcript = finalTranscript,
            strategy = insertion.strategy,
            insertedIntoApp = insertion.targetPackage,
        )

        // Let the bubble show the result for a moment, then go back to resting. Launched
        // separately so this pause is not charged to the dictation's timeout.
        scope.launch {
            delay(DONE_DISPLAY_MS)
            if (_state.value is DictationState.Done) _state.value = DictationState.Idle
        }
    }

    /**
     * The proxy activity finishes when listening ends, which is just before the text is ready.
     * Its window has to be gone, and focus back in the user's app, before anything can be
     * typed there - otherwise the search for the focused field finds the proxy.
     */
    private suspend fun awaitFocusReturn() {
        if (!MicProxyActivity.alive) return
        var waited = 0L
        while (MicProxyActivity.alive && waited < PROXY_EXIT_TIMEOUT_MS) {
            delay(PROXY_POLL_MS)
            waited += PROXY_POLL_MS
        }
        delay(FOCUS_SETTLE_MS)
        trace?.focusWait(waited + FOCUS_SETTLE_MS)
        Log.i(TAG, "waited ${waited + FOCUS_SETTLE_MS} ms for the proxy to hand focus back")
    }

    private fun updateListening(transform: (DictationState.Listening) -> DictationState.Listening) {
        val current = _state.value
        if (current is DictationState.Listening) _state.value = transform(current)
    }

    private fun fail(
        message: String,
        engineId: String? = null,
        remedy: SttFailure.Remedy? = null,
    ) {
        Log.w(TAG, "dictation failed: $message")
        graph.dictationLog.recordFailure(message, engineId, trace?.summary())
        _state.value = DictationState.Failed(message, remedy)

        // While dictating into another app the bubble is the only visible surface, and a red
        // ring does not say why. A toast does, without making the user switch back.
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }

        scope.launch {
            delay(FAILED_DISPLAY_MS)
            if (_state.value is DictationState.Failed) _state.value = DictationState.Idle
        }
    }

    private fun remedyOf(error: Throwable): SttFailure.Remedy? = (error as? SttFailure)?.remedy

    private fun userMessageOf(error: Throwable): String = when (error) {
        is SttFailure -> error.userMessage
        else -> error.message ?: "Unknown error"
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "Amber"
        const val DONE_DISPLAY_MS = 1_200L

        /** Rough cost of loading a Whisper model that was not in memory, for the estimate. */
        const val MODEL_LOAD_MS = 1_000L

        /** Past this the dictation goes in as recognized rather than keep the user waiting. */
        const val CLEANUP_TIMEOUT_MS = 12_000L
        const val PROXY_HANDOVER_TIMEOUT_MS = 3_000L
        const val FAILED_DISPLAY_MS = 3_500L
        const val PROXY_EXIT_TIMEOUT_MS = 1_500L
        const val PROXY_POLL_MS = 50L
        const val FOCUS_SETTLE_MS = 200L
    }
}
