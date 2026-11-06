package app.amethyst.dictation

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import app.amethyst.DebugLog
import app.amethyst.Graph
import app.amethyst.capture.AudioCapture
import app.amethyst.capture.MicForegroundService
import app.amethyst.capture.MicProxyActivity
import app.amethyst.core.audio.DiscardReason
import app.amethyst.core.bubble.BubbleEvent
import app.amethyst.core.bubble.TextFieldKind
import app.amethyst.core.capture.MicrophoneRoute
import app.amethyst.core.capture.MicrophoneRouter
import app.amethyst.core.capture.ProxyHandover
import app.amethyst.core.capture.RecordingPause
import app.amethyst.core.capture.withPausableTimeout
import app.amethyst.core.capture.TriggerSource
import app.amethyst.core.engine.ListenCallbacks
import app.amethyst.core.insert.InputTarget
import app.amethyst.core.insert.TargetCheck
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.engine.ChunkedTranscription
import app.amethyst.core.engine.ReadyFallback
import app.amethyst.core.engine.WaitProgress
import app.amethyst.core.engine.SelfCapturingSttEngine
import app.amethyst.core.engine.SttEngine
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.engine.TranscribeOptions
import app.amethyst.core.engine.sherpa.ParakeetEngine
import app.amethyst.core.engine.whisper.WhisperCppEngine
import app.amethyst.core.engine.whisper.WhisperModels
import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.commands.Command
import app.amethyst.core.commands.CommandChain
import app.amethyst.core.commands.CommandLanguages
import app.amethyst.core.commands.CommandOutcome
import app.amethyst.core.commands.CommandTrace
import app.amethyst.core.commands.GlobalAction
import app.amethyst.core.commands.CommandTrigger
import app.amethyst.core.commands.RunMode
import app.amethyst.core.commands.Understanding
import app.amethyst.core.commands.VoiceCommands
import app.amethyst.core.engine.CleanupRouting
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.EngineTrust
import app.amethyst.core.engine.OpenAiCompatibleEngine
import app.amethyst.core.net.ConnectionWarmUp
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.engine.AndroidOnDeviceEngine
import app.amethyst.core.engine.DownloadNotice
import app.amethyst.engine.ModelDownloads
import app.amethyst.core.engine.Fallback
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.settings.AppProfiles
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.WritingStyle
import app.amethyst.core.stats.DictationStat
import app.amethyst.core.stats.ServerCost
import app.amethyst.core.stats.countWords
import app.amethyst.core.text.LanguageGuess
import app.amethyst.core.text.Transcript
import app.amethyst.core.text.TranscriptNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

    /** This run's pause from the bubble's menu ([pause]); a new one for every run. */
    @Volatile
    private var recordingPause = RecordingPause()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var job: Job? = null

    @Volatile
    private var selfCapturing: SelfCapturingSttEngine? = null

    /**
     * Completed when this run's text is to go in as recognized - "No cleanup" or "As
     * recognized" from the bubble's quick menu. A cleanup on its way is dropped at that moment.
     */
    @Volatile
    private var skipCleanup = CompletableDeferred<Unit>()

    /** Timing of the current run; created at the first start(), kept across the proxy hop. */
    @Volatile
    private var trace: RunTrace? = null

    /**
     * The app whose field had focus when the dictation started. Text is only typed into that
     * app: if the user switched meanwhile, it would land in another chat - maybe to someone
     * else. Kept across the proxy activity's re-entry, which itself has focus.
     */
    private var startTarget: InputTarget? = null

    /** The local engine that took over this dictation from an unreachable server, if any. */
    private var fellBack: String? = null

    /** How far the wait after this run's recording has come, for the progress ring. */
    @Volatile
    private var wait: WaitProgress? = null

    /** The engine that failed when [fellBack] took over. */
    private var fellBackFrom: String? = null

    /**
     * A run handed to the proxy activity, until the activity calls back in: a cancel or start
     * over meanwhile decides whether it may record then.
     */
    private val handover = ProxyHandover(lostAfterMs = PROXY_HANDOVER_TIMEOUT_MS)

    /** Counts runs, so a timer from an earlier run never acts on a later one. */
    private var runSerial = 0

    private val _mode = MutableStateFlow(RunMode.DICTATION)

    /** Whether the run in progress is a dictation or a voice command; the bubble looks the part. */
    val mode: StateFlow<RunMode> = _mode.asStateFlow()

    val isRunning: Boolean
        get() = job?.isActive == true

    /** When a tap last stopped a command (uptime), so the second tap of that double tap starts nothing. */
    private var commandStoppedAt: Long? = null

    /** A tap stopped this run's command: Diagnostics say so instead of nothing. */
    @Volatile
    private var stoppedByTap = false

    /** Warm-ups per server; one still running is joined, so rapid taps do not queue HEADs. */
    private val warmUps by lazy { ConnectionWarmUp.Pool(graph.appScope) }

    /** This run's server connection, opened while the user speaks; null for a model on the phone. */
    @Volatile
    private var connectionWarmUp: ConnectionWarmUp? = null

    /** The cleanup whose server connection this run opened at its start, and how that went. */
    @Volatile
    private var cleanupWarmUp: Pair<CleanupChoice, ConnectionWarmUp>? = null

    /** The engine this run recognizes with, for a command's Diagnostics when it fails before it is read. */
    @Volatile
    private var runEngineId: String? = null

    /**
     * The steps of this run's command once its recognition began - each language it was heard
     * in ([recognizeCommand]) - or once its words are there ([runCommand]); null before. A
     * failure that comes after - the time limit - still shows them.
     */
    @Volatile
    private var commandSteps: CommandTrace? = null

    /** This run's command has its record in Diagnostics: a tap after that adds none. */
    @Volatile
    private var commandRecorded = false

    /**
     * What the bubble does on a tap. True when it started a recording rather than stopped one.
     * While a command is being carried out a tap stops it at once - a misheard "zwanzigmal
     * lauter" must have a way out - and the second tap of a double tap given for that is no
     * new recording.
     */
    fun toggle(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (isRunning) {
            val recording = _state.value.let { it is DictationState.Starting || it is DictationState.Listening }
            when (CommandTrigger.tapInRun(_mode.value, recording)) {
                CommandTrigger.TapInRun.STOP_RECORDING -> stop()
                CommandTrigger.TapInRun.CANCEL -> {
                    commandStoppedAt = now
                    stoppedByTap = true
                    cancel()
                    Log.i(TAG, "command stopped by a tap")
                    mainHandler.post { Toast.makeText(context, "Command stopped.", Toast.LENGTH_SHORT).show() }
                }
            }
            return false
        }
        if (CommandTrigger.isSecondTapOfStop(commandStoppedAt, now)) return false
        start()
        return _state.value is DictationState.Starting
    }

    fun start(source: TriggerSource = TriggerSource.BUBBLE) {
        // The proxy activity records only for a run that still waits for it - not after a
        // cancel - and brings along a release that came while it was on its way.
        val stoppedDuringHandover = if (source == TriggerSource.PROXY_ACTIVITY) {
            val arrival = handover.arrive()
            if (arrival == null) {
                Log.i(TAG, "mic proxy activity arrived with no dictation waiting for it")
                return
            }
            arrival.stopped
        } else {
            false
        }
        if (isRunning) return
        // The engine chosen in the introduction may still be downloading: say so and record
        // nothing, rather than taking the words and failing - and never hand them to another engine.
        graph.settings.value.activeEngine?.takeIf(ModelDownloads::downloadsModel)?.let(graph.modelDownloads::forEngine)?.let { download ->
            notice(DownloadNotice.atStart(download))
            return
        }
        if (source != TriggerSource.PROXY_ACTIVITY) {
            runSerial++
            stoppedByTap = false
            commandSteps = null
            commandRecorded = false
            runEngineId = graph.settings.value.activeEngine?.id
            skipCleanup = CompletableDeferred()
            fellBack = null
            fellBackFrom = null
            startTarget = graph.accessibilityService?.focusedTarget()
            _mode.value = CommandTrigger.modeAtStart(
                source,
                commandsOn = graph.settings.value.commandsEnabled,
                textFieldFocused = graph.accessibilityService?.editableFieldFocused == true,
            )
            if (_mode.value == RunMode.COMMAND) capCommand()
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
                fail("Microphone permission is missing. Please grant it in Amber.", details = listOf("microphone permission missing"))
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
                // A start over during a handover takes the activity on its way: never two.
                if (handover.begin(SystemClock.elapsedRealtime())) {
                    MicProxyActivity.launch(context)
                    watchProxyHandover()
                }
                return
            }

            MicrophoneRoute.START_SERVICE_THEN_RECORD -> MicForegroundService.start(context)

            MicrophoneRoute.RECORD_NOW -> Unit
        }

        // Recording directly: an activity still on its way starts nothing when it arrives.
        if (source != TriggerSource.PROXY_ACTIVITY) handover.cancel()
        graph.dictationStarting()

        // Re-entry from the proxy keeps a stop that came while it was starting - otherwise a
        // tap that stopped the dictation meanwhile would leave it recording until the cap.
        stopRequested.set(stoppedDuringHandover)
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
                handover.lost()
                fail(
                    "The microphone could not be opened. Open Amber once, " +
                        "then the bubble works directly.",
                    details = listOf("the invisible microphone activity did not start within $PROXY_HANDOVER_TIMEOUT_MS ms"),
                )
            }
        }
    }

    /**
     * The second tap of a double tap: the recording the first tap just started becomes a voice
     * command instead of being stopped. False when there is nothing to turn - commands off, or
     * no recording that is still listening.
     */
    fun commandInstead(): Boolean {
        if (!graph.settings.value.commandsEnabled || stopRequested.get()) return false
        val state = _state.value
        if (state !is DictationState.Starting && state !is DictationState.Listening) return false
        if (_mode.value != RunMode.COMMAND) {
            _mode.value = RunMode.COMMAND
            capCommand()
        }
        return true
    }

    /**
     * A command is a few words: this run's recording is stopped after
     * [CommandTrigger.COMMAND_MAX_MS] if it is still going - never a later run's.
     */
    private fun capCommand() {
        val serial = runSerial
        scope.launch {
            delay(CommandTrigger.COMMAND_MAX_MS.toLong())
            val state = _state.value
            if (runSerial == serial && (state is DictationState.Starting || state is DictationState.Listening)) stop()
        }
    }

    /** Finish the current recording and transcribe what was captured. */
    fun stop() {
        stopRequested.set(true)
        if (!isRunning && _state.value is DictationState.Starting) handover.stop()
        selfCapturing?.requestStop()
    }

    /** Throw the current recording away - also one still being handed to the proxy activity. */
    fun cancel() {
        handover.cancel()
        job?.cancel()
        _state.value = DictationState.Idle
    }

    /**
     * Throws the recording away and starts a new one - once the old run has let go of the
     * microphone, so the two never share it. During a handover to the proxy activity the new
     * run takes the activity already on its way ([ProxyHandover.begin]).
     */
    fun startOver() {
        val old = job
        cancel()
        if (old == null) {
            start()
            return
        }
        scope.launch {
            old.join()
            withContext(Dispatchers.Main) { if (!isRunning) start() }
        }
    }

    /**
     * Whether the recording in progress can pause: Amber's own recorder holds the microphone
     * (Android's recognizer does not stop listening on request), and it is a dictation.
     */
    val canPause: Boolean
        get() = _state.value is DictationState.Listening && selfCapturing == null && _mode.value == RunMode.DICTATION

    /**
     * Pauses the recording: the microphone stays open - going on needs no new start from the
     * background - but nothing it hears is kept, sent or counted toward stopping on silence
     * or the time limits. A tap still ends the recording and sends what came before.
     */
    fun pause(): Boolean {
        if (!canPause || !recordingPause.pause(SystemClock.elapsedRealtime())) return false
        updateListening { it.copy(paused = true, amplitude = 0f) }
        Log.i(TAG, "recording paused")
        return true
    }

    /** Goes on recording after [pause], seamlessly: the text is what was said before and after. */
    fun resume(): Boolean {
        if (_state.value !is DictationState.Listening || !recordingPause.resume(SystemClock.elapsedRealtime())) return false
        updateListening { it.copy(paused = false) }
        Log.i(TAG, "recording resumed")
        return true
    }

    /** Ends the recording; the words go in as recognized, without the cleanup. */
    fun sendWithoutCleanup() {
        skipCleanup.complete(Unit)
        stop()
    }

    /** The text goes in as recognized: a cleanup still to come is skipped, one under way dropped. */
    fun keepAsRecognized() {
        skipCleanup.complete(Unit)
    }

    /**
     * The last dictation of this session (any app) typed again into the field that has focus
     * now - never into a password field. False with nothing to insert or nowhere to put it.
     */
    fun insertAgain(): Boolean {
        if (isRunning) return false
        val text = graph.dictationLog.lastDictation?.text ?: return false
        val service = graph.accessibilityService ?: return false
        val target = service.focusedTarget()
        if (target == null || target.kind == TextFieldKind.SECRET) {
            toast("No text field to insert into.")
            return false
        }
        val settings = graph.settings.value
        val insertion = service.textInserter.insert(text, settings.insertion.toSpliceOptions(), allowClipboard = settings.insertion.allowClipboard)
        Log.i(TAG, "inserted again via ${insertion.strategy} into ${DebugLog.redact(insertion.targetPackage)}: field=${insertion.insertedIntoField}")
        if (!insertion.insertedIntoField && !insertion.copiedToClipboard) {
            toast("The text could not be inserted here.")
            return false
        }
        return true
    }

    /**
     * The last dictation of this session onto the clipboard, marked sensitive so it stays out
     * of clipboard previews and history - asked for by the user, so whatever the clipboard
     * fallback setting says.
     */
    fun copyLast(): Boolean {
        val text = graph.dictationLog.lastDictation?.text ?: return false
        val inserter = graph.accessibilityService?.textInserter ?: return false
        if (!inserter.copyToClipboard(text)) return false
        // From Android 13 the system confirms a copy itself.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            toast("Last dictation copied.")
        }
        return true
    }

    private suspend fun run(source: TriggerSource) {
        _state.value = DictationState.Starting
        val pause = RecordingPause().also { recordingPause = it }
        wait = null
        stoppedByTap = false
        commandSteps = null
        commandRecorded = false

        val settings = graph.settings.value
        val engineConfig = settings.activeEngine
        runEngineId = engineConfig?.id
        if (engineConfig == null) {
            fail("No engine selected. Please pick one in the settings.", details = listOf("no engine selected"))
            return
        }

        val engine = graph.engineFactory.create(engineConfig).getOrElse { error ->
            fail(userMessageOf(error), remedy = remedyOf(error), details = listOf("engine ${engineConfig.id} could not be set up: ${technicalOf(error)}"))
            return
        }
        warmUpConnections(engine, engineConfig, settings)

        val options = TranscribeOptions(languageTag = engineConfig.languageTag)
        val appLabels = appLabelsOnce()
        if (engine is ParakeetEngine) {
            trace?.modelWarm = graph.sherpaRuntime.isLoaded(graph.parakeetModels.dir.path)
        }
        if (engine is WhisperCppEngine) {
            val modelId = engineConfig.modelId ?: WhisperModels.default.id
            trace?.modelWarm = graph.whisperRuntime.isLoaded(graph.whisperModels.fileFor(modelId).path)
            // From here on the timings are this run's.
            graph.whisperRuntime.takeTimings()
        }

        try {
            // A recognizer that never calls back, or a server that accepts the upload and
            // goes quiet, would otherwise leave the bubble spinning with no way out but
            // force-stopping the app. Time spent paused does not count.
            val finished = withPausableTimeout(settings.capture.effectiveTotalTimeoutMs(), pause, SystemClock::elapsedRealtime) {
                val transcript = when (engine) {
                    is SelfCapturingSttEngine -> listenWithEngine(engine, options)
                    else -> recordThenTranscribe(engine, engineConfig, options, source, estimateKey = "${engineConfig.id}/${engineConfig.modelId}", appLabels, pause)
                } ?: return@withPausableTimeout Unit // the failure was already reported

                if (_mode.value == RunMode.COMMAND) runCommand(transcript, appLabels) else deliver(cleanUp(transcript), settings, engineConfig.id)
            }

            if (finished == null) {
                val limit = settings.capture.effectiveTotalTimeoutMs()
                fail("Timed out - the dictation was cancelled.", engineConfig.id, details = listOf("stopped: the run's time limit of $limit ms ran out"))
            }
        } catch (e: CancellationException) {
            // A command a tap stopped that has no record yet - while it was still being
            // recognized, or anywhere runCommand did not keep one: exactly one record, with
            // the steps so far.
            if (stoppedByTap && _mode.value == RunMode.COMMAND && !commandRecorded) {
                commandRecorded = true
                val steps = commandSteps
                graph.dictationLog.recordFailure(
                    "Stopped by a tap.", engineConfig.id, trace?.summary(), command = true,
                    details = if (steps == null) listOf("stopped by a tap before the command ran")
                    else steps.apply { note("stopped by a tap") }.lines,
                )
            }
            // cancel() already set the state, and a cancelled parent scope is not a dictation
            // error. Rethrow so structured concurrency still sees it.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "dictation failed", e)
            fail("Unexpected error: ${e.message ?: e::class.simpleName}", details = listOf("unexpected ${e::class.java.name}"))
        } finally {
            selfCapturing = null
            stopRequested.set(false)
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
            fail(userMessageOf(error), remedy = remedyOf(error), details = listOf("recognition failed: ${technicalOf(error)}"))
            null
        }
    }

    /**
     * A forgotten pause ran out with nothing worth sending: no error, the bubble just rests
     * again - and Diagnostics say why the recording ended.
     */
    private fun endQuietlyAfterPause(pause: RecordingPause) {
        val now = SystemClock.elapsedRealtime()
        trace?.recordingEnds()
        trace?.pausedMs = pause.pausedMs(now)
        trace?.paused = pause.summary(now)
        Log.i(TAG, "recording ended: paused too long, nothing recorded")
        graph.dictationLog.recordFailure(
            "Paused for more than ${RecordingPause.MAX_PAUSE_MS / 60_000} minutes with nothing recorded - the recording ended.",
            timing = trace?.summary(),
        )
        _state.value = DictationState.Idle
    }

    private suspend fun recordThenTranscribe(
        engine: SttEngine,
        engineConfig: EngineConfig,
        options: TranscribeOptions,
        source: TriggerSource,
        /** Engine and model, for learning how long recognition takes here. */
        estimateKey: String,
        /** The installed apps' labels for a command's app names, read once per run ([appLabelsOnce]). */
        appLabels: () -> List<String>,
        /** This run's pause from the bubble's menu. */
        pause: RecordingPause,
    ): Transcript? {
        _state.value = DictationState.Listening()

        trace?.recordingStarts()
        val vadConfig = graph.settings.value.capture.let { capture ->
            // A command started as one ends when the words do, whatever the user chose for
            // dictations. One a double tap turned into a command keeps the recording it began
            // with: it ends on silence if dictations do, else by a tap or after the cap.
            if (_mode.value == RunMode.COMMAND) capture.copy(autoStopOnSilence = true, maxDurationMs = CommandTrigger.COMMAND_MAX_MS) else capture
        }.toVadConfig()
        // Finished parts of a longer dictation are recognized while the user is still speaking,
        // so stopping leaves only the last part to wait for.
        // A child of this dictation's job: cancelling the dictation stops the chunk work too.
        val captureSettings = graph.settings.value.capture
        val chunked = if (_mode.value == RunMode.DICTATION && captureSettings.chunking && engine.capabilities.chunkable) {
            ChunkedTranscription(engine, options, CoroutineScope(currentCoroutineContext()))
        } else {
            null
        }
        // A server dictation whose fallback is a model on the phone may have it recognize the
        // recording alongside, so its text is there the moment the server fails or hangs.
        val prepared = if (_mode.value == RunMode.DICTATION && chunked == null) prepareFallback(engineConfig) else null
        val chunkSink = chunked ?: prepared?.chunks
        val outcome = try {
            capture.record(
                config = vadConfig,
                stopRequested = { stopRequested.get() },
                onAmplitude = { amplitude -> updateListening { it.copy(amplitude = amplitude) } },
                onChunk = chunkSink?.let { it::submit },
                minChunkMs = captureSettings.chunkMinMs ?: (prepared?.engine ?: engine).capabilities.minChunkMs,
                pause = pause,
            )
        } catch (e: Throwable) {
            chunked?.cancel()
            prepared?.chunks?.cancel()
            throw e
        }
        if (outcome !is AudioCapture.Outcome.Captured || outcome.chunks == 0) chunked?.cancel()
        if (outcome !is AudioCapture.Outcome.Captured) prepared?.chunks?.cancel()

        when (outcome) {
            is AudioCapture.Outcome.Discarded -> {
                if (outcome.reason == DiscardReason.PAUSED_TOO_LONG) {
                    endQuietlyAfterPause(pause)
                    return null
                }
                // Never silent:
                trace?.pausedMs = pause.pausedMs(SystemClock.elapsedRealtime())
                trace?.paused = pause.summary(SystemClock.elapsedRealtime())
                // Never silent: a dictation that ends with no tick and no text and no word why
                // looks exactly like a bug. Both cases get a message and a diagnostics line.
                fail(
                    when (outcome.reason) {
                        DiscardReason.TOO_SHORT -> "Too short - please speak a little longer."
                        DiscardReason.NO_SPEECH, DiscardReason.PAUSED_TOO_LONG -> "Nothing heard - try again a little closer to the phone."
                    },
                    details = listOf(
                        when (outcome.reason) {
                            DiscardReason.TOO_SHORT -> "recording discarded: too short"
                            DiscardReason.NO_SPEECH, DiscardReason.PAUSED_TOO_LONG -> "recording discarded: no speech detected"
                        },
                    ),
                )
                return null
            }

            is AudioCapture.Outcome.Failed -> {
                Log.w(TAG, "capture failed: ${outcome.technical}")
                // Classic symptom of a ROM blocking the microphone for a background
                // service. Retry once through the activity path before giving up.
                if (source.mayUseProxyFallback) {
                    Log.i(TAG, "retrying capture via proxy activity")
                    if (handover.begin(SystemClock.elapsedRealtime())) MicProxyActivity.launch(context)
                } else {
                    fail(outcome.message, details = listOf("capture failed: ${outcome.technical}"))
                }
                return null
            }

            is AudioCapture.Outcome.Captured -> {
                trace?.recordingEnds()
                // Ended while paused: the pause ends with the recording.
                pause.resume(SystemClock.elapsedRealtime())
                trace?.pausedMs = pause.pausedMs(SystemClock.elapsedRealtime())
                trace?.paused = pause.summary(SystemClock.elapsedRealtime())
                // Decided now, not when recording began: a double tap may have turned the
                // dictation into a command meanwhile. A command is heard whole, a language at
                // a time, so chunks the dictation began with are dropped - and stopped before
                // the engine gets the command.
                val command = _mode.value == RunMode.COMMAND
                if (command) chunked?.cancelAndJoin()
                // A command is heard by its engine alone; nothing prepared stands in for it.
                if (command) prepared?.chunks?.cancelAndJoin()
                val ready = prepared.takeUnless { command }
                val chunking = !command && chunked != null && outcome.chunks > 0
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
                val estimate = graph.recognitionEstimate
                val server = engine is OpenAiCompatibleEngine
                val uploadKey = "upload/$estimateKey"
                val recognizeMs = estimate.expectedMs(estimateKey, pendingMs, caps.typicalRate, overheadMs)
                val uploadMs = if (server) estimate.expectedMs(uploadKey, outcome.audio.durationMs, UPLOAD_RATE, 0) else 0L
                val cleanupMs = if (command) null else expectedCleanupMs(graph.settings.value, (outcome.audio.durationMs * CHARS_PER_AUDIO_MS).toLong(), fellBackFrom = null)
                // One ring from the release to the text in the field: upload, recognition, cleanup.
                val wait = WaitProgress(SystemClock::elapsedRealtime).also { this.wait = it }
                wait.plan(
                    *listOfNotNull(
                        (WaitProgress.Step.UPLOAD to uploadMs).takeIf { server },
                        WaitProgress.Step.RECOGNIZE to recognizeMs,
                        cleanupMs?.let { WaitProgress.Step.CLEANUP to it },
                    ).toTypedArray(),
                )
                // The bytes sent fill the upload's share; once they are out, the server recognizes.
                val uploadFrom = AtomicLong(0)
                val uploadTo = AtomicLong(0)
                val serverOptions = if (!server) options else options.copy(
                    onUpload = { sent, total ->
                        uploadFrom.compareAndSet(0, SystemClock.elapsedRealtime())
                        if (total > 0) wait.report(WaitProgress.Step.UPLOAD, sent.toFloat() / total)
                        if (total in 1..sent) {
                            uploadTo.compareAndSet(0, SystemClock.elapsedRealtime())
                            wait.begin(WaitProgress.Step.RECOGNIZE)
                        }
                    },
                )
                // Each chunk done is a real step of the recognition.
                if (chunking && pendingMs > 0) {
                    chunked!!.onChunkDone = { left -> wait.report(WaitProgress.Step.RECOGNIZE, 1f - left.toFloat() / pendingMs, upTo = 1f) }
                }
                _state.value = DictationState.Transcribing(wait)
                trace?.transcribingStarts()
                trace?.connection = connectionWarmUp?.describe()
                val startedAt = SystemClock.elapsedRealtime()
                var passes = 1
                var tookOver: ReadyFallback.Outcome.Local? = null
                val result = when {
                    chunking -> chunked!!.finish(outcome.tail, outcome.audio).also { trace?.chunks = chunked.chunkCount }
                    command -> recognizeCommand(engine, outcome.audio, options, appLabels).also { passes = it.passes }.result
                    ready != null -> when (val race = raceWithPrepared(engine, serverOptions, outcome, ready, uploadMs + recognizeMs)) {
                        is ReadyFallback.Outcome.Server -> race.result
                        is ReadyFallback.Outcome.Local -> Result.success(race.transcript).also { tookOver = race }
                    }
                    else -> engine.transcribe(outcome.audio, serverOptions)
                }
                trace?.transcribingEnds()
                trace?.upload = result.getOrNull()?.upload?.summary()
                // A cold model's load time would teach the wrong rate, and so would a command
                // heard in several languages, or a text the phone recognized instead. The
                // upload is learned on its own: on the road it can take longer than the server.
                if (result.isSuccess && !coldModel && passes == 1 && tookOver == null) {
                    val upload = if (uploadTo.get() > 0) uploadTo.get() - uploadFrom.get() else 0L
                    estimate.learn(estimateKey, pendingMs, SystemClock.elapsedRealtime() - startedAt - upload, caps.overheadMs)
                    if (upload > 0) estimate.learn(uploadKey, outcome.audio.durationMs, upload, 0)
                    graph.saveEstimates()
                }
                if (engine is WhisperCppEngine) {
                    trace?.whisperScheduling = graph.whisperRuntime.schedulingSummary()
                    trace?.whisperTimings = graph.whisperRuntime.takeTimings()?.summary()
                }
                tookOver?.let { local -> return tookOverFromServer(local, ready!!.config, engine.capabilities.trustLevel) }
                // No signal, a timeout, a server down: the recording is still here, so another
                // engine can take it instead of the dictation being lost - not the one prepared
                // alongside, whose text did not come.
                val recoverable = Fallback.recoverable(
                    result.exceptionOrNull(),
                    engine.capabilities.trustLevel,
                    graph.settings.value.fallback.mode,
                )
                val recovered = recoverable
                    ?.let { recognizeElsewhere(outcome.audio, it, engine.capabilities.trustLevel, skip = ready?.config?.id) }
                    ?.let { Result.success(it) } ?: result
                return recovered.getOrElse { error ->
                    fail(userMessageOf(error), remedy = remedyOf(error), details = listOf("recognition failed: ${technicalOf(error)}"))
                    null
                }
            }
        }
    }

    /** A fallback on the phone recognizing this recording alongside the server ([ReadyFallback]). */
    private class Prepared(val config: EngineConfig, val engine: SttEngine, val options: TranscribeOptions, val chunks: ChunkedTranscription)

    /**
     * The fallback model to prepare while [primary] records, if the user switched preparing on
     * and the model is downloaded: it starts loading now, and finished chunks of the recording
     * go to it. Null otherwise - and always for an engine on the phone.
     */
    private suspend fun prepareFallback(primary: EngineConfig): Prepared? {
        val config = ReadyFallback.engineToPrepare(graph.settings.value, primary, ::trustOf, ::localModelReady) ?: return null
        val engine = graph.engineFactory.create(config).getOrNull() ?: return null
        val scope = CoroutineScope(currentCoroutineContext())
        // The model loads while the user speaks; a failure shows when it is asked for text.
        scope.launch { engine.warmUp() }
        val options = TranscribeOptions(languageTag = config.languageTag)
        return Prepared(config, engine, options, ChunkedTranscription(engine, options, scope))
    }

    private fun trustOf(config: EngineConfig): TrustLevel =
        EngineTrust.forDisplay(config, onDeviceSpeech = AndroidOnDeviceEngine.onDeviceSpeech)

    /** Whether a model on the phone is downloaded; an engine elsewhere needs none. */
    private fun localModelReady(config: EngineConfig): Boolean = when (config.kind) {
        EngineKind.LOCAL_SHERPA -> graph.parakeetModels.isReady()
        EngineKind.WHISPER_CPP -> graph.whisperModels.isReady(config.modelId ?: WhisperModels.default.id)
        else -> true
    }

    /** Whether a model on the phone is in memory already; null for an engine elsewhere. */
    private fun localModelWarm(config: EngineConfig): Boolean? = when (config.kind) {
        EngineKind.LOCAL_SHERPA -> graph.sherpaRuntime.isLoaded(graph.parakeetModels.dir.path)
        EngineKind.WHISPER_CPP -> graph.whisperRuntime.isLoaded(graph.whisperModels.fileFor(config.modelId ?: WhisperModels.default.id).path)
        else -> null
    }

    /**
     * The server's answer against the text [ready] prepares on the phone ([ReadyFallback.race]):
     * the rest of the recording is recognized there while the server works. Whatever comes of
     * it, the work on the phone stops when this returns. Diagnostics get one line on it.
     */
    private suspend fun raceWithPrepared(
        engine: SttEngine,
        options: TranscribeOptions,
        captured: AudioCapture.Outcome.Captured,
        ready: Prepared,
        expectedServerMs: Long,
    ): ReadyFallback.Outcome {
        val stoppedAt = SystemClock.elapsedRealtime()
        val localDoneAt = AtomicLong(0)
        val localOk = AtomicBoolean(false)
        val local = CoroutineScope(currentCoroutineContext()).async {
            try {
                if (captured.chunks > 0) {
                    ready.chunks.finish(captured.tail, captured.audio)
                } else {
                    ready.chunks.cancel()
                    ready.engine.transcribe(captured.audio, ready.options)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }.also {
                localOk.set(it.isSuccess)
                localDoneAt.set(SystemClock.elapsedRealtime())
            }
        }
        val deadlineMs = ReadyFallback.deadlineMs(expectedServerMs)
        try {
            val outcome = ReadyFallback.race({ engine.transcribe(captured.audio, options) }, local, deadlineMs) {
                Fallback.recoverable(it, engine.capabilities.trustLevel, graph.settings.value.fallback.mode)
            }
            val readyAfter = localDoneAt.get().takeIf { it > 0 }?.let { at ->
                val text = if (localOk.get()) "ready" else "failed"
                "$text ${ms(at - stoppedAt)} after stop"
            } ?: "not ready yet"
            val who = when (outcome) {
                is ReadyFallback.Outcome.Server -> if (outcome.result.isSuccess) "the server answered first" else "the server's error stands"
                is ReadyFallback.Outcome.Local ->
                    if (outcome.serverTooSlow) "the server took over ${ms(deadlineMs)}, the phone's text was used"
                    else "the server failed, the phone's text was used"
            }
            trace?.prepared = "prepared on the phone with ${ready.config.displayName}, ${ready.chunks.chunkCount} chunks, $readyAfter; $who"
            return outcome
        } finally {
            local.cancel()
            ready.chunks.cancel()
        }
    }

    /**
     * The text prepared on the phone took over from the server: labelled with where the
     * recording may have gone, said in a toast and in Diagnostics, as any fallback is.
     */
    private fun tookOverFromServer(local: ReadyFallback.Outcome.Local, config: EngineConfig, serverTrust: TrustLevel): Transcript {
        val transcript = local.transcript
        val trust = local.serverFailure?.let { Fallback.trustAfter(it, serverTrust, transcript.trustLevel) }
            ?: Fallback.trustAfterSlowServer(serverTrust, transcript.trustLevel)
        val primary = graph.settings.value.activeEngine
        fellBack = config.displayName
        fellBackFrom = primary?.id
        val why = if (local.serverTooSlow) "server too slow" else "server unreachable"
        trace?.fallback = "$why, recognized by ${config.displayName} during the recording, label $trust"
        val message = when {
            local.serverTooSlow -> "The server took too long - used the text recognized on the phone."
            trust == TrustLevel.ON_DEVICE -> "No connection - recognized on the phone with ${config.displayName}."
            else -> "The server did not answer - recognized on the phone with ${config.displayName}. " +
                "The recording may have reached the server."
        }
        mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        return transcript.copy(trustLevel = trust)
    }

    /**
     * How long the cleanup chosen for this dictation's app should take for about [chars]
     * characters, learned per cleanup model; null when it gets none.
     */
    private fun expectedCleanupMs(settings: AmethystSettings, chars: Long, fellBackFrom: String?): Long? {
        val target = startTarget?.packageName ?: graph.accessibilityService?.targetPackage()
        val choice = CleanupRouting.decide(settings, target, fellBackFrom) { graph.secretStore.has(it) }.choice ?: return null
        return graph.recognitionEstimate.expectedMs(cleanupKey(choice), chars, CLEANUP_RATE, CLEANUP_OVERHEAD_MS)
    }

    private fun cleanupKey(choice: CleanupChoice) = "cleanup/${choice.engineId}/${choice.model}"

    /**
     * A command's words: where the engine takes a language per request, heard a language at a
     * time - the phone's first - until the rules understand them (CommandLanguages). Every pass
     * goes to Diagnostics as it ends, never to logcat.
     */
    private suspend fun recognizeCommand(
        engine: SttEngine,
        audio: PcmAudio,
        options: TranscribeOptions,
        appLabels: () -> List<String>,
    ): CommandLanguages.Recognition = CommandLanguages.recognize(
        engine,
        audio,
        options,
        phoneLanguage = phoneLanguage(),
        appLabels = appLabels,
        readOn = Dispatchers.IO,
        // The trace begins with the first pass: a tap before it still reads "before the command ran".
        note = { line -> (commandSteps ?: CommandTrace().also { commandSteps = it }).note(line) },
    )

    /**
     * Opens the server connections this run will need while the user is still speaking: the
     * speech engine's - for an enclave the attestation and the pinned connection - and, for a
     * dictation, that of the cleanup chosen for the app being written in. Off the main thread
     * and in the app's scope, so nothing waits for them. A HEAD to the API root with no
     * Authorization header and no audio - an Amber PC or Server has its key in the address's
     * path, so it goes there as with every request, never logged - and a failure changes
     * nothing ([ConnectionWarmUp]). One per server at a time: a warm-up still running for the
     * same API root - an earlier tap's, or the speech engine's for a cleanup on its server - is
     * joined ([ConnectionWarmUp.Pool]). A model on the phone needs none.
     */
    private fun warmUpConnections(engine: SttEngine, engineConfig: EngineConfig, settings: AmethystSettings) {
        connectionWarmUp = null
        cleanupWarmUp = null
        val server = engine as? OpenAiCompatibleEngine
        val serverUrl = engineConfig.baseUrl
        if (server != null && serverUrl != null) {
            connectionWarmUp = warmUps.start(OpenAiCompatibleEngine.apiRoot(serverUrl)) {
                withContext(Dispatchers.IO) { server.warmUp().isSuccess }
            }
        }
        if (_mode.value != RunMode.DICTATION) return
        val target = startTarget?.packageName ?: graph.accessibilityService?.targetPackage()
        val choice = CleanupRouting.decide(settings, target, fellBackFrom = null) { graph.secretStore.has(it) }.choice ?: return
        val cleanupUrl = settings.engines.firstOrNull { it.id == choice.engineId }?.baseUrl ?: return
        val cleanup = graph.engineFactory.cleanupFor(choice, settings) ?: return
        cleanupWarmUp = choice to warmUps.start(OpenAiCompatibleEngine.apiRoot(cleanupUrl)) {
            withContext(Dispatchers.IO) { cleanup.warmUp() }
        }
    }

    /** The language the phone is set to ("de") - not Amber's own, should it have one. */
    private fun phoneLanguage(): String? =
        Resources.getSystem().configuration.locales.takeUnless { it.isEmpty }?.get(0)?.language

    /**
     * The installed apps' launcher labels, for a bare app name: taken once per command when the
     * rules first need them - picking a language, then running the command - and not at all
     * while commands are off. Asked for on the IO pool only; the list itself is the process's
     * one ([app.amethyst.apps.LauncherList]), read again only after a package changed.
     */
    private fun appLabelsOnce(): () -> List<String> {
        val enabled = graph.settings.value.commandsEnabled
        val labels by lazy { if (enabled) graph.launcherList.appsBlocking().map { it.label } else emptyList() }
        return { labels }
    }

    /**
     * The fallback the user chose (by default the best local model that is ready - Parakeet,
     * then Whisper) for a recording the server could not take. Null when none is ready or none
     * recognized it; the original error then stands.
     */
    private suspend fun recognizeElsewhere(audio: PcmAudio, cause: SttFailure, serverTrust: TrustLevel, skip: String? = null): Transcript? {
        val settings = graph.settings.value
        val primary = settings.activeEngine ?: return null
        val candidates = Fallback.candidates(settings.fallback, settings.engines, primary, ::trustOf).filter { it.id != skip }
        for (config in candidates) {
            val other = graph.engineFactory.create(config).getOrNull() ?: continue
            Log.i(TAG, "server unavailable (${cause.technical.take(80)}) - recognizing with ${config.id}")
            // The rest of the ring is this engine's recognition and the cleanup after it.
            val caps = other.capabilities
            val load = if (localModelWarm(config) == false) MODEL_LOAD_MS else 0L
            val recognizeMs = graph.recognitionEstimate.expectedMs("${config.id}/${config.modelId}", audio.durationMs, caps.typicalRate, caps.overheadMs + load)
            val cleanupMs = expectedCleanupMs(settings, (audio.durationMs * CHARS_PER_AUDIO_MS).toLong(), fellBackFrom = primary.id)
            val wait = wait ?: WaitProgress(SystemClock::elapsedRealtime).also { wait = it }
            wait.plan(*listOfNotNull(WaitProgress.Step.RECOGNIZE to recognizeMs, cleanupMs?.let { WaitProgress.Step.CLEANUP to it }).toTypedArray())
            _state.value = DictationState.Transcribing(wait)
            val result = try {
                other.transcribe(audio, TranscribeOptions(languageTag = config.languageTag))
            } finally {
                runCatching { other.close() }
            }
            result.getOrNull()?.let { transcript ->
                // A read timeout or a 5xx came after the upload: the recording may be the
                // first server's too - and the label says so, as it says the fallback's own.
                val trust = Fallback.trustAfter(cause, serverTrust, transcript.trustLevel)
                fellBack = config.displayName
                fellBackFrom = primary.id
                trace?.fallback = "server unreachable, recognized by ${config.displayName}, label $trust"
                val local = transcript.trustLevel == TrustLevel.ON_DEVICE
                val message = when {
                    trust == TrustLevel.ON_DEVICE -> "No connection - recognized on the phone with ${config.displayName}."
                    local -> "The server did not answer - recognized on the phone with ${config.displayName}. " +
                        "The recording may have reached the server."
                    else -> "${primary.displayName} did not answer - recognized by ${config.displayName} instead."
                }
                mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
                return transcript.copy(trustLevel = trust)
            }
        }
        return null
    }

    /**
     * Runs the cleanup chosen for the target app (its own, else the one for every app) over
     * the transcript, if any. Never fails the dictation: on an error, a timeout or an
     * implausible answer the transcript is kept as recognized, and the diagnostics say why.
     * The bubble stays on "working" meanwhile.
     */
    private suspend fun cleanUp(transcript: Transcript): Transcript {
        // Both the cleanup and the style belong to the app whose field gets the text; after
        // the proxy activity that is only known once focus is back.
        awaitFocusReturn()
        val settings = graph.settings.value
        val target = startTarget?.packageName ?: graph.accessibilityService?.targetPackage()
        val decision = CleanupRouting.decide(settings, target, fellBackFrom) { graph.secretStore.has(it) }
        // May name the app (its style's label), so the trace keeps it apart from the rest.
        trace?.cleanupNote = decision.note
        val choice = decision.choice ?: return transcript
        val skip = skipCleanup
        if (skip.isCompleted) {
            trace?.cleanup = "cleanup skipped from the quick menu"
            return transcript
        }
        val cleanup = graph.engineFactory.cleanupFor(choice, settings)
            ?: return transcript.also { trace?.cleanup = "cleanup ${choice.model} could not be set up" }
        val profile = AppProfiles.forPackage(settings.profiles, target)
        val style = profile?.style ?: WritingStyle.AS_SPOKEN
        // Whether its server connection was opened at the start - only for the cleanup chosen then.
        val connection = cleanupWarmUp?.takeIf { it.first == choice }?.let { ", ${it.second.describe()}" }.orEmpty()
        // The rest of the ring: a streamed reply fills it as it comes, else the clock against
        // what this model took here before.
        val key = cleanupKey(choice)
        val chars = transcript.text.length.toLong()
        wait?.plan(WaitProgress.Step.CLEANUP to graph.recognitionEstimate.expectedMs(key, chars, CLEANUP_RATE, CLEANUP_OVERHEAD_MS))
        // Named in the prompt and checked in the reply: what the engine detected, the one
        // language it was set to, or what the words clearly are.
        val language = LanguageGuess.forDictation(
            transcript.text,
            transcript.detectedLanguage,
            settings.engines.firstOrNull { it.id == transcript.engineId }?.languageTag,
        )
        val result = withTimeoutOrNull(CLEANUP_TIMEOUT_MS) {
            coroutineScope {
                val work = async {
                    cleanup.clean(
                        transcript.text,
                        style,
                        instruction = profile?.instruction,
                        generalInstruction = settings.cleanupInstruction,
                        language = language,
                    ) { done -> wait?.report(WaitProgress.Step.CLEANUP, done) }
                }
                // "As recognized" from the quick menu wins over a cleanup still on its way.
                select {
                    work.onAwait { it }
                    skip.onAwait {
                        work.cancel()
                        null
                    }
                }
            }
        }
        if (result == null) {
            trace?.cleanup = if (skip.isCompleted) "cleanup dropped from the quick menu (${cleanup.model})" else "cleanup timed out (${cleanup.model})"
            return transcript
        }
        return result.fold(
            onSuccess = { cleaned ->
                val styleNote = if (style == WritingStyle.AS_SPOKEN) "" else ", ${style.name.lowercase()}"
                val languageNote = language?.let { ", $it" }.orEmpty()
                trace?.cleanup = "cleanup ${cleaned.latencyMs} ms (${cleanup.model}$styleNote$languageNote$connection)"
                graph.recognitionEstimate.learn(key, chars, cleaned.latencyMs, CLEANUP_OVERHEAD_MS)
                graph.saveEstimates()
                trace?.cleanupModel = cleanup.model
                trace?.cleanupEngineId = choice.engineId
                transcript.copy(
                    text = cleaned.text,
                    formatted = true,
                    // The text is only as trustworthy as the weaker of the two trips it made.
                    trustLevel = listOf(transcript.trustLevel, cleaned.trust).maxBy { it.strictness },
                )
            },
            onFailure = { error ->
                trace?.cleanup = "cleanup skipped: ${userMessageOf(error)}"
                // The error's own text may quote the model's reply, i.e. the dictation.
                Log.w(TAG, "cleanup failed: ${(error as? SttFailure)?.technical?.take(80) ?: error.javaClass.simpleName}")
                transcript
            },
        )
    }

    /**
     * A recognized command: understood by the rules, carried out by the accessibility service
     * (which refuses while commands are off), kept in Diagnostics - never typed anywhere and
     * not counted in the statistics. Several commands said in one go run one after another
     * ([CommandChain]), each through the service's gate and field check, until one is not done.
     * Diagnostics get what the rules made of the words and every step the executor took
     * ([CommandTrace]); logcat gets neither.
     */
    private suspend fun runCommand(transcript: Transcript, appLabels: () -> List<String> = appLabelsOnce()) {
        // After the proxy activity, "back" would close the proxy instead of the user's screen.
        awaitFocusReturn()
        // Recognition may have begun the trace with the languages the words were heard in.
        val steps = commandSteps ?: CommandTrace().also { commandSteps = it }
        val heard = transcript.text.trim()
        if (heard.isEmpty()) {
            fail("Nothing heard - say the command again.", transcript.engineId, details = listOf("the engine recognized no words"))
            return
        }
        // A bare app name ("WhatsApp") is a command only if it names an installed app ([appLabels]:
        // read once, and not while commands are off).
        val enabled = graph.settings.value.commandsEnabled
        // From reading the words to the last step, a tap may stop it: the record then says so.
        var chain: CommandChain.Run? = null
        val (understanding, outcome) = try {
            val parsed = withContext(Dispatchers.IO) {
                VoiceCommands.parse(heard, appLabels).also { steps.addAll(VoiceCommands.explain(heard, appLabels)) }
            }
            if (!enabled && parsed is Understanding.NotUnderstood) steps.note("voice commands are off: no app list was read for bare names")
            parsed to when (parsed) {
                is Understanding.NotUnderstood -> CommandOutcome.Failed(
                    "Not a command: \u201C${heard.take(SHOWN_CHARS)}\u201D. Try \u201Copen WhatsApp\u201D or \u201Cgo back\u201D.",
                )
                is Understanding.Understood -> {
                    val executor = graph.accessibilityService?.commands
                    if (executor == null) {
                        CommandOutcome.Failed("Amber's accessibility service is off.").also { steps.note("accessibility service: off - nothing done") }
                    } else {
                        // A text command changes only the field it was started in, checked as a dictation's
                        // text is (EditTarget) - recognition took seconds, the user may be in another chat
                        // now. Each of several commands is checked against that same field.
                        CommandChain.run(parsed.commands, steps, pause = { delay(it) }) { command ->
                            executor.execute(command, startedIn = startTarget, steps)
                        }.also { chain = it }.outcome
                    }
                }
            }
        } catch (e: CancellationException) {
            // The trace says how far it got.
            if (stoppedByTap && !commandRecorded) {
                commandRecorded = true
                steps.note("stopped by a tap")
                graph.dictationLog.recordCommand(heard, CommandOutcome.Refused("Stopped by a tap."), transcript.engineId, trace?.summary(), steps.lines)
            }
            throw e
        }
        steps.note(
            chain?.line ?: ("outcome: " + when (outcome) {
                is CommandOutcome.Done -> "done"
                is CommandOutcome.Refused -> "refused"
                is CommandOutcome.Failed -> "failed"
            }),
        )
        commandRecorded = true
        graph.dictationLog.recordCommand(heard, outcome, transcript.engineId, trace?.summary(), steps.lines)
        if (outcome !is CommandOutcome.Done) {
            // Logged just above, with what was heard.
            fail(outcome.message, transcript.engineId, record = false, logAs = "command not done")
            return
        }
        _state.value = DictationState.Commanded(heard, outcome.message)
        // A toast would end up in the screenshot, and on the lock screen it says nothing new -
        // also when that was one of several commands.
        val actions = (understanding as? Understanding.Understood)?.commands.orEmpty().mapNotNull { (it as? Command.Navigate)?.action }
        if (GlobalAction.SCREENSHOT !in actions && GlobalAction.LOCK_SCREEN !in actions) {
            mainHandler.post {
                Toast.makeText(context, "\u201C${heard.take(SHOWN_CHARS)}\u201D \u2192 ${outcome.message}", Toast.LENGTH_SHORT).show()
            }
        }
        scope.launch {
            delay(DONE_DISPLAY_MS)
            if (_state.value is DictationState.Commanded) _state.value = DictationState.Idle
        }
    }

    /**
     * A typed command through the same path, for the debug build's adb test hook (src/debug).
     * The field that has focus now counts as the one the command was started in.
     */
    internal suspend fun commandForTest(text: String) {
        _mode.value = RunMode.COMMAND
        stoppedByTap = false
        commandSteps = null
        commandRecorded = false
        trace = RunTrace("TEST/COMMAND")
        startTarget = graph.accessibilityService?.focusedTarget()
        runCommand(Transcript(text = text, engineId = "test", trustLevel = TrustLevel.ON_DEVICE, latencyMs = 0))
        _mode.value = RunMode.DICTATION
    }

    /**
     * A ready-made text through the same delivery a dictation takes - normalizing, the target
     * check, every insertion method - for the debug build's adb test hook (src/debug). The
     * field that has focus now counts as the one the dictation started in.
     */
    internal suspend fun deliverForTest(text: String, formatted: Boolean) {
        startTarget = graph.accessibilityService?.focusedTarget()
        val transcript = Transcript(
            text = text,
            engineId = "debug-test",
            trustLevel = TrustLevel.ON_DEVICE,
            latencyMs = 0,
            formatted = formatted,
        )
        deliver(transcript, graph.settings.value, transcript.engineId)
    }

    private suspend fun deliver(
        transcript: Transcript,
        settings: app.amethyst.core.settings.AmethystSettings,
        engineId: String,
    ) {
        val cleaned = TranscriptNormalizer.normalize(
            transcript.text,
            settings.capture.toNormalizeOptions(),
            language = transcript.detectedLanguage ?: settings.engines.firstOrNull { it.id == engineId }?.languageTag,
            // Whether the field takes them is decided at insertion (InsertionEngine).
            keepLineBreaks = transcript.formatted,
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

        val now = graph.accessibilityService?.focusedTarget()
        val verdict = TargetCheck.verdict(startTarget, now)
        if (verdict != TargetCheck.Verdict.INSERT) {
            Log.i(TAG, "not inserting: $verdict (start ${DebugLog.redact(startTarget?.packageName)}, now ${DebugLog.redact(now?.packageName)})")
            val why = when (verdict) {
                TargetCheck.Verdict.SECRET -> "The field now is a password field"
                TargetCheck.Verdict.NO_FIELD -> "No text field has focus any more"
                else -> "You switched to another app, chat or field meanwhile"
            }
            // Never into a password field, not even via the clipboard. Otherwise only when the
            // user allows the clipboard at all; else the text stays in the diagnostics.
            val copied = verdict != TargetCheck.Verdict.SECRET &&
                settings.insertion.allowClipboard && inserter.copyToClipboard(cleaned)
            if (copied) {
                fail("$why - the text is on the clipboard instead.", engineId)
            } else {
                fail(
                    "$why - not inserted: \u201C$cleaned\u201D",
                    engineId,
                    brief = "$why - not inserted. The text is in Amber's diagnostics.",
                )
            }
            return
        }

        trace?.insertingStarts()
        val insertion = inserter.insert(
            cleaned,
            settings.insertion.toSpliceOptions(),
            allowClipboard = settings.insertion.allowClipboard,
        )
        trace?.insertingEnds()
        Log.i(
            TAG,
            "insertion via ${insertion.strategy} into ${DebugLog.redact(insertion.targetPackage)}: " +
                "field=${insertion.insertedIntoField} clipboard=${insertion.copiedToClipboard} " +
                "(engine=${transcript.engineId}, trust=${transcript.trustLevel}, " +
                "${transcript.latencyMs}ms)",
        )

        if (!insertion.insertedIntoField && !insertion.copiedToClipboard) {
            // Neither the field nor the clipboard took it. Quote the words in the message so
            // they survive in the diagnostics card instead of being lost.
            val what = if (insertion.uncertain) "The text was typed but never showed up in the field"
            else "No text field accepted the text"
            fail(
                "$what: \u201C$cleaned\u201D [${inserter.lastTrace}]",
                engineId,
                brief = "$what. It is in Amber's diagnostics.",
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
        trace?.let { Log.i(TAG, "dictation timing: ${it.summary(namesApps = DebugLog.enabled)}") }
        recordStat(finalTranscript, insertion.targetPackage, settings)

        _state.value = DictationState.Done(
            transcript = finalTranscript,
            strategy = insertion.strategy,
            insertedIntoApp = insertion.targetPackage,
        )
        graph.bubbleEvents.tryEmit(BubbleEvent.DictationInserted)

        // Let the bubble show the result for a moment, then go back to resting. Launched
        // separately so this pause is not charged to the dictation's timeout.
        scope.launch {
            delay(DONE_DISPLAY_MS)
            if (_state.value is DictationState.Done) _state.value = DictationState.Idle
        }
    }

    /** One line for the statistics: counts and timings only, never the text. */
    private fun recordStat(
        transcript: Transcript,
        targetPackage: String?,
        settings: app.amethyst.core.settings.AmethystSettings,
    ) {
        if (!settings.stats.keep) return
        val config = settings.engines.firstOrNull { it.id == transcript.engineId }
        val audioMs = trace?.recordingMs ?: 0
        val now = System.currentTimeMillis()
        graph.stats.record(
            DictationStat(
                at = now,
                day = graph.stats.dayOf(now),
                engine = transcript.engineId,
                model = config?.modelId,
                trust = transcript.trustLevel,
                words = countWords(transcript.text),
                audioMs = audioMs,
                waitMs = trace?.sinceStopMs ?: transcript.latencyMs,
                app = (startTarget?.packageName ?: targetPackage)?.takeIf { it != context.packageName },
                cleanupModel = trace?.cleanupModel,
                // Recognition and cleanup each priced by the provider that did it; an own server
                // gives null.
                costMicroUsd = run {
                    val speech = config?.takeIf { it.kind == EngineKind.OPENAI_COMPATIBLE }
                    val cleanupUrl = settings.engines.firstOrNull { it.id == trace?.cleanupEngineId }?.baseUrl
                    ServerCost.microUsd(speech?.baseUrl, speech?.modelId, audioMs, transcript.text.length, cleanupUrl, trace?.cleanupModel)
                },
            ),
        )
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

    /** Atomic: the recorder's level and the menu's pause may arrive at once from two threads. */
    private fun updateListening(transform: (DictationState.Listening) -> DictationState.Listening) {
        _state.update { if (it is DictationState.Listening) transform(it) else it }
    }

    /** A short note over whatever app is open, from the quick menu's actions. */
    private fun toast(message: String) {
        mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    /** A toast without a failure: the state stays Idle, the bubble stays calm, nothing is logged. */
    private fun notice(message: String) {
        Log.i(TAG, "dictation not started: $message")
        mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }

    private fun fail(
        message: String,
        engineId: String? = null,
        remedy: SttFailure.Remedy? = null,
        // For the toast and logcat when the message quotes the dictated text: the toast is
        // drawn over whatever app is open, and the words stay private to Amber's own screen.
        brief: String = message,
        record: Boolean = true,
        // Logcat never gets a heard command's words, which the message may quote.
        logAs: String = brief,
        // For a command's Diagnostics: what went wrong before or around it, never logged.
        details: List<String> = emptyList(),
    ) {
        Log.w(TAG, "dictation failed: $logAs")
        if (record) {
            val command = _mode.value == RunMode.COMMAND
            if (command) {
                // The steps so far, if the words were there, then what went wrong - noted on in
                // the same trace, so its bounds and its count of left-out lines stay exact.
                val steps = commandSteps ?: CommandTrace()
                details.forEach(steps::note)
                commandRecorded = true
                graph.dictationLog.recordFailure(message, engineId ?: runEngineId, trace?.summary(), command = true, details = steps.lines)
            } else {
                graph.dictationLog.recordFailure(message, engineId, trace?.summary())
            }
        }
        _state.value = DictationState.Failed(message, remedy)

        // While dictating into another app the bubble is the only visible surface, and a red
        // ring does not say why. A toast does, without making the user switch back.
        mainHandler.post {
            Toast.makeText(context, brief, Toast.LENGTH_LONG).show()
        }

        scope.launch {
            delay(FAILED_DISPLAY_MS)
            if (_state.value is DictationState.Failed) _state.value = DictationState.Idle
        }
    }

    private fun remedyOf(error: Throwable): SttFailure.Remedy? = (error as? SttFailure)?.remedy

    /** What an engine said went wrong, for a command's Diagnostics - the technical side. */
    private fun technicalOf(error: Throwable): String = (error as? SttFailure)?.technical ?: error.toString()

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

        private fun ms(value: Long): String =
            if (value >= 1_000) "%.1f s".format(java.util.Locale.US, value / 1000.0) else "$value ms"

        /** Upload milliseconds per millisecond of audio before this phone taught better: WAV at about 5 Mbit/s. */
        const val UPLOAD_RATE = 0.05

        /** A cleanup's milliseconds per character, and its fixed cost, before this phone taught better. */
        const val CLEANUP_RATE = 8.0
        const val CLEANUP_OVERHEAD_MS = 500L

        /** About how many characters a millisecond of speech becomes - 15 a second - for planning the cleanup. */
        const val CHARS_PER_AUDIO_MS = 0.015

        /** Past this the dictation goes in as recognized rather than keep the user waiting. */
        const val CLEANUP_TIMEOUT_MS = 12_000L
        const val PROXY_HANDOVER_TIMEOUT_MS = 3_000L
        const val FAILED_DISPLAY_MS = 3_500L
        const val PROXY_EXIT_TIMEOUT_MS = 1_500L
        const val PROXY_POLL_MS = 50L
        const val FOCUS_SETTLE_MS = 200L

        /** How much of a heard command a toast or message quotes. */
        const val SHOWN_CHARS = 60
    }
}
