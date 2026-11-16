package app.amethyst.dictation

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import app.amethyst.DebugLog
import app.amethyst.Graph
import app.amethyst.R
import app.amethyst.Texts
import app.amethyst.accessibility.FieldEditor
import app.amethyst.capture.AudioCapture
import app.amethyst.capture.MicForegroundService
import app.amethyst.capture.MediaPause
import app.amethyst.capture.MicProxyActivity
import app.amethyst.capture.RecordingSounds
import app.amethyst.core.audio.DiscardReason
import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.audio.StopReason
import app.amethyst.core.bubble.BubbleEvent
import app.amethyst.core.bubble.TextFieldKind
import app.amethyst.core.capture.LoudnessLog
import app.amethyst.core.capture.MicrophoneRoute
import app.amethyst.core.capture.MicrophoneRouter
import app.amethyst.core.capture.ProxyHandover
import app.amethyst.core.capture.RecordingLimit
import app.amethyst.core.capture.RecordingCue
import app.amethyst.core.capture.StartToneMute
import app.amethyst.core.capture.RecordingPause
import app.amethyst.core.capture.RunLimit
import app.amethyst.core.capture.TriggerSource
import app.amethyst.core.capture.withPausableTimeout
import app.amethyst.core.commands.Command
import app.amethyst.core.commands.CommandChain
import app.amethyst.core.commands.CommandLanguages
import app.amethyst.core.commands.CommandOutcome
import app.amethyst.core.commands.CommandTrace
import app.amethyst.core.commands.CommandTrigger
import app.amethyst.core.commands.GlobalAction
import app.amethyst.core.commands.RunMode
import app.amethyst.core.commands.Understanding
import app.amethyst.core.commands.VoiceCommands
import app.amethyst.core.dictation.Anonymize
import app.amethyst.core.dictation.BubbleTap
import app.amethyst.core.dictation.KeptDictation
import app.amethyst.core.dictation.NotInserted
import app.amethyst.core.dictation.RunStop
import app.amethyst.core.dictation.ScreenOff
import app.amethyst.core.engine.ChunkedTranscription
import app.amethyst.core.engine.CleanupRouting
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.EngineRouting
import app.amethyst.core.engine.EngineTrust
import app.amethyst.core.engine.Fallback
import app.amethyst.core.engine.ListenCallbacks
import app.amethyst.core.engine.OpenAiCompatibleEngine
import app.amethyst.core.engine.Problem
import app.amethyst.core.engine.ReadyFallback
import app.amethyst.core.engine.SelfCapturingSttEngine
import app.amethyst.core.engine.Servers
import app.amethyst.core.engine.SttEngine
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.engine.TranscribeOptions
import app.amethyst.core.engine.WaitProgress
import app.amethyst.core.engine.sherpa.ParakeetEngine
import app.amethyst.core.engine.whisper.WhisperCppEngine
import app.amethyst.core.engine.whisper.WhisperModels
import app.amethyst.core.insert.InputTarget
import app.amethyst.core.insert.InsertionOutcome
import app.amethyst.core.insert.TargetCheck
import app.amethyst.core.insert.UndoLast
import app.amethyst.core.history.HistoryKeep
import app.amethyst.core.privacy.SecurityMode
import app.amethyst.core.net.ConnectionWarmUp
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppProfiles
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.LanguageList
import app.amethyst.core.settings.WritingStyle
import app.amethyst.core.stats.DictationStat
import app.amethyst.core.stats.ServerCost
import app.amethyst.core.stats.countWords
import app.amethyst.core.text.ChatCleanup
import app.amethyst.core.text.LanguageGuess
import app.amethyst.core.text.Transcript
import app.amethyst.core.text.ScratchThat
import app.amethyst.core.text.TranscriptNormalizer
import app.amethyst.data.PhoneLocales
import app.amethyst.engine.AndroidOnDeviceEngine
import app.amethyst.engine.ModelDownloads
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

    /** The stop a tap asks of the run in progress; a run that ended late never clears a later one's. */
    private val runStop = RunStop()

    /** This run's pause from the bubble's menu ([pause]); a new one for every run. */
    @Volatile
    private var recordingPause = RecordingPause()

    /** This run's time limit, from its start to the text in the field; null before the first run. */
    @Volatile
    private var runLimit: RunLimit? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var job: Job? = null

    /**
     * The recognizer of the run in progress, if it records by itself: a tap is passed on to
     * it. Taken back only by the run that set it ([run]).
     */
    private val selfCapturing = AtomicReference<SelfCapturingSttEngine?>(null)

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

    /**
     * The node of [startTarget]'s field, when Android's focus lookup returned an editable one:
     * found again by it when Android has lost track of the focus by the time the text is ready.
     */
    private var startNode: AccessibilityNodeInfo? = null

    /** The app in front at the start: where a dictation started without a field may end. */
    private var startApp: String? = null

    /** The local engine that took over this dictation from an unreachable server, if any. */
    private var fellBack: String? = null

    /** How far the wait after this run's recording has come, for the progress ring. */
    @Volatile
    private var wait: WaitProgress? = null

    /**
     * This dictation's recording once it is complete - what "Try again" gets, should recognition
     * fail. Only for as long as its run lasts: the run lets go of it when it ends.
     */
    @Volatile
    private var runAudio: PcmAudio? = null

    /** What is kept of a failed dictation for "Try again"; see [KeptDictation]. */
    private val kept: KeptDictation get() = graph.memory.kept

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
     * The app this run dictates into, as known at its start - its own speech recognition, if it
     * has one, hears the run ([EngineRouting]). Kept across the proxy activity's re-entry.
     */
    @Volatile
    private var runPackage: String? = null

    /**
     * The speech recognition this run uses and why: the app's own or the one for every app.
     * The fallback works from it, never from the default.
     */
    @Volatile
    private var runRouting: EngineRouting.Decision? = null

    /**
     * This run was started inside Amber (the test field on Home): it is for Amber's own field,
     * whatever app Android last named as the one in front.
     */
    @Volatile
    private var runInAmber = false

    /**
     * The app this run's text is for, whose own speech recognition and cleanup apply: the one
     * whose field had focus at the start, else the one Android names now - after the proxy
     * activity, only known once focus is back. Amber's own for a run started inside Amber: the
     * app named last would be the one before it, which never stores Amber itself.
     */
    private fun targetApp(): String? =
        if (runInAmber) context.packageName else startTarget?.packageName ?: graph.accessibilityService?.targetPackage()

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
     * What the bubble does on a tap, and what it came to ([BubbleTap.Result]): a recording
     * started, something stopped, or - while a dictation is being written - nothing, said with
     * the hint how to cancel.
     * While a command is being carried out a tap stops it at once - a misheard "zwanzigmal
     * lauter" must have a way out - and the second tap of a double tap given for that is no
     * new recording.
     */
    fun toggle(): BubbleTap.Result {
        val now = SystemClock.uptimeMillis()
        val state = _state.value
        // A run that is still starting - its microphone being opened, nothing working on it
        // yet - is a run: a tap then is its stop, not another start ([BubbleTap]).
        val action = BubbleTap.onTap(
            working = isRunning,
            starting = state is DictationState.Starting,
            listening = state is DictationState.Listening,
            mode = _mode.value,
            secondTapOfStop = CommandTrigger.isSecondTapOfStop(commandStoppedAt, now),
        )
        when (action) {
            BubbleTap.Action.STOP_RECORDING -> stop()
            BubbleTap.Action.CANCEL_COMMAND -> {
                commandStoppedAt = now
                stoppedByTap = true
                cancel()
                Log.i(TAG, "command stopped by a tap")
                mainHandler.post { Toast.makeText(context, "Command stopped.", Toast.LENGTH_SHORT).show() }
            }
            BubbleTap.Action.NOTHING -> Unit
            BubbleTap.Action.STILL_WORKING -> {
                toast(R.string.toast_still_working)
                return BubbleTap.Result.STILL_WORKING
            }
            BubbleTap.Action.START -> {
                start()
                return if (_state.value is DictationState.Starting) BubbleTap.Result.STARTED else BubbleTap.Result.HANDLED
            }
        }
        return BubbleTap.Result.HANDLED
    }

    fun start(source: TriggerSource = TriggerSource.BUBBLE) {
        if (source != TriggerSource.PROXY_ACTIVITY) retryForHistory = false
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
        // The app being dictated into picks the speech recognition: its own, or the one for
        // every app. Taken once, at the start; the proxy activity's re-entry keeps it.
        if (source != TriggerSource.PROXY_ACTIVITY) {
            takeStartTarget()
            runInAmber = source == TriggerSource.IN_APP
            runPackage = targetApp()
        }
        val routed = graph.engineFor(runPackage).engine
        // The engine chosen in the introduction may still be downloading: say so and record
        // nothing, rather than taking the words and failing - and never hand them to another engine.
        routed?.takeIf(ModelDownloads::downloadsModel)?.let(graph.modelDownloads::forEngine)?.let { download ->
            notice(Texts.downloadAtStart(context, download))
            return
        }
        if (source != TriggerSource.PROXY_ACTIVITY) {
            runSerial++
            stoppedByTap = false
            commandSteps = null
            commandRecorded = false
            runEngineId = routed?.id
            skipCleanup = CompletableDeferred()
            fellBack = null
            fellBackFrom = null
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
                fail(say(R.string.dictation_mic_permission), details = listOf("microphone permission missing"))
                return
            }

            MicrophoneRoute.VIA_PROXY_ACTIVITY -> {
                // Android 14+ refuses a background microphone start and a foreign app is in
                // front, so hand over to the invisible activity and let it call back in.
                Log.i(TAG, "no foreground anchor - routing through the proxy activity")

                handToProxy()
                return
            }

            MicrophoneRoute.START_SERVICE_THEN_RECORD -> MicForegroundService.start(context)

            MicrophoneRoute.RECORD_NOW -> Unit
        }

        // Recording directly: an activity still on its way starts nothing when it arrives.
        if (source != TriggerSource.PROXY_ACTIVITY) handover.cancel()
        graph.dictationStarting(routed)

        // Re-entry from the proxy keeps a stop that came while it was starting - otherwise a
        // tap that stopped the dictation meanwhile would leave it recording until the cap.
        // From here this run is the current one: an earlier run still unwinding - cancelled
        // during a recognition that cannot be aborted - no longer touches the stop.
        val token = runStop.begin(stoppedBefore = stoppedDuringHandover)
        // Also set here, synchronously: anything watching the state must see the dictation
        // begin before the coroutine gets its first dispatch.
        _state.value = DictationState.Starting
        job = scope.launch { run(source, token) }
    }

    /**
     * Working or stuck: once this run's wait passes twice its estimate ([WaitProgress.stuck]),
     * one toast says how to cancel - the ring says it too, pulsing slower. Ends with the run.
     */
    private suspend fun watchStuck() {
        while (true) {
            delay(STUCK_CHECK_MS)
            val waiting = wait
            if (_state.value is DictationState.Transcribing && waiting?.stuck() == true) {
                Log.i(TAG, "still working past twice the estimate")
                toast(R.string.toast_still_working, long = true)
                return
            }
        }
    }

    /**
     * Hands the run to the invisible activity, which calls back in ([start] from
     * [TriggerSource.PROXY_ACTIVITY]) once it is up. Until then the run is "starting" with
     * nothing working on it: a tap is kept as its stop ([stop]), a cancel as its end.
     */
    private fun handToProxy() {
        // Set synchronously, before the activity exists. The activity waits for the run to
        // *leave* this state; seeing Idle it would finish during its own onCreate - before
        // AudioRecord opened - and the anchor it exists to provide would be gone again.
        _state.value = DictationState.Starting
        // A start over during a handover takes the activity on its way: never two.
        if (handover.begin(SystemClock.elapsedRealtime())) {
            MicProxyActivity.launch(context)
            watchProxyHandover()
        }
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
                    say(R.string.dictation_mic_not_opened),
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
        if (!graph.settings.value.commandsEnabled || runStop.requested) return false
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
        runStop.request()
        // Before the recording began: kept by whatever starts it - the activity on its way
        // (nothing if none is), or the run itself when it gets to its recorder or recognizer.
        if (_state.value is DictationState.Starting) handover.stop()
        selfCapturing.get()?.let { engine ->
            engine.requestStop()
            // Its recording ends with this tap: the run's limit no longer covers the longest one.
            runLimit?.let { it.recordingEnded(graph.settings.value.capture.afterRecordingMs(it.elapsedMs())) }
        }
    }

    /** Throw the current recording away - also one still being handed to the proxy activity. */
    fun cancel() {
        handover.cancel()
        job?.cancel()
        _state.value = DictationState.Idle
    }

    /**
     * The screen went off - the power button: a dictation's recording ends here as a tap would
     * end it, instead of running on in the pocket ([ScreenOff]). Its text is kept when it is
     * ready ([deliver]). Main thread.
     */
    fun screenOff() {
        val state = _state.value
        val recording = state is DictationState.Starting || state is DictationState.Listening
        when (ScreenOff.onScreenOff(recording, begun = isRunning, mode = _mode.value)) {
            ScreenOff.Action.NOTHING -> Unit
            ScreenOff.Action.STOP_RECORDING -> {
                Log.i(TAG, "the screen went off - the recording ends here")
                trace?.ended = "ended when the screen went off"
                stop()
            }
            ScreenOff.Action.CANCEL -> {
                Log.i(TAG, "the screen went off - the run is dropped")
                cancel()
            }
        }
    }

    /** A dictation's text was kept because the screen was off or locked, and nobody was told yet. */
    @Volatile
    private var keptWhileScreenOff = false

    /**
     * The screen is on again and the phone unlocked: says, once, that a dictation was kept
     * meanwhile and how to get it - the note at the time was shown to a dark screen.
     */
    fun screenBack() {
        if (!keptWhileScreenOff) return
        keptWhileScreenOff = false
        // The security mode kept no copy: nothing to insert again.
        val kept = SecurityMode.keepsWords(graph.settings.value)
        toast(if (kept) R.string.toast_screen_off_insert_again else R.string.toast_screen_off_not_kept, long = true)
    }

    /** For the record and Amber's screen: why the text did not go in. */
    private fun notInsertedWhileScreenOff(context: Context): String =
        context.getString(R.string.dictation_not_inserted, context.getString(R.string.dictation_why_screen_off))

    /**
     * A dictation's words that did not go in for [why]: the reason goes to the record and
     * Amber's screen - with how the field was looked for, [insertionTrace], when a write was
     * tried - and the toast says the way out ([NotInserted.hint]): Insert again where the field
     * was gone or left, Copy last where the field itself refused the text.
     */
    private fun notInserted(why: NotInserted.Why, engineId: String, text: String, onClipboard: Boolean = false, insertionTrace: String? = null) {
        val reason = when (why) {
            NotInserted.Why.PASSWORD -> R.string.dictation_why_password
            NotInserted.Why.NO_FIELD -> R.string.dictation_why_no_field
            NotInserted.Why.SWITCHED -> R.string.dictation_why_switched
            NotInserted.Why.SCREEN_OFF -> R.string.dictation_why_screen_off
            NotInserted.Why.REFUSED -> R.string.dictation_no_field_accepted
            NotInserted.Why.UNCERTAIN -> R.string.dictation_typed_never_showed
        }
        // The trace names the app and its field: not in the security mode ([DictationLog.namesApps]).
        val fieldTrace = insertionTrace?.takeIf { graph.memory.log.namesApps }
        val words: Words = { context ->
            context.getString(if (onClipboard) R.string.dictation_on_clipboard_instead else R.string.dictation_not_inserted, context.getString(reason)) +
                fieldTrace?.let { " [$it]" }.orEmpty()
        }
        val hint = NotInserted.hint(why, onClipboard, kept = SecurityMode.keepsWords(graph.settings.value))
        val toast = if (retryForHistory && why == NotInserted.Why.NO_FIELD && SecurityMode.historyKeep(graph.settings.value) != HistoryKeep.OFF) R.string.toast_in_history else when (hint) {
            NotInserted.Hint.INSERT_AGAIN -> R.string.toast_insert_again
            NotInserted.Hint.COPY -> R.string.toast_copy_last
            NotInserted.Hint.ON_CLIPBOARD -> R.string.toast_on_clipboard
            NotInserted.Hint.MAY_ARRIVE -> R.string.toast_may_arrive
            NotInserted.Hint.MAY_ARRIVE_ON_CLIPBOARD -> R.string.toast_may_arrive_on_clipboard
            NotInserted.Hint.NONE -> R.string.toast_not_inserted_password
            NotInserted.Hint.NOT_KEPT -> R.string.toast_not_kept
            NotInserted.Hint.MAY_ARRIVE_NOT_KEPT -> R.string.toast_may_arrive_not_kept
        }
        // Logcat gets the reason, never the field's trace, which names the app.
        fail(
            words, engineId, brief = say(toast), logAs = english.getString(R.string.dictation_not_inserted, english.getString(reason)), text = text,
            // Words that met a password field may be one: never into the history.
            history = why != NotInserted.Why.PASSWORD,
        )
    }

    /** Whether a text may go into a field now: the screen is on and the phone unlocked. */
    private fun mayInsertNow(): Boolean = ScreenOff.mayInsert(
        screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive != false,
        locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true,
    )

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
        get() = _state.value is DictationState.Listening && selfCapturing.get() == null && _mode.value == RunMode.DICTATION

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

    /** The insertion "Undo last" may take out again: its app and exactly what went in ([graph]'s memory). */
    private fun remember(insertion: InsertionOutcome) {
        val app = insertion.targetPackage
        val fragment = insertion.fragment
        graph.memory.inserted(if (insertion.insertedIntoField && app != null && !fragment.isNullOrEmpty()) UndoLast.Insertion(app, fragment) else null)
    }

    /**
     * Takes the last dictation out of the field again, while it still stands right before the
     * cursor ([UndoLast]); says what happened in a toast. False when there is nothing to undo.
     */
    fun undoLast(): Boolean {
        if (isRunning) return false
        val last = graph.memory.lastInsertion ?: return false
        val service = graph.accessibilityService ?: return false
        scope.launch {
            val result = service.fieldEditor.undo(last)
            if (result == FieldEditor.Undo.DONE) graph.memory.undone()
            toast(
                when (result) {
                    FieldEditor.Undo.DONE -> R.string.undo_done
                    FieldEditor.Undo.CHANGED -> R.string.undo_changed
                    FieldEditor.Undo.FAILED -> R.string.undo_failed
                },
            )
        }
        return true
    }

    /**
     * The last dictation of this session (any app) typed again into the field that has focus
     * now - never into a password field. False with nothing to insert or nowhere to put it.
     */
    fun insertAgain(): Boolean {
        if (isRunning) return false
        val text = graph.memory.sessionDictation()?.text ?: return false
        val service = graph.accessibilityService ?: return false
        val target = service.focusedTarget()
        if (target == null || target.kind == TextFieldKind.SECRET) {
            toast(R.string.dictation_no_field)
            return false
        }
        val settings = graph.settings.value
        val insertion = service.textInserter.insert(text, settings.insertion.toSpliceOptions(), allowClipboard = SecurityMode.mayUseClipboard(settings), expectedPackage = target.packageName)
        Log.i(TAG, "inserted again via ${insertion.strategy} into ${DebugLog.redact(insertion.targetPackage)}: field=${insertion.insertedIntoField}")
        remember(insertion)
        if (!insertion.insertedIntoField && !insertion.copiedToClipboard) {
            toast(R.string.dictation_not_inserted_here)
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
        // The security mode never writes the clipboard; it keeps no last dictation either.
        if (!SecurityMode.mayCopy(graph.settings.value)) return false
        val text = graph.memory.sessionDictation()?.text ?: return false
        val inserter = graph.accessibilityService?.textInserter ?: return false
        if (!inserter.copyToClipboard(text)) return false
        // From Android 13 the system confirms a copy itself.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            toast(R.string.dictation_copied)
        }
        return true
    }

    /**
     * [token]: this run's, from [RunStop.begin] - what it may clear when it ends. [retry]: the
     * kept recording to recognize again ([tryAgain]) instead of recording.
     */
    private suspend fun run(source: TriggerSource, token: Int, retry: PcmAudio? = null) {
        _state.value = if (retry == null) DictationState.Starting else DictationState.Transcribing()
        val pause = RecordingPause().also { recordingPause = it }
        wait = null
        runAudio = null
        // A run cancelled while its recognizer listened left its microphone counted as open.
        cue.ended()
        stoppedByTap = false
        commandSteps = null
        commandRecorded = false

        val settings = graph.settings.value
        // Watches this run's wait, and ends with the run.
        val stuckWatch = scope.launch { watchStuck() }
        currentCoroutineContext()[Job]?.invokeOnCompletion { stuckWatch.cancel() }
        val routing = graph.engineFor(runPackage)
        runRouting = routing
        trace?.routing = routing
        val engineConfig = routing.engine
        runEngineId = engineConfig?.id
        if (engineConfig == null) {
            fail(say(R.string.dictation_no_engine), details = listOf("no engine selected"))
            return
        }

        val engine = graph.engineFactory.create(engineConfig).getOrElse { error ->
            fail(failureOf(error), remedy = remedyOf(error), details = listOf("engine ${engineConfig.id} could not be set up: ${technicalOf(error)}"))
            return
        }
        warmUpConnections(engine, engineConfig, settings)

        val options = TranscribeOptions(languageTag = LanguageList.forDictation(engineConfig), vocabularyHint = settings.words.hint())
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
            // While it records, the limit is that of the longest recording; once the recording
            // has ended, what that recording can still need ([RunLimit]).
            val limit = RunLimit(settings.capture.effectiveTotalTimeoutMs(), pause, SystemClock::elapsedRealtime).also { runLimit = it }
            val finished = withPausableTimeout(limit) {
                val estimateKey = "${engineConfig.id}/${engineConfig.modelId}"
                val transcript = when {
                    // Android's recognizer listens by itself: it cannot take the kept recording,
                    // which stays kept for another speech recognition - as it was, its time not
                    // started again, and not offered again for this one ([KeptDictation.worthTryingAgain]).
                    retry != null && engine is SelfCapturingSttEngine -> {
                        failRecognition(
                            SttFailure(Problem.RecordsByItself(engineConfig.displayName), "the kept recording cannot go to a recognizer that records by itself"),
                            details = emptyList(),
                        )
                        null
                    }
                    retry != null -> recognizeKept(engine, options, retry, estimateKey, appLabels, limit)
                    engine is SelfCapturingSttEngine -> listenWithEngine(engine, options, limit)
                    else -> recordThenTranscribe(engine, engineConfig, options, source, estimateKey, appLabels, pause, limit)
                } ?: return@withPausableTimeout Unit // the failure was already reported

                if (_mode.value == RunMode.COMMAND) runCommand(transcript, appLabels) else scratched(transcript, settings)?.let { deliver(cleanUp(it), settings, engineConfig.id) } ?: Unit
            }

            if (finished == null) {
                val keptNow = keepForRetry(null)
                fail(
                    say(R.string.dictation_timed_out),
                    engineConfig.id,
                    brief = say(if (keptNow) R.string.toast_timed_out_try_again else R.string.dictation_timed_out),
                    details = listOf("stopped: the run's time limit of ${limit.limitMs} ms ran out"),
                )
            }
        } catch (e: CancellationException) {
            // A command a tap stopped that has no record yet - while it was still being
            // recognized, or anywhere runCommand did not keep one: exactly one record, with
            // the steps so far.
            if (stoppedByTap && _mode.value == RunMode.COMMAND && !commandRecorded) {
                commandRecorded = true
                val steps = commandSteps
                graph.memory.log.recordFailure(
                    "Stopped by a tap.", engineConfig.id, trace?.summary(namesApps = graph.memory.log.namesApps), command = true,
                    details = if (steps == null) listOf("stopped by a tap before the command ran")
                    else steps.apply { note("stopped by a tap") }.lines,
                )
            }
            // cancel() already set the state, and a cancelled parent scope is not a dictation
            // error. Rethrow so structured concurrency still sees it.
            throw e
        } catch (e: Exception) {
            // Its message may hold a server's answer or what was heard: a release build logs
            // the class only.
            if (DebugLog.enabled) Log.e(TAG, "dictation failed", e) else Log.e(TAG, "dictation failed: ${e::class.java.name}")
            fail(
                say(R.string.dictation_unexpected, e.message ?: e::class.simpleName.orEmpty()),
                // The message may be long and technical: the toast says where it is.
                brief = say(R.string.toast_unexpected),
                logAs = english.getString(R.string.dictation_unexpected, e::class.java.name),
                details = listOf("unexpected ${e::class.java.name}"),
            )
        } finally {
            // Only what is still this run's. After a cancel during a recognition on the phone,
            // which cannot be aborted, this runs seconds late - a new dictation may be
            // recording by then, and its recognizer and the tap that stopped it are not ours.
            (engine as? SelfCapturingSttEngine)?.let { selfCapturing.compareAndSet(it, null) }
            // The recording had its chance to be kept for "Try again" ([keepForRetry]): what
            // is not in [kept] now is let go, not held in memory until the next dictation. A
            // run another has followed leaves alone what is that one's.
            if (runStop.end(token)) runAudio = null
            runCatching { engine.close() }
            graph.scheduleModelRelease()
        }
    }

    /** When this run's tones are due; see [RecordingCue]. */
    private val cue = RecordingCue()

    /** Plays the start and stop tones when the setting is on ([RecordingSounds]). */
    private val sounds = RecordingSounds()

    /** Holds the audio focus while the microphone is open, when music is to pause ([MediaPause]). */
    private val mediaPause by lazy { MediaPause(context) }

    /** The microphone is about to open: music pauses now, before the first word, if wanted. */
    private fun microphoneOpening() {
        if (graph.settings.value.feedback.pauseMedia) mediaPause.hold()
    }

    /**
     * The microphone is really open - the recorder records, the recognizer listens: the bubble
     * buzzes (it watches [DictationState.Listening.open]), and the start tone plays if wanted -
     * its beep silenced in Amber's own recording ([StartToneMute]).
     */
    private fun microphoneOpen() {
        updateListening { it.copy(open = true) }
        if (cue.opened() != null && graph.settings.value.feedback.sounds) sounds.play(RecordingSounds.Tone.START)
    }

    /** The recording ended, however: the stop tone, once, if the microphone had opened. */
    private fun microphoneClosed() {
        // The focus goes back first: the music is on again as the stop tone plays.
        mediaPause.release()
        if (cue.ended() != null && graph.settings.value.feedback.sounds) sounds.play(RecordingSounds.Tone.STOP)
    }

    /** What the bubble's level was fed in this dictation, for the log ([LoudnessLog]). */
    @Volatile
    private var loudness: LoudnessLog? = null

    private fun noteLoudness(amplitude: Float) {
        loudness?.let { log -> synchronized(log) { log.add(amplitude, SystemClock.elapsedRealtime()) } }
    }

    private suspend fun listenWithEngine(
        engine: SelfCapturingSttEngine,
        options: TranscribeOptions,
        limit: RunLimit,
    ): Transcript? {
        selfCapturing.set(engine)
        // A tap that came while the run was starting found no recognizer to stop: it is due
        // now, and the engine keeps it until its recognizer listens ([ListenStop]).
        if (runStop.requested) engine.requestStop()
        _state.value = DictationState.Listening()
        // Recognition overlaps with listening here; the whole call counts as both.
        trace?.recordingStarts()
        trace?.transcribingStarts()
        loudness = LoudnessLog()

        microphoneOpening()
        val result = try {
            engine.listen(
                options,
                ListenCallbacks(
                    onAmplitude = { amplitude -> noteLoudness(amplitude); updateListening { it.copy(amplitude = amplitude) } },
                    onPartial = { partial -> updateListening { it.copy(partial = partial) } },
                    onReady = ::microphoneOpen,
                ),
            )
        } finally {
            microphoneClosed()
        }

        trace?.recordingEnds()
        trace?.transcribingEnds()
        // Also when the recognizer ended by itself, with no tap that told the limit.
        limit.recordingEnded(graph.settings.value.capture.afterRecordingMs(limit.elapsedMs()))
        _state.value = DictationState.Transcribing()
        return result.getOrElse { error ->
            fail(failureOf(error), remedy = remedyOf(error), details = listOf("recognition failed: ${technicalOf(error)}"))
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
        graph.memory.log.recordFailure(
            "Paused for more than ${RecordingPause.MAX_PAUSE_MS / 60_000} minutes with nothing recorded - the recording ended.",
            timing = trace?.summary(namesApps = graph.memory.log.namesApps),
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
        /** This run's time limit, cut down to the recording's length once it has ended. */
        limit: RunLimit,
    ): Transcript? {
        _state.value = DictationState.Listening()
        loudness = LoudnessLog()

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
        // The sign that the longest recording is near, for a dictation; a command is short.
        var limitLeft: Float? = null
        microphoneOpening()
        val outcome = try {
            capture.record(
                config = vadConfig,
                stopRequested = { runStop.requested },
                onAmplitude = { amplitude ->
                    noteLoudness(amplitude)
                    updateListening { it.copy(amplitude = amplitude, limitLeft = limitLeft) }
                },
                onRecorded = { ms ->
                    limitLeft = if (_mode.value == RunMode.DICTATION) RecordingLimit.left(ms, vadConfig.maxDurationMs) else null
                },
                onChunk = chunkSink?.let { it::submit },
                minChunkMs = captureSettings.chunkMinMs ?: (prepared?.engine ?: engine).capabilities.minChunkMs,
                pause = pause,
                onStarted = ::microphoneOpen,
                // The start tone plays as the microphone opens: its beep is not to be recognized.
                silenceFirstMs = if (graph.settings.value.feedback.sounds) StartToneMute.START_TONE_MS else 0,
            )
        } catch (e: Throwable) {
            chunked?.cancel()
            prepared?.chunks?.cancel()
            throw e
        } finally {
            microphoneClosed()
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
                // A microphone another app holds delivers silence: said as that, or the user
                // would move closer to the phone and try again in vain.
                fail(
                    when (outcome.reason) {
                        DiscardReason.TOO_SHORT -> say(R.string.dictation_too_short)
                        DiscardReason.NO_SPEECH, DiscardReason.PAUSED_TOO_LONG -> say(R.string.dictation_nothing_heard)
                        DiscardReason.MIC_SILENCED -> said(Problem.MicrophoneTaken)
                        DiscardReason.MIC_LOST -> said(Problem.MicrophoneUnreadable)
                    },
                    details = listOf(
                        when (outcome.reason) {
                            DiscardReason.TOO_SHORT -> "recording discarded: too short"
                            DiscardReason.NO_SPEECH, DiscardReason.PAUSED_TOO_LONG -> "recording discarded: no speech detected"
                            DiscardReason.MIC_SILENCED, DiscardReason.MIC_LOST -> "recording discarded: ${outcome.technical ?: "the microphone was lost"}"
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
                    // Starting again, as at the first handover: a tap meanwhile is this run's
                    // stop, and an activity that never comes is said after a moment - the
                    // bubble no longer stays on "listening" with nothing behind it.
                    handToProxy()
                } else {
                    fail(say(outcome.message), details = listOf("capture failed: ${outcome.technical}"))
                }
                return null
            }

            is AudioCapture.Outcome.Captured -> {
                trace?.recordingEnds()
                // From here the run needs what this recording takes, not what the longest would.
                limit.recordingEnded(captureSettings.afterRecordingMs(outcome.audio.durationMs))
                // Sent as a tap would, all of it; the diagnostics say why it ended there.
                if (outcome.reason == StopReason.MAX_DURATION) {
                    trace?.ended = "ended at the longest recording, ${vadConfig.maxDurationMs / 1000} s"
                }
                // The microphone went away mid-dictation - another app took it, or it could
                // no longer be read: what was said before is recognized, and the user is told
                // why the recording ended without their tap.
                if (outcome.reason == StopReason.MIC_SILENCED || outcome.reason == StopReason.MIC_LOST) {
                    trace?.ended = "ended: ${outcome.technical ?: "the microphone was lost"}"
                    toast(if (outcome.reason == StopReason.MIC_SILENCED) R.string.dictation_mic_taken_sent else R.string.dictation_mic_lost_sent, long = true)
                }
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
                // Kept for "Try again" should its recognition fail; a command is said again instead.
                if (!command) runAudio = outcome.audio
                return recognize(engine, options, outcome, chunked, prepared, command, estimateKey, appLabels)
            }
        }
    }

    /**
     * The recording of a dictation that failed, kept for "Try again" ([KeptDictation]),
     * recognized once more with the speech recognition set now - no microphone, the rest as a
     * dictation goes: the progress ring, the fallback, the cleanup, the field in focus now.
     */
    private suspend fun recognizeKept(
        engine: SttEngine,
        options: TranscribeOptions,
        audio: PcmAudio,
        estimateKey: String,
        appLabels: () -> List<String>,
        limit: RunLimit,
    ): Transcript? {
        runAudio = audio
        trace?.kept = "recognized again from the kept recording, ${ms(audio.durationMs)}"
        limit.recordingEnded(graph.settings.value.capture.afterRecordingMs(audio.durationMs))
        val captured = AudioCapture.Outcome.Captured(audio, StopReason.MANUAL, tail = null, chunks = 0)
        return recognize(engine, options, captured, chunked = null, prepared = null, command = false, estimateKey, appLabels)
    }

    /**
     * A finished recording to text: with chunks, only what is left after the stop; with a
     * fallback prepared on the phone, raced against it; on a server's failure, by the fallback.
     * Null when it failed - the failure was said.
     */
    private suspend fun recognize(
        engine: SttEngine,
        options: TranscribeOptions,
        outcome: AudioCapture.Outcome.Captured,
        chunked: ChunkedTranscription?,
        prepared: Prepared?,
        command: Boolean,
        estimateKey: String,
        appLabels: () -> List<String>,
    ): Transcript? {
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
            failRecognition(error, details = listOf("recognition failed: ${technicalOf(error)}"))
            null
        }
    }

    /**
     * Recognition failed for [error]: said, and - for a dictation whose recording is here and
     * worth another go ([KeptDictation.worthTryingAgain]) - the recording kept for "Try again",
     * which the toast then names as the way out.
     */
    private fun failRecognition(error: Throwable, details: List<String>) {
        // Not a problem Amber knows (null): nothing says another go would help.
        val problem = (error as? SttFailure)?.problem
        if (problem != null && keepForRetry(problem)) {
            fail(
                failureOf(error),
                remedy = remedyOf(error),
                brief = { it.getString(R.string.toast_try_again, Texts.failure(it, error).trimEnd('.')) },
                details = details,
            )
        } else {
            fail(failureOf(error), remedy = remedyOf(error), details = details)
        }
    }

    /**
     * Keeps this run's recording for "Try again" when [why] leaves it worth another go (null:
     * the run's time limit) - only a dictation's, only while keeping is on.
     * True when it is kept now; its time is watched, so the memory goes back when it is up.
     */
    private fun keepForRetry(why: Problem?): Boolean {
        if (_mode.value != RunMode.DICTATION) return false
        val audio = runAudio ?: return false
        if (!kept.keepRecording(audio, why)) return false
        trace?.kept = "recording kept for Try again, ${ms(audio.durationMs)}"
        Log.i(TAG, "recording kept for Try again (${audio.durationMs} ms, in memory only)")
        scope.launch {
            delay(kept.keepMs)
            kept.expire()
        }
        return true
    }

    /**
     * Tried again from the History page ([tryAgain] forHistory): with no field there, the words
     * land in the history, and the toast says so instead of pointing to a field.
     */
    @Volatile
    private var retryForHistory = false

    /**
     * "Try again" from the quick menu: the kept recording of a dictation that failed is
     * recognized again with the speech recognition set now, and goes into the field in focus
     * now as a dictation would. False with nothing kept, or while a dictation runs.
     */
    fun tryAgain(forHistory: Boolean = false): Boolean {
        if (isRunning) return false
        val audio = kept.recording() ?: return false
        retryForHistory = forHistory
        takeStartTarget()
        runInAmber = false
        runPackage = targetApp()
        val routed = graph.engineFor(runPackage).engine
        // A model still downloading: said, and the recording stays kept.
        routed?.takeIf(ModelDownloads::downloadsModel)?.let(graph.modelDownloads::forEngine)?.let { download ->
            notice(Texts.downloadAtStart(context, download))
            return false
        }
        runSerial++
        stoppedByTap = false
        commandSteps = null
        commandRecorded = false
        runEngineId = routed?.id
        skipCleanup = CompletableDeferred()
        fellBack = null
        fellBackFrom = null
        _mode.value = RunMode.DICTATION
        handover.cancel()
        trace = RunTrace("TRY_AGAIN")
        graph.dictationStarting(routed)
        val token = runStop.begin()
        _state.value = DictationState.Transcribing()
        job = scope.launch { run(TriggerSource.BUBBLE, token, retry = audio) }
        return true
    }

    /** A fallback on the phone recognizing this recording alongside the server ([ReadyFallback]). */
    private class Prepared(val config: EngineConfig, val engine: SttEngine, val options: TranscribeOptions, val chunks: ChunkedTranscription)

    /**
     * The fallback model to prepare while [primary] records, if the user switched preparing on
     * and the model is downloaded: it starts loading now, and finished chunks of the recording
     * go to it. Null otherwise - and always for an engine on the phone.
     */
    private suspend fun prepareFallback(primary: EngineConfig): Prepared? {
        val config = ReadyFallback.engineToPrepare(graph.settings.value, primary, ::trustOf, graph::modelReady) ?: return null
        val engine = graph.engineFactory.create(config).getOrNull() ?: return null
        val scope = CoroutineScope(currentCoroutineContext())
        // The model loads while the user speaks; a failure shows when it is asked for text.
        scope.launch { engine.warmUp() }
        val options = TranscribeOptions(languageTag = LanguageList.forDictation(config), vocabularyHint = graph.settings.value.words.hint())
        return Prepared(config, engine, options, ChunkedTranscription(engine, options, scope))
    }

    private fun trustOf(config: EngineConfig): TrustLevel =
        EngineTrust.forDisplay(config, onDeviceSpeech = AndroidOnDeviceEngine.onDeviceSpeech)

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
        val primary = runRouting?.engine
        fellBack = config.displayName
        fellBackFrom = primary?.id
        val refused = local.serverFailure?.takeIf { it.serverCannotServe }
        val why = if (local.serverTooSlow) "server too slow" else refused?.let(::cannotServe) ?: "server unreachable"
        trace?.fallback = "$why, recognized by ${config.displayName} during the recording, label $trust"
        val name = Texts.engineName(context, config)
        val message = when {
            local.serverTooSlow -> context.getString(R.string.fallback_server_too_slow)
            // What the server said, in full: the fallback must not hide that it needs the user.
            refused != null -> refusedButRecognized(refused.problem, name)
            trust == TrustLevel.ON_DEVICE -> context.getString(R.string.fallback_no_connection, name)
            // A read timeout or a 5xx came after the upload: the recording may be the server's.
            else -> context.getString(R.string.fallback_no_answer_on_phone)
        }
        mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        return transcript.copy(trustLevel = trust)
    }

    /**
     * The fallback's toast after a server that cannot serve ([SttFailure.serverCannotServe]):
     * what the server said, short - only the user can put it right - and who recognized instead.
     */
    private fun refusedButRecognized(problem: Problem, by: String): String = when (problem) {
        is Problem.NoCredit -> context.getString(R.string.fallback_no_credit, Texts.catalog(context, problem.service), by)
        is Problem.ModelNotOnServer -> context.getString(R.string.fallback_model_gone, Texts.catalog(context, problem.service), by)
        else -> Texts.problem(context, problem)
    }

    /** For Diagnostics: a server that answered but cannot serve - what it said, in English, without its body. */
    private fun cannotServe(failure: SttFailure): String =
        "server cannot serve (HTTP ${failure.statusCode}: ${Texts.problem(english, failure.problem)})"

    /**
     * How long the cleanup chosen for this dictation's app should take for about [chars]
     * characters, learned per cleanup model; null when it gets none.
     */
    private fun expectedCleanupMs(settings: AmethystSettings, chars: Long, fellBackFrom: String?): Long? {
        val target = targetApp()
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
        val target = targetApp()
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
        val primary = runRouting?.engine ?: return null
        val candidates = Fallback.candidates(settings.fallback, Servers.recognizers(settings), primary, ::trustOf).filter { it.id != skip }
        for (config in candidates) {
            val other = graph.engineFactory.create(config).getOrNull() ?: continue
            Log.i(TAG, "server unavailable (${Anonymize.line(cause.technical).take(80)}) - recognizing with ${config.id}")
            // The rest of the ring is this engine's recognition and the cleanup after it.
            val caps = other.capabilities
            val load = if (localModelWarm(config) == false) MODEL_LOAD_MS else 0L
            val recognizeMs = graph.recognitionEstimate.expectedMs("${config.id}/${config.modelId}", audio.durationMs, caps.typicalRate, caps.overheadMs + load)
            val cleanupMs = expectedCleanupMs(settings, (audio.durationMs * CHARS_PER_AUDIO_MS).toLong(), fellBackFrom = primary.id)
            val wait = wait ?: WaitProgress(SystemClock::elapsedRealtime).also { wait = it }
            wait.plan(*listOfNotNull(WaitProgress.Step.RECOGNIZE to recognizeMs, cleanupMs?.let { WaitProgress.Step.CLEANUP to it }).toTypedArray())
            _state.value = DictationState.Transcribing(wait)
            val result = try {
                other.transcribe(audio, TranscribeOptions(languageTag = LanguageList.forDictation(config), vocabularyHint = settings.words.hint()))
            } finally {
                runCatching { other.close() }
            }
            result.getOrNull()?.let { transcript ->
                // A read timeout or a 5xx came after the upload: the recording may be the
                // first server's too - and the label says so, as it says the fallback's own.
                val trust = Fallback.trustAfter(cause, serverTrust, transcript.trustLevel)
                fellBack = config.displayName
                fellBackFrom = primary.id
                val why = if (cause.serverCannotServe) cannotServe(cause) else "server unreachable"
                trace?.fallback = "$why, recognized by ${config.displayName}, label $trust"
                val local = transcript.trustLevel == TrustLevel.ON_DEVICE
                val name = Texts.engineName(context, config)
                val message = when {
                    // What the server said, in full: the fallback must not hide that it needs the user.
                    cause.serverCannotServe -> refusedButRecognized(cause.problem, name)
                    trust == TrustLevel.ON_DEVICE -> context.getString(R.string.fallback_no_connection, name)
                    local -> context.getString(R.string.fallback_no_answer_on_phone)
                    else -> context.getString(R.string.fallback_other_server, Texts.engineName(context, primary), name)
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
        val target = targetApp()
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
        // Named in the prompt and checked in the reply: what the words clearly are, else what
        // the engine detected, else the one language it was set to.
        val language = LanguageGuess.forDictation(
            transcript.text,
            transcript.detectedLanguage,
            settings.engines.firstOrNull { it.id == transcript.engineId }?.let { LanguageList.spokenHint(it, PhoneLocales.tags()) },
        )
        // A long text takes its time to be written back; past it, it goes in as recognized.
        val result = withTimeoutOrNull(ChatCleanup.timeoutMs(transcript.text.length)) {
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
                graph.cleanupTrouble.succeeded()
                transcript.copy(
                    text = cleaned.text,
                    formatted = true,
                    // The text is only as trustworthy as the weaker of the two trips it made.
                    trustLevel = listOf(transcript.trustLevel, cleaned.trust).maxBy { it.strictness },
                )
            },
            onFailure = { error ->
                trace?.cleanup = "cleanup skipped: ${Texts.failure(english, error)}"
                // The error's own text may quote the model's reply, i.e. the dictation.
                Log.w(TAG, "cleanup failed: ${(error as? SttFailure)?.technical?.let { Anonymize.line(it, listOf(transcript.text)).take(80) } ?: error.javaClass.simpleName}")
                // A refused key, no credit, an unknown model: said once a session, and on top
                // of Settings until a cleanup runs again. Anything else stays silent.
                (error as? SttFailure)?.problem?.let { problem ->
                    val service = settings.engines.firstOrNull { it.id == choice.engineId }?.displayName ?: choice.engineId
                    graph.cleanupTrouble.failed(problem, service, choice)?.let { open ->
                        mainHandler.post {
                            Toast.makeText(context, context.getString(R.string.toast_cleanup_did_not_run, Texts.cleanupTrouble(context, open)), Toast.LENGTH_LONG).show()
                        }
                    }
                }
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
            fail(plain("Nothing heard - say the command again."), transcript.engineId, details = listOf("the engine recognized no words"))
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
                graph.memory.log.recordCommand(heard, CommandOutcome.Refused("Stopped by a tap."), transcript.engineId, trace?.summary(namesApps = graph.memory.log.namesApps), steps.lines)
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
        graph.memory.log.recordCommand(heard, outcome, transcript.engineId, trace?.summary(namesApps = graph.memory.log.namesApps), steps.lines)
        if (outcome !is CommandOutcome.Done) {
            // Logged just above, with what was heard.
            fail(plain(outcome.message), transcript.engineId, record = false, logAs = "command not done")
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
        takeStartTarget()
        runCommand(Transcript(text = text, engineId = "test", trustLevel = TrustLevel.ON_DEVICE, latencyMs = 0))
        _mode.value = RunMode.DICTATION
    }

    /**
     * A ready-made text through the same delivery a dictation takes - normalizing, the target
     * check, every insertion method - for the debug build's adb test hook (src/debug). The
     * field that has focus now counts as the one the dictation started in.
     */
    internal suspend fun deliverForTest(text: String, formatted: Boolean) {
        takeStartTarget()
        val transcript = Transcript(
            text = text,
            engineId = "debug-test",
            trustLevel = TrustLevel.ON_DEVICE,
            latencyMs = 0,
            formatted = formatted,
        )
        deliver(transcript, graph.settings.value, transcript.engineId)
    }

    /** The field that has focus now is the one this run is for. */
    private fun takeStartTarget() {
        val service = graph.accessibilityService
        startTarget = service?.focusedTarget()
        startNode = startTarget?.let { service?.textInserter?.inputFocus() }
        startApp = service?.appInFront
    }

    /**
     * "Streich das" / "scratch that" at the end takes back the sentence before it ([ScratchThat]),
     * before a cleanup sees the text - with the spoken punctuation, under one switch. Null when
     * nothing is left: nothing is inserted, and a short toast says so.
     */
    private fun scratched(transcript: Transcript, settings: app.amethyst.core.settings.AmethystSettings): Transcript? {
        if (!settings.words.spokenPunctuation) return transcript
        val result = ScratchThat.apply(transcript.text)
        if (!result.scratched) return transcript
        if (result.text.isNotBlank()) return transcript.copy(text = result.text)
        Log.i(TAG, "scratched: nothing left to insert")
        toast(R.string.dictation_scratched)
        _state.value = DictationState.Idle
        return null
    }

    private suspend fun deliver(
        transcript: Transcript,
        settings: app.amethyst.core.settings.AmethystSettings,
        engineId: String,
    ) {
        val cleaned = TranscriptNormalizer.normalize(
            transcript.text,
            settings.normalizeOptions(),
            language = transcript.detectedLanguage ?: settings.engines.firstOrNull { it.id == engineId }?.let { LanguageList.spokenHint(it, PhoneLocales.tags()) },
            // Whether the field takes them is decided at insertion (InsertionEngine).
            keepLineBreaks = transcript.formatted,
        )
        if (cleaned.isBlank()) {
            fail(said(Problem.NothingRecognized), engineId)
            return
        }
        // Words came: a recording kept from a failed dictation is not needed now - whatever
        // becomes of these, they are kept for Insert again and Copy last.
        if (_mode.value == RunMode.DICTATION) kept.textRecognized()

        val inserter = graph.accessibilityService?.textInserter
        if (inserter == null) {
            fail(say(R.string.dictation_service_off), engineId)
            return
        }

        // The power button meanwhile: nothing is typed behind the lock screen or into one of
        // its fields. The words stay in the record, for Insert again; the toast waits until
        // someone can read it ([screenBack]).
        if (!mayInsertNow()) {
            Log.i(TAG, "not inserting: the screen is off or locked")
            keptWhileScreenOff = true
            fail(::notInsertedWhileScreenOff, engineId, text = cleaned, toast = false)
            return
        }

        awaitFocusReturn()

        var now = graph.accessibilityService?.focusedTarget()
        var verdict = TargetCheck.verdict(startTarget, now, startApp)
        // Android may say no field has focus while the field is still there: the screen went
        // off during a long recording, the app ended input with the keyboard. The start field's
        // own node is looked for again - never another field, which could be another chat's.
        var refound: AccessibilityNodeInfo? = null
        if (verdict == TargetCheck.Verdict.NO_FIELD) {
            val found = inserter.refind(startTarget, startNode)
            trace?.refind = found.note
            Log.i(TAG, found.note)
            found.node?.let { node ->
                // Focused as a tap would: the app starts input again and the words are typed,
                // the way that keeps them. Where focus does not take, they go into the node.
                inserter.focus(node)
                delay(FOCUS_SETTLE_MS)
                now = graph.accessibilityService?.focusedTarget()
                verdict = TargetCheck.verdict(startTarget, now, startApp)
                if (verdict == TargetCheck.Verdict.NO_FIELD) {
                    refound = node
                    verdict = TargetCheck.Verdict.INSERT
                }
            }
        }
        if (verdict != TargetCheck.Verdict.INSERT) {
            Log.i(TAG, "not inserting: $verdict (start ${DebugLog.redact(startTarget?.packageName)}, now ${DebugLog.redact(now?.packageName)})")
            val why = NotInserted.beforeWrite(verdict)
            // Never into a password field, not even via the clipboard. Otherwise only when the
            // user allows the clipboard at all; else the text stays in the diagnostics.
            val copied = why != NotInserted.Why.PASSWORD &&
                SecurityMode.mayUseClipboard(settings) && inserter.copyToClipboard(cleaned)
            notInserted(why, engineId, cleaned, onClipboard = copied)
            return
        }

        trace?.insertingStarts()
        val insertion = inserter.insert(
            cleaned,
            settings.insertion.toSpliceOptions(),
            allowClipboard = SecurityMode.mayUseClipboard(settings),
            into = refound,
            expectedPackage = TargetCheck.expectedPackage(startTarget, startApp),
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
            // Neither the field nor the clipboard took it. The words stay in the record, so
            // they survive in the diagnostics card and Insert again and Copy last can take them.
            notInserted(NotInserted.afterWrite(insertion.refused, insertion.uncertain), engineId, cleaned, insertionTrace = inserter.lastTrace)
            return
        }

        remember(insertion)
        trace?.servedBy = transcript.servedBy
        val finalTranscript = transcript.copy(text = cleaned)
        graph.memory.log.recordSuccess(
            transcript = finalTranscript,
            strategy = insertion.strategy,
            inserted = insertion.insertedIntoField,
            targetPackage = insertion.targetPackage,
            note = inserter.lastTrace,
            timing = trace?.summary(namesApps = graph.memory.log.namesApps),
        )
        trace?.let { Log.i(TAG, "dictation timing: ${it.summary(namesApps = DebugLog.enabled)}") }
        loudness?.summary()?.let { Log.i(TAG, "bubble level: $it") }
        recordStat(finalTranscript, insertion.targetPackage, settings)
        graph.dictatedInto(startTarget?.packageName ?: insertion.targetPackage)
        graph.keepInHistory(cleaned, startTarget?.packageName ?: insertion.targetPackage)

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
                // Never in the security mode: counts and times only.
                app = SecurityMode.statApp(settings, (startTarget?.packageName ?: targetPackage)?.takeIf { it != context.packageName }),
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
    private fun toast(message: Int, long: Boolean = false) {
        mainHandler.post { Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
    }

    /** A toast without a failure: the state stays Idle, the bubble stays calm, nothing is logged. */
    private fun notice(message: String) {
        Log.i(TAG, "dictation not started: $message")
        mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }

    /**
     * [words] are put into words twice: in the phone's language for the bubble, the toast and
     * Amber's screen, in English for Diagnostics and logcat, which get shared.
     */
    private fun fail(
        words: Words,
        engineId: String? = null,
        remedy: SttFailure.Remedy? = null,
        // For the toast and logcat when the message quotes the dictated text: the toast is
        // drawn over whatever app is open, and the words stay private to Amber's own screen.
        brief: Words = words,
        record: Boolean = true,
        // Logcat never gets a heard command's words, which the message may quote.
        logAs: String = brief(english),
        // For a command's Diagnostics: what went wrong before or around it, never logged.
        details: List<String> = emptyList(),
        // A dictation's words that were recognized but did not go in: kept in the record.
        text: String = "",
        // Off when nobody can read it now and it is said later instead.
        toast: Boolean = true,
        // Whether [text] may go into the history of recent dictations.
        history: Boolean = true,
    ) {
        val message = words(context)
        val logged = words(english)
        // A message may quote a server's answer or what was heard: logcat gets it without.
        Log.w(TAG, "dictation failed: ${Anonymize.line(logAs, listOf(text))}")
        if (record) {
            val command = _mode.value == RunMode.COMMAND
            if (command) {
                // The steps so far, if the words were there, then what went wrong - noted on in
                // the same trace, so its bounds and its count of left-out lines stay exact.
                val steps = commandSteps ?: CommandTrace()
                details.forEach(steps::note)
                commandRecorded = true
                graph.memory.log.recordFailure(logged, engineId ?: runEngineId, trace?.summary(namesApps = graph.memory.log.namesApps), command = true, details = steps.lines)
            } else {
                graph.memory.log.recordFailure(logged, engineId, trace?.summary(namesApps = graph.memory.log.namesApps), text = text)
                // Words that did not go in are a dictation too: the history keeps them.
                if (history && text.isNotBlank()) graph.keepInHistory(text, startTarget?.packageName)
            }
        }
        _state.value = DictationState.Failed(message, remedy)

        // While dictating into another app the bubble is the only visible surface, and a red
        // ring does not say why. A toast does, without making the user switch back.
        if (toast) {
            mainHandler.post {
                Toast.makeText(context, brief(context), Toast.LENGTH_LONG).show()
            }
        }

        scope.launch {
            delay(FAILED_DISPLAY_MS)
            if (_state.value is DictationState.Failed) _state.value = DictationState.Idle
        }
    }

    private fun remedyOf(error: Throwable): SttFailure.Remedy? = (error as? SttFailure)?.remedy

    /** What an engine said went wrong, for a command's Diagnostics - the technical side. */
    private fun technicalOf(error: Throwable): String = (error as? SttFailure)?.technical ?: error.toString()

    private fun failureOf(error: Throwable): Words = { Texts.failure(it, error) }

    private fun say(id: Int, vararg args: Any): Words = { it.getString(id, *args) }

    private fun said(problem: Problem): Words = { Texts.problem(it, problem) }

    /** For the voice commands, English while they are hidden (Features.VOICE_COMMANDS). */
    private fun plain(text: String): Words = { text }

    /** English resources, for Diagnostics and logcat. */
    private val english: Context by lazy { Texts.english(context) }

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

        const val PROXY_HANDOVER_TIMEOUT_MS = 3_000L
        const val FAILED_DISPLAY_MS = 3_500L
        const val PROXY_EXIT_TIMEOUT_MS = 1_500L
        const val PROXY_POLL_MS = 50L
        const val FOCUS_SETTLE_MS = 200L

        /** How often a wait is looked at for being stuck. */
        const val STUCK_CHECK_MS = 500L

        /** How much of a heard command a toast or message quotes. */
        const val SHOWN_CHARS = 60
    }
}

/** A sentence for the user, put into words when shown: see DictationController.fail. */
private typealias Words = (Context) -> String
