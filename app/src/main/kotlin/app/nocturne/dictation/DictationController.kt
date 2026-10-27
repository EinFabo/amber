package app.nocturne.dictation

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.nocturne.Graph
import app.nocturne.capture.AudioCapture
import app.nocturne.capture.MicForegroundService
import app.nocturne.capture.MicProxyActivity
import app.nocturne.core.audio.DiscardReason
import app.nocturne.core.engine.ListenCallbacks
import app.nocturne.core.engine.SelfCapturingSttEngine
import app.nocturne.core.engine.SttEngine
import app.nocturne.core.engine.SttFailure
import app.nocturne.core.engine.TranscribeOptions
import app.nocturne.core.insert.InsertionStrategy
import app.nocturne.core.text.Transcript
import app.nocturne.core.text.TranscriptNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    fun start(viaProxyActivity: Boolean = false) {
        if (isRunning) return

        if (!hasMicrophonePermission()) {
            fail("Mikrofon-Berechtigung fehlt. Bitte in Nocturne erteilen.")
            return
        }

        // Android 14+ refuses a background microphone start. The foreground service is the
        // normal answer; if it is not up, hand over to the proxy activity, which is
        // foreground by definition, and let it call back in.
        if (!viaProxyActivity && !MicForegroundService.isRunning) {
            Log.i(TAG, "mic service not running - routing through proxy activity")
            MicProxyActivity.launch(context)
            return
        }

        stopRequested.set(false)
        job = scope.launch { run(viaProxyActivity) }
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

    private suspend fun run(viaProxyActivity: Boolean) {
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
            val transcript = when (engine) {
                is SelfCapturingSttEngine -> listenWithEngine(engine, options)
                else -> recordThenTranscribe(engine, options, viaProxyActivity)
            } ?: return

            deliver(transcript, settings)
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
        viaProxyActivity: Boolean,
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
                if (!viaProxyActivity) {
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
    ) {
        val cleaned = TranscriptNormalizer.normalize(
            transcript.text,
            settings.capture.toNormalizeOptions(),
        )
        if (cleaned.isBlank()) {
            fail("Es wurde nichts erkannt.")
            return
        }

        val inserter = graph.accessibilityService?.textInserter
        if (inserter == null) {
            fail("Bedienungshilfe ist nicht aktiv - Text konnte nicht eingefügt werden.")
            return
        }

        val insertion = inserter.insert(cleaned, settings.insertion.toSpliceOptions())
        Log.i(
            TAG,
            "inserted via ${insertion.strategy} into ${insertion.targetPackage} " +
                "(success=${insertion.success}, engine=${transcript.engineId}, " +
                "trust=${transcript.trustLevel}, ${transcript.latencyMs}ms)",
        )

        _state.value = DictationState.Done(
            transcript = transcript.copy(text = cleaned),
            strategy = insertion.strategy,
            insertedIntoApp = insertion.targetPackage,
        )

        // Let the bubble show the result for a moment, then go back to resting.
        delay(DONE_DISPLAY_MS)
        if (_state.value is DictationState.Done) _state.value = DictationState.Idle
    }

    private fun updateListening(transform: (DictationState.Listening) -> DictationState.Listening) {
        val current = _state.value
        if (current is DictationState.Listening) _state.value = transform(current)
    }

    private fun fail(message: String) {
        _state.value = DictationState.Failed(message)
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

/** Convenience for the insertion result, so the strategy is visible in logs and UI. */
data class InsertionOutcome(
    val strategy: InsertionStrategy,
    val success: Boolean,
    val targetPackage: String?,
)
