package app.amethyst

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.SystemClock
import app.amethyst.accessibility.AmethystAccessibilityService
import app.amethyst.accessibility.ReturnToAmber
import app.amethyst.apps.LauncherList
import app.amethyst.capture.MicForegroundService
import app.amethyst.core.apps.AppsSeenInFront
import app.amethyst.core.bubble.BubbleEvent
import app.amethyst.core.dictation.DictationLog
import app.amethyst.core.dictation.DictationMemory
import app.amethyst.core.dictation.ExitLog
import app.amethyst.core.dictation.ExitRecords
import app.amethyst.core.dictation.SystemExit
import app.amethyst.core.dictation.KeptDictation
import app.amethyst.core.dictation.RecordingSpool
import app.amethyst.core.text.CleanupTrouble
import app.amethyst.core.engine.AmberAddress
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.EngineRouting
import app.amethyst.core.engine.KeyBinding
import app.amethyst.core.engine.ModelLoadGuard
import app.amethyst.core.engine.ModelLoadMarks
import app.amethyst.core.engine.ModelRelease
import app.amethyst.core.engine.Problem
import app.amethyst.core.engine.RecognitionEstimate
import app.amethyst.core.engine.sherpa.ParakeetModel
import app.amethyst.core.engine.whisper.WhisperModels
import app.amethyst.core.net.OkHttpTransport
import app.amethyst.core.history.HistoryKeep
import app.amethyst.core.onboarding.RealAppNudges
import app.amethyst.core.privacy.SecurityMode
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.PrivacySettings
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.stats.StatsSummary
import app.amethyst.data.EstimateStore
import app.amethyst.data.HistoryStore
import app.amethyst.data.OwnClipboard
import app.amethyst.data.PhoneLocales
import app.amethyst.data.SecretStore
import app.amethyst.data.SettingsStore
import app.amethyst.data.StatsStore
import app.amethyst.data.StrategyStore
import app.amethyst.dictation.DictationController
import app.amethyst.dictation.SpoolWriter
import app.amethyst.engine.AmberCatalogRefresher
import app.amethyst.engine.EngineFactory
import app.amethyst.engine.FirstRunSetup
import app.amethyst.engine.ModelDownloads
import app.amethyst.engine.NativeWhisperRuntime
import app.amethyst.engine.OpusEncoder
import app.amethyst.engine.ParakeetModelStore
import app.amethyst.engine.SherpaRuntime
import app.amethyst.engine.TinfoilVerifier
import app.amethyst.engine.WhisperModelStore
import app.amethyst.update.UpdateChecker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AmethystApp : Application() {

    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        // First, so a crash while the graph is built leaves its trace too.
        val exits = ExitLog(File(noBackupFilesDir, EXITS_FILE)) { android.util.Log.w("Embersay", it) }
        exits.installCrashHandler()
        graph = Graph(this, exits)
        // How the last processes ended, as Android saw it: file I/O and a binder call, so not here.
        graph.appScope.launch(Dispatchers.IO) { graph.readExitsAtStart() }
        app.amethyst.engine.AndroidOnDeviceEngine.detectOnDeviceSpeech(this)
        // A recording a dying process left in the Opus scratch file goes; file I/O, so not here.
        graph.appScope.launch(Dispatchers.IO) { graph.opusEncoder.clearLeftovers() }
        // The ready notification is gone since 0.35 (the microphone service runs only while it
        // records): its channels leave Android's list with the update.
        graph.appScope.launch(Dispatchers.IO) { MicForegroundService.dropOldChannels(this@AmethystApp) }
        // A model whose load the last process died in is not loaded again before it is checked.
        graph.appScope.launch(Dispatchers.IO) { graph.checkModelsAtStart() }
    }

    /**
     * The models are the largest thing in this process. When the system is about to start
     * killing apps, giving them back is cheaper for the user than losing the service.
     *
     * Up to Android 13 only on real memory pressure: UI_HIDDEN and BACKGROUND are numerically
     * higher but only mean the screens went away - the bubble still dictates, and the idle
     * release handles the rest. From Android 14, which no longer sends that pressure, they are
     * a moment to look at memory again ([ModelRelease.onTrim]). And never here on the main
     * thread: a running transcription holds the model's lock, and waiting for it would freeze
     * the bubble.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (ModelRelease.onTrim(level, Build.VERSION.SDK_INT)) {
            ModelRelease.Trim.RELEASE -> graph.releaseModelsWhenIdle()
            ModelRelease.Trim.REVIEW -> graph.reviewModelRelease()
            ModelRelease.Trim.NONE -> Unit
        }
    }

    companion object {
        fun graph(context: Context): Graph =
            (context.applicationContext as AmethystApp).graph

        /** The times Embersay stopped unexpectedly ([ExitLog]), in noBackupFilesDir. */
        private const val EXITS_FILE = "exits.json"
    }
}

/** The statistics summed up ([StatsSummary]) and the day they were summed for. */
class StatsView(val today: Long, val summary: StatsSummary)

/**
 * Hand-written service locator.
 *
 * The accessibility service, the proxy activity and the UI all need the same few objects and
 * none of them is created by us, so a DI framework would buy little here. Keeping it explicit
 * also keeps the dependency list short, which matters for an app that asks for accessibility
 * and microphone access.
 */
class Graph(
    app: Application,
    /**
     * The times the app stopped unexpectedly, for Diagnostics: its own crashes, written by the
     * crash handler as they happen, and how Android says the earlier processes ended
     * ([readExitsAtStart]). Kept across restarts; nothing of what was said.
     */
    val exits: ExitLog,
) {

    /**
     * The app's background work - every dictation runs here. A failure nobody caught - an Error
     * from a native engine, a bug in some background job - is logged and kept in Diagnostics
     * instead of ending the process, which would take the bubble and a dictation in progress
     * with it.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> backgroundFailed(error) })

    /**
     * Work nobody caught a failure of in [appScope] failed: logged, and kept in Diagnostics with
     * its class and where in the app it was thrown ([ExitRecords.oneLine]), never its message.
     */
    fun backgroundFailed(error: Throwable) {
        // The class only in a release build: a message may hold a server's answer or what was heard.
        if (DebugLog.enabled) android.util.Log.e("Embersay", "background work failed", error)
        else android.util.Log.e("Embersay", "background work failed: ${error::class.java.name}")
        runCatching { memory.log.recordFailure("Background work failed unexpectedly: ${ExitRecords.oneLine(error)}") }
    }

    /**
     * How the app's earlier processes ended, as Android kept it (Android 11 and later): those since
     * the last start become [exits] - a crash, a native crash, not responding, out of memory, or
     * ended by Android while in use; none an end the user or an update asked for. On the IO pool.
     */
    fun readExitsAtStart() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Only what the crash handler wrote: read now, so Diagnostics need not.
            exits.records
            return
        }
        try {
            val activities = appContext.getSystemService(ActivityManager::class.java) ?: return
            val found = activities.getHistoricalProcessExitReasons(appContext.packageName, 0, 0).map {
                SystemExit(reason = it.reason, importance = it.importance, status = it.status, atMs = it.timestamp)
            }
            exits.foundAtStart(found)
        } catch (e: Exception) {
            android.util.Log.w("Embersay", "exit reasons not read: ${e.javaClass.simpleName}")
        }
    }

    /** A line for Diagnostics when stored settings could not be read and went aside ([app.amethyst.core.settings.SettingsRescue]). */
    val settingsStore = SettingsStore(app) { line -> runCatching { memory.log.recordFailure(line) } }
    /** Which insertion method worked in which app - not written down in the security mode. */
    val strategyStore = StrategyStore(app) { SecurityMode.remembersApps(loadedSettings.value) }

    // Before [secretStore], which sets it: a start's background work may build that while
    // the rest of the graph is still being made.
    private val secretStoreBuilt = MutableStateFlow(false)

    /**
     * Lazy on purpose. Building this generates or unwraps a keystore-backed master key and
     * initialises Tink - the slowest thing in the app on a first launch. In
     * Application.onCreate it would delay the first frame for everyone, including users who
     * never configure a cloud engine at all.
     */
    val secretStore: SecretStore by lazy { SecretStore(app).also { secretStoreBuilt.value = true } }

    /**
     * True once [secretStore] is built - or building it failed and nothing is gained by
     * waiting. Screens that ask it about keys wait for this instead of building it on the main
     * thread themselves ([warmSecretStore]).
     */
    val secretStoreReady: StateFlow<Boolean> = secretStoreBuilt

    /**
     * Builds [secretStore] on the IO pool - called as Amber's screens open, which ask it about
     * keys a moment later. The accessibility service builds it with [warmUp].
     */
    fun warmSecretStore() {
        if (secretStoreBuilt.value) return
        appScope.launch(Dispatchers.IO) {
            runCatching { secretStore }
                .onFailure { android.util.Log.w("Embersay", "secret store not opened: ${it.javaClass.simpleName}") }
            secretStoreBuilt.value = true
        }
    }

    /** [engineFactory] for a caller that may be on the main thread: built on the IO pool the first time. */
    suspend fun engines(): EngineFactory = withContext(Dispatchers.IO) { engineFactory }

    /**
     * The stored settings, null until DataStore has answered. [settings] starts on the defaults,
     * which would show a fresh install's introduction to everyone for a moment; the start screen
     * is decided from this instead. So is every privacy question ([SecurityMode]): asked of the
     * defaults, a security mode that is on would be off for that moment.
     *
     * The one reader of the store: each change is decoded once, here, and everything else in
     * the process follows this flow rather than the store's.
     */
    val loadedSettings: StateFlow<AmethystSettings?> = settingsStore.settings
        .stateIn(appScope, SharingStarted.Eagerly, null)

    /** Synchronously readable settings, for the service paths that cannot suspend. */
    val settings: StateFlow<AmethystSettings> = loadedSettings.filterNotNull()
        .stateIn(appScope, SharingStarted.Eagerly, AmethystSettings.defaults(PhoneLocales.tags()))

    /** The stored settings once they are read: what a one-off look at them suspends for. */
    private suspend fun storedSettings(): AmethystSettings = loadedSettings.filterNotNull().first()

    /**
     * The markers around each model's native load, and the models under suspicion
     * ([ModelLoadGuard]). In noBackupFilesDir, beside the models: they say something about these
     * files on this phone only. Reads nothing until first asked. A file it cannot write (storage
     * full) never stops a load: logged by its class, kept in memory meanwhile.
     */
    val modelMarks = ModelLoadMarks(File(app.noBackupFilesDir, "model-guard"), trouble = { android.util.Log.w("Embersay", it) })

    val whisperRuntime = NativeWhisperRuntime(app, modelMarks)

    val whisperModels: WhisperModelStore by lazy { WhisperModelStore(app, appScope, whisperRuntime, modelMarks) { httpTransport.downloadClient() } }

    val sherpaRuntime = SherpaRuntime(modelMarks)

    val parakeetModels: ParakeetModelStore by lazy { ParakeetModelStore(app, appScope, sherpaRuntime, modelMarks) { httpTransport.downloadClient() } }

    /** The introduction's downloads, and whether the active engine is still waiting for one. */
    val modelDownloads: ModelDownloads by lazy { ModelDownloads(app, appScope, parakeetModels, whisperModels) }

    /** What the Tinfoil verifier did lately, newest first, for the engine screen's debug section. */
    val tinfoilLog = MutableStateFlow<List<String>>(emptyList())

    /** One per process, so its cache of proofs spans dictations. */
    val tinfoilVerifier: TinfoilVerifier by lazy { TinfoilVerifier(tinfoilLog) }

    /** One HTTP client for the engines, the model lists and the model downloads, so they share connections and threads. */
    val httpTransport: OkHttpTransport by lazy { OkHttpTransport() }

    /** Compresses recordings for Amber servers that take Ogg Opus. */
    val opusEncoder = OpusEncoder(app)

    // Lazy too, so that merely building the graph does not force the secret store open.
    val engineFactory: EngineFactory by lazy {
        EngineFactory(
            app, httpTransport, secretStore, whisperModels, whisperRuntime, tinfoilVerifier, parakeetModels, sherpaRuntime,
            encoder = opusEncoder,
            settings = { settings.value },
            compressionRefused = { engineId, baseUrl -> amberCatalogs.compressionRefused(engineId, baseUrl) },
            estimate = { recognitionEstimate },
        )
    }

    /** The model lists of Amber PCs and Amber Servers, fetched in the background. */
    val amberCatalogs: AmberCatalogRefresher by lazy {
        AmberCatalogRefresher(
            appScope, httpTransport,
            { engine -> secretStore.get(engine.apiKeyAlias)?.takeIf { KeyBinding.allows(engine) } },
            { settings.value }, settingsStore::update,
        )
    }

    /**
     * The update check of the GitHub build: at most once a day, when Amber's screen opens or the
     * service connects. Every other build never asks. Built with the graph, so which build this
     * is gets read on the IO pool right at the start, before any screen needs it.
     */
    val updates = UpdateChecker(app, appScope, { httpTransport }, loadedSettings, settingsStore::update)

    /** The launcher's apps for voice commands and the app pickers, read once until a package changes. */
    val launcherList = LauncherList(app)

    /** The apps the accessibility service saw in front: an app in another profile counts as one ([app.amethyst.apps.Launcher.installed]). */
    val appsSeenInFront = AppsSeenInFront()

    /** What a new install is given without being asked, and the recognition that waits for its model. */
    val firstRun = FirstRunSetup(app, appScope, settingsStore, loadedSettings, { modelDownloads }, launcherList)

    /**
     * How long recognition, upload and cleanup take per engine and model on this phone, for the
     * progress ring; kept across restarts, read on the IO pool.
     */
    val recognitionEstimate = RecognitionEstimate()
    private val estimateStore = EstimateStore(app)

    init {
        appScope.launch(Dispatchers.IO) { recognitionEstimate.restore(estimateStore.load()) }
        appScope.launch(Dispatchers.IO) { moveAmberPathKeys() }
    }

    /**
     * An Amber PC or Amber Server stored with its key in the address's path gets the two apart,
     * once: the key into the encrypted store first, then the address without it. Done again on
     * every start until nothing is left to move, so a crash between the two loses nothing.
     */
    private suspend fun moveAmberPathKeys() {
        try {
            // What is stored under each alias decides with the key's shape: a server with
            // another key already there is not an old Amber, and keeps both.
            val stored = { alias: String -> secretStore.get(alias) }
            val (_, moves) = AmberAddress.movePathKeys(storedSettings(), stored)
            if (moves.isEmpty()) return
            moves.forEach { move -> secretStore.put(move.alias, move.key) }
            settingsStore.update { AmberAddress.movePathKeys(it, stored).first }
            // Never the address or the key: only that it happened.
            android.util.Log.i("Embersay", "moved the key out of ${moves.size} Embersay server address(es)")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("Embersay", "Embersay server keys not moved: ${e.javaClass.simpleName}")
        }
    }

    /** Keeps what [recognitionEstimate] learned, off the caller's thread. */
    fun saveEstimates() {
        val rates = recognitionEstimate.snapshot()
        appScope.launch(Dispatchers.IO) { estimateStore.save(rates) }
    }

    /** What the keyboard tracker decided lately, newest first, for the diagnostics card. */
    val keyboardLog = MutableStateFlow<List<String>>(emptyList())

    /**
     * One of Amber's own text fields has focus while its screen is in front
     * ([app.amethyst.ui.reportsOwnField]). Below Android 13 the service has no other way to
     * learn of it: a Compose field gives Android no focused node to name. The activity and the
     * service share this process, so the screen tells the service here.
     */
    val ownFieldFocused = MutableStateFlow(false)

    /**
     * Everything Amber holds of what was dictated, and the one call that forgets it all
     * ([DictationMemory.wipe]): the records Diagnostics shows ([DictationMemory.log]), a failed
     * dictation's recording for "Try again" and the last dictation for Home and the quick menu
     * ([DictationMemory.kept], on the clock of the records), the insertion "Undo last" may take
     * back - and here the clipboard while Amber's clip is on it, and the scratch file a recording
     * is compressed in.
     */
    val memory = DictationMemory(KeptDictation({ System.currentTimeMillis() }), DictationLog()).apply {
        hold { OwnClipboard.forgetIfOurs(app) }
        hold { opusEncoder.clearLeftovers() }
        // Nothing kept until the settings say the security mode is off.
        keepsWords = SecurityMode.keepsWords(loadedSettings.value)
    }

    init {
        // After [memory], so the line lands in Diagnostics.
        appScope.launch(Dispatchers.IO) { settingsStore.rescueAtStart() }
    }

    /** A cleanup that will not run until the user puts something right, for a toast and the row on top of Settings. */
    val cleanupTrouble = CleanupTrouble()

    init {
        // A cleanup turned off, or another service or model in its place: the old trouble goes.
        appScope.launch { settings.collect(cleanupTrouble::settingsChanged) }
    }

    /** A key was saved for [alias]: a cleanup trouble with its service may be put right. */
    fun keySaved(alias: String) =
        cleanupTrouble.keySaved(settings.value.engines.filter { it.apiKeyAlias == alias }.map { it.id })

    /** Counts and timings of every dictation, for the statistics; never text or audio. */
    val stats = StatsStore(app, appScope) { loadedSettings.value }

    /**
     * The statistics summed up for Home and the statistics page, with the day they were summed
     * for: on the Default pool, not in a screen's composition - again with every dictation
     * (which may be the first of a new day), a new typing speed, and at midnight, so an app left
     * open overnight starts the new day at zero. Only while a screen shows them; the last
     * summary stays for the next screen, a rotation's included.
     */
    val statsSummary: StateFlow<StatsView> = combine(
        stats.all,
        settings.map { it.stats.typingWpm }.distinctUntilChanged(),
        midnights(),
    ) { all, typingWpm, _ ->
        val today = stats.dayOf(System.currentTimeMillis())
        StatsView(today, StatsSummary.of(all, today, typingWpm))
    }
        .flowOn(Dispatchers.Default)
        .stateIn(appScope, SharingStarted.WhileSubscribed(STATS_KEPT_MS), stats.dayOf(System.currentTimeMillis()).let { StatsView(it, StatsSummary.of(emptyList(), it)) })

    /** Once now and at every midnight after; a delay stands still while the phone sleeps, and a dictation asks the day itself. */
    private fun midnights(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(stats.millisUntilNextDay(System.currentTimeMillis()))
        }
    }

    private val amberPackage = app.packageName

    private val appContext: Context = app

    /**
     * The recent dictations, encrypted on this phone for as long as the user chose
     * ([SecurityMode.historyKeep]); forgotten with everything else ([DictationMemory.wipe]).
     */
    val history = HistoryStore(app).also { store -> memory.hold { store.deleteAll() } }

    /**
     * A running dictation's recording on disk, sealed with the history's keys - only with the
     * history on - so a process killed meanwhile does not lose it: Home offers it at the next
     * start ([RecordingSpool]). In noBackupFilesDir: never in a backup, and its keys could not go
     * along anyway. Forgotten with everything else, and with the history: off, or "Delete all".
     */
    val spool = RecordingSpool(File(app.noBackupFilesDir, "recording"), history.lock, memory.kept).also { spool ->
        memory.hold { spool.deleteAll() }
    }

    /** Feeds [spool] from the recorder, off its thread. */
    val spoolWriter = SpoolWriter(spool)

    init {
        // The kept recording's disk copy follows what is kept in memory: gone with it, for as long as it.
        appScope.launch(Dispatchers.IO) {
            memory.kept.changes.collect {
                try {
                    spool.sync()
                } catch (e: Exception) {
                    android.util.Log.w("Embersay", "the recording on disk was not tidied: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /** Counts every change of the history, for the screens that show it. */
    val historyChanges = MutableStateFlow(0L)

    /**
     * What is past its time goes, and the keys of the days past it - and a recording left on disk
     * past its time ([RecordingSpool.prune]) - when the screen goes off, as well as with every
     * dictation added and every count. Only once the settings are read:
     * before, "off" would delete it all.
     */
    fun pruneHistory() {
        val settings = loadedSettings.value ?: return
        appScope.launch(Dispatchers.IO) {
            val keep = SecurityMode.historyKeep(settings)
            // A recording an earlier process left goes after 24 hours, Home opened or not.
            pruneSpool(keep)
            history.prune(keep)
            historyChanges.update { it + 1 }
        }
    }

    /** [RecordingSpool.prune]: a trouble with its files never stops the history being pruned. */
    private fun pruneSpool(keep: HistoryKeep) {
        try {
            spool.prune(keep)
        } catch (e: Exception) {
            android.util.Log.w("Embersay", "the recording on disk was not tidied: ${e.javaClass.simpleName}")
        }
    }

    init {
        // What is past its time goes at every start and whenever the choice changes - off, or
        // the security mode, takes it all.
        appScope.launch(Dispatchers.IO) {
            loadedSettings.filterNotNull().map(SecurityMode::historyKeep).distinctUntilChanged().collect { keep ->
                // Off, its keys go: so does the recording sealed with them.
                if (keep == HistoryKeep.OFF) runCatching { spool.deleteAll() }
                pruneSpool(keep)
                history.prune(keep)
                historyChanges.update { it + 1 }
            }
        }
    }

    /**
     * "Delete all" on the History page: every dictation and the keys they were sealed with - and
     * Amber's own clip, a dictation copied from the page. Called on the main thread, with Amber in
     * front: the only moment Android lets it see whose clip is on the clipboard.
     */
    fun deleteHistory() {
        OwnClipboard.forgetIfOurs(appContext)
        appScope.launch(Dispatchers.IO) {
            try {
                // The recording on disk is sealed with the keys that go now.
                spool.deleteAll()
                history.deleteAll()
            } catch (e: Exception) {
                android.util.Log.w("Embersay", "history not deleted: ${e.javaClass.simpleName}")
            }
            historyChanges.update { it + 1 }
        }
    }

    /**
     * Keeps a dictation's [text], dictated into [app], in the history - unless the history is off,
     * the security mode on, or the phone locked. Off the caller's thread.
     */
    fun keepInHistory(text: String, app: String?) {
        if (SecurityMode.historyKeep(loadedSettings.value) == HistoryKeep.OFF || text.isBlank()) return
        // A "Delete all" or the security mode's wipe before the add runs refuses it.
        val ticket = history.ticket()
        appScope.launch(Dispatchers.IO) {
            // Asked again as it is written, under the lock "Delete all" takes: the security mode
            // may have gone on meanwhile.
            history.add(text, app, ticket) { SecurityMode.historyKeep(loadedSettings.value) }
            // With every dictation, as the history's own past days go: a leftover past its time too.
            pruneSpool(SecurityMode.historyKeep(loadedSettings.value))
            historyChanges.update { it + 1 }
        }
    }

    init {
        // The security mode as the settings say: on, nothing more is kept; switched on, what is
        // kept now is forgotten. Not on reading the settings at a start - nothing switched then;
        // but with the mode on, what an earlier switch may have left is looked for again.
        appScope.launch {
            var before: PrivacySettings? = null
            loadedSettings.filterNotNull().collect { current ->
                memory.keepsWords = SecurityMode.keepsWords(current)
                if (SecurityMode.wipesOnSwitch(before, current.privacy)) forgetForSecurityMode()
                if (SecurityMode.repairsAtStart(before, current)) repairForSecurityMode()
                before = current.privacy
            }
        }
        // Statistics that name an app from before the nudge's end was remembered: remembered now,
        // so clearing them or losing their app names cannot bring the nudge back.
        appScope.launch { stats.all.collect { rememberRealApp(it) } }
    }

    /**
     * The security mode was switched on: everything dictated is forgotten ([DictationMemory.wipe]),
     * the statistics are written again without their apps and the insertion methods per app go.
     */
    private fun forgetForSecurityMode() {
        appScope.launch(Dispatchers.IO) {
            rememberRealApp(stats.all.value)
            try {
                memory.wipe()
            } catch (e: Exception) {
                android.util.Log.w("Embersay", "not everything dictated was forgotten: ${e.javaClass.simpleName}")
            }
            stats.forgetApps()
            strategyStore.forget()
            android.util.Log.i("Embersay", "security mode on: what was kept is forgotten")
        }
    }

    /**
     * A start with the security mode on ([SecurityMode.repairsAtStart]): app names still in the
     * statistics and insertion methods still written down per app - left when the process died
     * between switching the mode on and forgetting, or the forgetting failed - go now. The
     * history goes with the prune above, which the mode turns into "delete all".
     */
    private fun repairForSecurityMode() {
        stats.forgetAppsIfNamed { before -> rememberRealApp(before) }
        appScope.launch(Dispatchers.IO) {
            try {
                if (strategyStore.forgetIfAny()) android.util.Log.i("Embersay", "security mode: insertion methods per app left over removed")
            } catch (e: Exception) {
                android.util.Log.w("Embersay", "insertion methods per app not removed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** The statistics are cleared - once the nudge's end is remembered apart from them. */
    fun clearStats() {
        appScope.launch {
            rememberRealApp(stats.all.value)
            stats.clear()
        }
    }

    private suspend fun rememberRealApp(stats: List<app.amethyst.core.stats.DictationStat>) {
        if (RealAppNudges.reached(remembered = false, stats)) rememberRealApp()
    }

    /** A dictation went into another app: the nudge toward one is over for good ([RealAppNudges.reached]). */
    private suspend fun rememberRealApp() {
        if (storedSettings().firstRun.realAppReached) return
        settingsStore.update { it.copy(firstRun = it.firstRun.copy(realAppReached = true)) }
    }

    /** A dictation went into [app]: past the nudge if that is another app than Amber ([RealAppNudges.ends]). */
    fun dictatedInto(app: String?) {
        if (!RealAppNudges.ends(app, amberPackage) || settings.value.firstRun.realAppReached) return
        appScope.launch { rememberRealApp() }
    }

    /**
     * What the bubble just did - a dictation inserted, a quick action run, a dock - for the
     * introduction's practice step, the only one listening. No replay: with nobody listening
     * an event is simply gone. Emitted with tryEmit, from any thread.
     */
    val bubbleEvents = MutableSharedFlow<BubbleEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val dictation = DictationController(app, this, appScope)

    private val connection = MutableStateFlow(false)

    /**
     * Whether the accessibility service is connected, following [accessibilityService]: Home and
     * the practice step look at the service's health again when it connects or goes away.
     */
    val serviceConnected: StateFlow<Boolean> get() = connection

    /** Set by the accessibility service while it is connected; null means no insertion path. */
    @Volatile
    var accessibilityService: AmethystAccessibilityService? = null
        set(value) {
            field = value
            connection.value = value != null
        }

    /** Short messages over another app: the service's own overlay while it is connected, else a toast. */
    val messages = Messages(app) { accessibilityService }

    /** Brings Amber back from Android's settings once what it sent the user for is on. */
    val returnToAmber = ReturnToAmber(app, appScope) { accessibilityService }

    private val releaseLock = Any()
    private var releaseJob: Job? = null

    /** When [releaseJob] frees the models (elapsedRealtime), so a review only ever brings it forward. */
    private var releaseAt = Long.MAX_VALUE

    @Volatile
    private var lastDictationAt = 0L

    /**
     * Builds what the first dictation after a process start would otherwise build while the
     * bubble says "Starting": the secret store (keystore, Tink, the list of stored keys), the
     * HTTP client and the engine factory - and, with voice commands on, the launcher list. On
     * the IO pool, called when the accessibility service connects; a failure here only means
     * the first dictation builds them itself, as it always did.
     */
    fun warmUp() {
        appScope.launch(Dispatchers.IO) {
            runCatching { engineFactory }
                .onFailure { android.util.Log.w("Embersay", "warm-up of the engine factory failed: ${it.javaClass.simpleName}") }
            if (loadedSettings.filterNotNull().first().commandsEnabled) {
                runCatching { launcherList.appsBlocking() }
                    .onFailure { android.util.Log.w("Embersay", "warm-up of the launcher list failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    /**
     * The speech recognition for a dictation into [packageName]: the app's own while it is
     * ready, else the one for every app ([EngineRouting]). The key store is only touched for an
     * engine with a key alias ([EngineRouting.notUsable]), so a start with the phone's own or an
     * on-device recognition never builds it on the main thread; the lazy store itself builds on IO
     * ([warmSecretStore]).
     */
    fun engineFor(packageName: String?): EngineRouting.Decision =
        EngineRouting.decide(settings.value, packageName, { secretStore.has(it) }, ::modelReady)

    /**
     * At the start, on the IO pool: a model whose native load the last process died in is
     * suspect ([ModelLoadGuard.verdictAtStart]) - Diagnostics name it - and every suspect model
     * still unchecked, that one or one whose check an earlier process did not finish, has its
     * files checked now.
     */
    fun checkModelsAtStart() {
        for (left in modelMarks.leftAtStart()) {
            modelNote("The app ended while loading the model ${left.modelId}. It is not loaded again until its files are checked.")
        }
        modelMarks.checking().forEach(::checkModel)
    }

    /**
     * [engine]'s model on the phone failed for [problem]. Twice in a row for
     * [Problem.LocalModelFailed] ([ModelLoadGuard.afterFailure]) and it is suspect: not loaded
     * again, and its files are checked.
     */
    suspend fun modelFailed(engine: EngineConfig, problem: Problem) {
        val id = modelIdOf(engine) ?: return
        val verdict = withContext(Dispatchers.IO) { modelMarks.failed(id, problem) }
        if (verdict !is ModelLoadGuard.Verdict.Damaged) return
        modelNote("The model $id failed ${ModelLoadGuard.FAILURES_IN_A_ROW} times in a row. It is not loaded again until its files are checked.")
        checkModel(id)
    }

    /** [engine]'s model on the phone recognized: its failures start over ([ModelLoadGuard.afterFailure]). */
    suspend fun modelWorked(engine: EngineConfig) {
        val id = modelIdOf(engine) ?: return
        withContext(Dispatchers.IO) { modelMarks.worked(id) }
    }

    /** The id [ModelLoadMarks] knows [engine]'s model on the phone by; null for an engine elsewhere. */
    private fun modelIdOf(engine: EngineConfig): String? = when (engine.kind) {
        EngineKind.LOCAL_SHERPA -> ParakeetModel.ID
        EngineKind.WHISPER_CPP -> engine.modelId ?: WhisperModels.default.id
        else -> null
    }

    /** Checks the files of the suspect model [id] in the background; Diagnostics say what came of it. */
    private fun checkModel(id: String) {
        appScope.launch(Dispatchers.IO) {
            val health = if (id == ParakeetModel.ID) parakeetModels.recheck() else whisperModels.recheck(id)
            when (health) {
                null -> modelNote("The files of the model $id checked out. It is used again.")
                ModelLoadGuard.Health.DAMAGED -> modelNote("The model $id is damaged. Download it again on its page.")
                ModelLoadGuard.Health.CHECKING -> Unit
            }
        }
    }

    /** A line about a model's health for logcat and Diagnostics: its id and what happened, nothing else. */
    private fun modelNote(line: String) {
        android.util.Log.w("Embersay", line)
        runCatching { memory.log.recordFailure(line) }
    }

    /** Whether a model on the phone is downloaded; an engine elsewhere needs none. */
    fun modelReady(config: EngineConfig): Boolean = when (config.kind) {
        EngineKind.LOCAL_SHERPA -> parakeetModels.isReady()
        EngineKind.WHISPER_CPP -> whisperModels.isReady(config.modelId ?: WhisperModels.default.id)
        else -> true
    }

    /**
     * A field gained focus, in the app [packageName] names - asked only when needed. Preloads
     * that app's speech recognition, only for someone who dictated a moment ago.
     */
    fun textFieldFocused(packageName: () -> String?) {
        if (SystemClock.elapsedRealtime() - lastDictationAt < RECENT_DICTATION_MS) prepareForDictation(engineFor(packageName()).engine)
    }

    /** A dictation with [engine] starts: its model loads while the user is still speaking. */
    fun dictationStarting(engine: EngineConfig?) {
        lastDictationAt = SystemClock.elapsedRealtime()
        prepareForDictation(engine)
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
    private fun prepareForDictation(engine: EngineConfig?) {
        if (engine == null) return
        if (engine.kind == EngineKind.LOCAL_SHERPA) {
            if (!parakeetModels.isReady()) return
            appScope.launch(sherpaRuntime.dispatcher) {
                runCatching { sherpaRuntime.ensureLoaded(parakeetModels.dir.path) }
                    .onFailure { android.util.Log.w("Embersay", "parakeet warm-up failed", it) }
            }
            scheduleRelease({ maxOf(loadedModelBytes(), ParakeetModel.TOTAL_BYTES) }, ModelRelease::beforeDictation)
            return
        }
        if (engine.kind != EngineKind.WHISPER_CPP) return
        val modelId = engine.modelId ?: WhisperModels.default.id
        if (!whisperModels.isReady(modelId)) return
        // On the whisper thread: the CPU pool is created with the first load and its workers
        // inherit that thread's priority.
        appScope.launch(whisperRuntime.dispatcher) {
            runCatching { whisperRuntime.ensureLoaded(whisperModels.fileFor(modelId).path) }
                .onFailure { android.util.Log.w("Embersay", "whisper warm-up failed", it) }
        }
        val file = whisperModels.fileFor(modelId)
        scheduleRelease({ maxOf(loadedModelBytes(), file.length()) }, ModelRelease::beforeDictation)
    }

    /**
     * A dictation ended: the models go after their idle time - or sooner when memory is short
     * on Android 14 and later, which no longer warns of it ([ModelRelease.afterDictation]).
     */
    fun scheduleModelRelease() {
        scheduleRelease(::loadedModelBytes, ModelRelease::afterDictation)
    }

    /**
     * The screens went away on Android 14 or later: memory is looked at again, and a release
     * comes forward when it is short. The usual idle time is left as it runs.
     */
    fun reviewModelRelease() {
        appScope.launch {
            val bytes = loadedModelBytes()
            val wait = ModelRelease.afterDictation(memoryNow(), bytes)
            if (wait == ModelRelease.Wait.IDLE) return@launch
            // A timer that fires as early already stays.
            val due = SystemClock.elapsedRealtime() + wait.ms
            if (synchronized(releaseLock) { releaseJob?.isActive == true && releaseAt <= due }) return@launch
            scheduleRelease({ bytes }) { _, _ -> wait }
        }
    }

    /**
     * (Re)starts the one timer that frees the models. The memory and the [modelBytes] the
     * decision is about are asked inside it, off the caller's thread - a dictation's start is
     * often on the main thread.
     */
    private fun scheduleRelease(modelBytes: () -> Long, decide: (ModelRelease.Memory?, Long) -> ModelRelease.Wait) {
        synchronized(releaseLock) {
            releaseJob?.cancel()
            releaseAt = Long.MAX_VALUE
            releaseJob = appScope.launch {
                val memory = memoryNow()
                val bytes = modelBytes()
                val wait = decide(memory, bytes)
                synchronized(releaseLock) { if (releaseJob === coroutineContext[Job]) releaseAt = SystemClock.elapsedRealtime() + wait.ms }
                if (wait != ModelRelease.Wait.IDLE) {
                    android.util.Log.i("Embersay", "memory is short: the speech model goes ${if (wait == ModelRelease.Wait.NOW) "now" else "in ${wait.ms / 1000} s"} (${memoryLine(memory, bytes)})")
                }
                delay(wait.ms)
                // The dictation that asked may still be finishing; one that runs longer is the
                // one whose end starts this timer over.
                while (dictation.isRunning) delay(RELEASE_RECHECK_MS)
                releaseModelsWhenIdle()
            }
        }
    }

    /** Loaded models' bytes, both runtimes - a change of engine may leave the other loaded until the release. */
    private fun loadedModelBytes(): Long = whisperRuntime.loadedBytes() + sherpaRuntime.loadedBytes()

    /** The memory the phone reports (Android's totalMem) in MB, 0 when it gives none. A binder call: never on the main thread. */
    fun totalRamMb(): Long = (memoryNow()?.totalBytes ?: 0L) / 1_000_000

    /** Android's view of memory now; null when it gives none. A binder call: never on the main thread. */
    private fun memoryNow(): ModelRelease.Memory? = runCatching {
        val activities = appContext.getSystemService(ActivityManager::class.java) ?: return null
        val info = ActivityManager.MemoryInfo().also(activities::getMemoryInfo)
        ModelRelease.Memory(Build.VERSION.SDK_INT, activities.isLowRamDevice, info.lowMemory, info.availMem, info.threshold, info.totalMem)
    }.getOrNull()

    /**
     * The numbers behind a short-memory release, for logcat - no words, no apps. The very ones
     * the decision was made on: [modelBytes] is the model about to load at a dictation's start.
     */
    private fun memoryLine(memory: ModelRelease.Memory?, modelBytes: Long): String {
        if (memory == null) return "no memory info"
        return "available ${memory.availBytes / 1_000_000} MB, threshold ${memory.thresholdBytes / 1_000_000} MB, " +
            "total ${memory.totalBytes / 1_000_000} MB, model ${modelBytes / 1_000_000} MB, " +
            "low memory ${memory.lowMemory}, low-RAM phone ${memory.lowRamDevice}"
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

    /**
     * Frees the model [engine] runs on the phone now, while a dictation runs: after it ran out
     * of memory or failed ([app.amethyst.core.engine.SttFailure.localModelFailed]), so the
     * fallback or "Try again" loads into the room it held - on a 3 GB phone two models never
     * fit at once. On the model's own thread, behind a recognition still on it. Nothing for an
     * engine elsewhere.
     */
    suspend fun releaseModelOf(engine: EngineConfig) {
        when (engine.kind) {
            EngineKind.LOCAL_SHERPA -> withContext(sherpaRuntime.dispatcher) { sherpaRuntime.release() }
            EngineKind.WHISPER_CPP -> withContext(whisperRuntime.dispatcher) { whisperRuntime.release() }
            else -> Unit
        }
    }

    private companion object {
        /** How long the statistics are kept summed up after the last screen stopped showing them. */
        const val STATS_KEPT_MS = 5_000L

        /** How often a release that came due during a dictation looks again whether it ended. */
        const val RELEASE_RECHECK_MS = 1_000L

        /** Someone who dictated this recently is likely to again in the next field. */
        const val RECENT_DICTATION_MS = 10 * 60 * 1000L
    }
}
