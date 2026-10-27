package app.nocturne.dictation

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import app.nocturne.Graph
import app.nocturne.capture.AudioCapture
import app.nocturne.capture.MicForegroundService
import app.nocturne.capture.MicProxyActivity
import app.nocturne.core.audio.DiscardReason
import app.nocturne.core.capture.MicrophoneRoute
import app.nocturne.core.capture.MicrophoneRouter
import app.nocturne.core.capture.TriggerSource
import app.nocturne.core.engine.ListenCallbacks
import app.nocturne.core.engine.SelfCapturingSttEngine
import app.nocturne.core.engine.SttEngine
import app.nocturne.core.engine.SttFailure
import app.nocturne.core.engine.TranscribeOptions
import app.nocturne.core.text.Transcript
import app.nocturne.core.text.TranscriptNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

    val isRunning: Boolean
        get() = job?.isActive == true

    /** What the bubble does on a tap. */
    fun toggle() {
        if (isRunning) stop() else start()
    }

    fun start(source: TriggerSource = TriggerSource.BUBBLE) {
        if (isRunning) return

        when (
            MicrophoneRouter.route(
                source = source,
                hasPermission = hasMicrophonePermission(),
                foregroundServiceRunning = MicForegroundService.isRunning,
            )
        ) {
            MicrophoneRoute.NEEDS_PERMISSION -> {
                fail("Mikrofon-Berechtigung fehlt. Bitte in Nocturne erteilen.")
                return
            }

            MicrophoneRoute.VIA_PROXY_ACTIVITY -> {
                // Android 14+ refuses a background microphone start and a foreign app is in
                // front, so hand over to the invisible activity and let it call back in.
                Log.i(TAG, "no foreground anchor - routing through the proxy activity")
                MicProxyActivity.launch(context)
                return
            }

            MicrophoneRoute.START_SERVICE_THEN_RECORD -> MicForegroundService.start(context)

            MicrophoneRoute.RECORD_NOW -> Unit
        }

        stopRequested.set(false)
        job = scope.launch { run(source) }
    }

    /** Finish the current recording and transcribe what was captured. */
    fun stop() {
        stopRequested.set(true)
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
            fail("Keine Engine ausgewählt. Bitte in den Einstellungen eine wählen.")
            return
        }

        val engine = graph.engineFactory.create(engineConfig).getOrElse { error ->
            fail(userMessageOf(error))
            return
        }

        val options = TranscribeOptions(languageTag = engineConfig.languageTag)

        try {
            // A recognizer that never calls back, or a server that accepts the upload and
            // goes quiet, would otherwise leave the bubble spinning with no way out but
            // force-stopping the app.
            val finished = withTimeoutOrNull(settings.capture.effectiveTotalTimeoutMs()) {
                val transcript = when (engine) {
                    is SelfCapturingSttEngine -> listenWithEngine(engine, options)
                    else -> recordThenTranscribe(engine, options, source)
                } ?: return@withTimeoutOrNull Unit // the failure was already reported

                deliver(transcript, settings, engineConfig.id)
            }

            if (finished == null) {
                fail("Zeitüberschreitung - das Diktat wurde abgebrochen.", engineConfig.id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "dictation failed", e)
            fail("Unerwarteter Fehler: ${e.message ?: e::class.simpleName}")
        } finally {
            selfCapturing = null
            stopRequested.set(false)
            runCatching { engine.close() }
        }
    }

    private suspend fun listenWithEngine(
        engine: SelfCapturingSttEngine,
        options: TranscribeOptions,
    ): Transcript? {
        selfCapturing = engine
        _state.value = DictationState.Listening()

        val result = engine.listen(
            options,
            ListenCallbacks(
                onAmplitude = { amplitude -> updateListening { it.copy(amplitude = amplitude) } },
                onPartial = { partial -> updateListening { it.copy(partial = partial) } },
            ),
        )

        _state.value = DictationState.Transcribing
        return result.getOrElse { error ->
            fail(userMessageOf(error))
            null
        }
    }

    private suspend fun recordThenTranscribe(
        engine: SttEngine,
        options: TranscribeOptions,
        source: TriggerSource,
    ): Transcript? {
        _state.value = DictationState.Listening()

        val vadConfig = graph.settings.value.capture.toVadConfig()
        val outcome = capture.record(
            config = vadConfig,
            stopRequested = { stopRequested.get() },
            onAmplitude = { amplitude -> updateListening { it.copy(amplitude = amplitude) } },
        )

        when (outcome) {
            is AudioCapture.Outcome.Discarded -> {
                _state.value = DictationState.Idle
                if (outcome.reason == DiscardReason.TOO_SHORT) {
                    fail("Zu kurz - bitte etwas länger sprechen.")
                }
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
                _state.value = DictationState.Transcribing
                return engine.transcribe(outcome.audio, options).getOrElse { error ->
                    fail(userMessageOf(error))
                    null
                }
            }
        }
    }

    private suspend fun deliver(
        transcript: Transcript,
        settings: app.nocturne.core.settings.NocturneSettings,
        engineId: String,
    ) {
        val cleaned = TranscriptNormalizer.normalize(
            transcript.text,
            settings.capture.toNormalizeOptions(),
        )
        if (cleaned.isBlank()) {
            fail("Es wurde nichts erkannt.", engineId)
            return
        }

        val inserter = graph.accessibilityService?.textInserter
        if (inserter == null) {
            fail("Bedienungshilfe ist nicht aktiv - Text konnte nicht eingefügt werden.", engineId)
            return
        }

        val insertion = inserter.insert(cleaned, settings.insertion.toSpliceOptions())
        Log.i(
            TAG,
            "insertion via ${insertion.strategy} into ${insertion.targetPackage}: " +
                "field=${insertion.insertedIntoField} clipboard=${insertion.copiedToClipboard} " +
                "(engine=${transcript.engineId}, trust=${transcript.trustLevel}, " +
                "${transcript.latencyMs}ms)",
        )

        if (!insertion.insertedIntoField && !insertion.copiedToClipboard) {
            // Neither the field nor the clipboard took it - the text is gone, so say so
            // instead of showing a success state.
            fail("Text konnte weder eingefügt noch kopiert werden.", engineId)
            return
        }

        val finalTranscript = transcript.copy(text = cleaned)
        graph.dictationLog.recordSuccess(
            transcript = finalTranscript,
            strategy = insertion.strategy,
            inserted = insertion.insertedIntoField,
            targetPackage = insertion.targetPackage,
        )

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

    private fun updateListening(transform: (DictationState.Listening) -> DictationState.Listening) {
        val current = _state.value
        if (current is DictationState.Listening) _state.value = transform(current)
    }

    private fun fail(message: String, engineId: String? = null) {
        Log.w(TAG, "dictation failed: $message")
        graph.dictationLog.recordFailure(message, engineId)
        _state.value = DictationState.Failed(message)

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

    private fun userMessageOf(error: Throwable): String = when (error) {
        is SttFailure -> error.userMessage
        else -> error.message ?: "Unbekannter Fehler"
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "Nocturne"
        const val DONE_DISPLAY_MS = 1_200L
        const val FAILED_DISPLAY_MS = 3_500L
    }
}
