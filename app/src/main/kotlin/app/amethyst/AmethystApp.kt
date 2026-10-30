package app.amethyst

import android.app.Application
import android.content.Context
import android.os.SystemClock
import app.amethyst.accessibility.AmethystAccessibilityService
import app.amethyst.core.dictation.DictationLog
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.data.SecretStore
import app.amethyst.data.SettingsStore
import app.amethyst.data.StatsStore
import app.amethyst.data.StrategyStore
import app.amethyst.dictation.DictationController
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.RecognitionEstimate
import app.amethyst.core.engine.whisper.WhisperModels
import app.amethyst.engine.EngineFactory
import app.amethyst.engine.ModelDownloads
import app.amethyst.engine.NativeWhisperRuntime
import app.amethyst.engine.ParakeetModelStore
import app.amethyst.engine.SherpaRuntime
import app.amethyst.engine.TinfoilVerifier
import app.amethyst.engine.WhisperModelStore
import app.amethyst.core.net.OkHttpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AmethystApp : Application() {

    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        app.amethyst.engine.AndroidOnDeviceEngine.detectOnDeviceSpeech(this)
    }

    /**
     * The models are the largest thing in this process. When the system is about to start
     * killing apps, giving them back is cheaper for the user than losing the service.
     *
     * Only on real memory pressure: UI_HIDDEN and BACKGROUND are numerically higher but only
     * mean the screens went away - the bubble still dictates, and the idle release handles
     * the rest. And never here on the main thread: a running transcription holds the model's
     * lock, and waiting for it would freeze the bubble.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_COMPLETE) {
            graph.releaseModelsWhenIdle()
        }
    }

    companion object {
        fun graph(context: Context): Graph =
            (context.applicationContext as AmethystApp).graph
    }
}

/**
 * Hand-written service locator.
 *
 * The accessibility service, the proxy activity and the UI all need the same few objects and
 * none of them is created by us, so a DI framework would buy little here. Keeping it explicit
 * also keeps the dependency list short, which matters for an app that asks for accessibility
 * and microphone access.
 */
class Graph(app: Application) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsStore = SettingsStore(app)
    val strategyStore = StrategyStore(app)

    /**
     * Lazy on purpose. Building this generates or unwraps a keystore-backed master key and
     * initialises Tink - the slowest thing in the app on a first launch. In
     * Application.onCreate it would delay the first frame for everyone, including users who
     * never configure a cloud engine at all.
     */
    val secretStore: SecretStore by lazy { SecretStore(app) }

    /** Synchronously readable settings, for the service paths that cannot suspend. */
    val settings: StateFlow<AmethystSettings> = settingsStore.settings
        .stateIn(appScope, SharingStarted.Eagerly, AmethystSettings.defaults())

    /**
     * The stored settings, null until DataStore has answered. [settings] starts on the defaults,
     * which would show a fresh install's introduction to everyone for a moment; the start screen
     * is decided from this instead.
     */
    val loadedSettings: StateFlow<AmethystSettings?> = settingsStore.settings
        .stateIn(appScope, SharingStarted.Eagerly, null)

    val whisperRuntime = NativeWhisperRuntime(app)

    val whisperModels: WhisperModelStore by lazy { WhisperModelStore(app, appScope, whisperRuntime) }

    val sherpaRuntime = SherpaRuntime()

    val parakeetModels: ParakeetModelStore by lazy { ParakeetModelStore(app, appScope, sherpaRuntime) }

    /** The introduction's downloads, and whether the active engine is still waiting for one. */
    val modelDownloads: ModelDownloads by lazy { ModelDownloads(app, parakeetModels, whisperModels) }

    /** What the Tinfoil verifier did lately, newest first, for the engine screen's debug section. */
    val tinfoilLog = MutableStateFlow<List<String>>(emptyList())

    /** One per process, so its cache of proofs spans dictations. */
    val tinfoilVerifier: TinfoilVerifier by lazy { TinfoilVerifier(tinfoilLog) }

    // Lazy too, so that merely building the graph does not force the secret store open.
    val engineFactory: EngineFactory by lazy {
        EngineFactory(app, OkHttpTransport(), secretStore, whisperModels, whisperRuntime, tinfoilVerifier, parakeetModels, sherpaRuntime)
    }

    /** How long recognition takes per engine and model on this phone, for the progress ring. */
    val recognitionEstimate = RecognitionEstimate()

    /** What the keyboard tracker decided lately, newest first, for the diagnostics card. */
    val keyboardLog = MutableStateFlow<List<String>>(emptyList())

    /** Outcomes of the last dictations, for the diagnostics card. */
    val dictationLog = DictationLog()

    /** Counts and timings of every dictation, for the statistics; never text or audio. */
    val stats = StatsStore(app, appScope)

    val dictation = DictationController(app, this, appScope)

    /** Set by the accessibility service while it is connected; null means no insertion path. */
    @Volatile
    var accessibilityService: AmethystAccessibilityService? = null

    private var releaseJob: Job? = null

    @Volatile
    private var lastDictationAt = 0L

    /** A field gained focus. Preloads only for someone who dictated a moment ago. */
    fun textFieldFocused() {
        if (SystemClock.elapsedRealtime() - lastDictationAt < RECENT_DICTATION_MS) prepareForDictation()
    }

    /** A dictation starts: the model loads while the user is still speaking. */
    fun dictationStarting() {
        lastDictationAt = SystemClock.elapsedRealtime()
        prepareForDictation()
    }

    /**
     * Loads the Whisper model in the background so the transcription does not also pay for
     * reading it, and lets [scheduleModelRelease] give the memory back when nobody dictates.
     * Holding up to 1 GB around the clock would keep the process heavy and make Android more
     * eager to kill the service.
     *
     * Not on every focused text field: that read 80-570 MB from flash and built the graph each
     * time someone opened a chat after a few idle minutes, whether they dictated or not. A
     * dictation starts loading at the first tap instead, in parallel with the recording,
     * which for the FUTO models is done long before the user stops talking.
     */
    private fun prepareForDictation() {
        val engine = settings.value.activeEngine ?: return
        if (engine.kind == EngineKind.LOCAL_SHERPA) {
            if (!parakeetModels.isReady()) return
            appScope.launch(sherpaRuntime.dispatcher) {
                runCatching { sherpaRuntime.ensureLoaded(parakeetModels.dir.path) }
                    .onFailure { android.util.Log.w("Amber", "parakeet warm-up failed", it) }
            }
            scheduleModelRelease()
            return
        }
        if (engine.kind != EngineKind.WHISPER_CPP) return
        val modelId = engine.modelId ?: WhisperModels.default.id
        if (!whisperModels.isReady(modelId)) return
        // On the whisper thread: the CPU pool is created with the first load and its workers
        // inherit that thread's priority.
        appScope.launch(whisperRuntime.dispatcher) {
            runCatching { whisperRuntime.ensureLoaded(whisperModels.fileFor(modelId).path) }
                .onFailure { android.util.Log.w("Amber", "whisper warm-up failed", it) }
        }
        scheduleModelRelease()
    }

    fun scheduleModelRelease() {
        releaseJob?.cancel()
        releaseJob = appScope.launch {
            delay(MODEL_IDLE_RELEASE_MS)
            releaseModelsWhenIdle()
        }
    }

    /**
     * Frees both models on their own threads - queued behind any running inference rather
     * than blocking the caller on its lock - unless a dictation is using one right now.
     */
    fun releaseModelsWhenIdle() {
        if (dictation.isRunning) return
        appScope.launch(whisperRuntime.dispatcher) { if (!dictation.isRunning) whisperRuntime.release() }
        appScope.launch(sherpaRuntime.dispatcher) { if (!dictation.isRunning) sherpaRuntime.release() }
    }

    private companion object {
        const val MODEL_IDLE_RELEASE_MS = 3 * 60 * 1000L

        /** Someone who dictated this recently is likely to again in the next field. */
        const val RECENT_DICTATION_MS = 10 * 60 * 1000L
    }
}
