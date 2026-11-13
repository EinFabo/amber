package app.amethyst.ui

import android.Manifest
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.amethyst.AmethystApp
import app.amethyst.Graph
import app.amethyst.R
import app.amethyst.Texts
import app.amethyst.apps.Launcher
import app.amethyst.capture.MicForegroundService
import app.amethyst.core.apps.AppChoice
import app.amethyst.core.capture.TriggerSource
import app.amethyst.core.dictation.DictationLog
import app.amethyst.core.engine.EngineOption
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.PhoneFacts
import app.amethyst.core.engine.KeyBinding
import app.amethyst.core.engine.ServerModels
import app.amethyst.core.engine.Servers
import app.amethyst.core.onboarding.Onboarding
import app.amethyst.core.onboarding.OnboardingStep
import app.amethyst.core.onboarding.SettingsTrip
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppProfiles
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.LanguageList
import app.amethyst.core.settings.ThemeMode
import app.amethyst.core.stats.StatsSummary
import app.amethyst.core.update.UpdatePolicy
import app.amethyst.data.PhoneLocales
import app.amethyst.engine.AndroidOnDeviceEngine
import app.amethyst.engine.ModelDownloads
import app.amethyst.engine.ParakeetState
import app.amethyst.ui.AndroidAppSource
import app.amethyst.ui.onboarding.OnboardingContent
import app.amethyst.ui.onboarding.OnboardingScreen
import app.amethyst.ui.screen.AppStyleScreen
import app.amethyst.ui.screen.AppStylesScreen
import app.amethyst.ui.screen.BubbleScreen
import app.amethyst.ui.screen.CleanupAccess
import app.amethyst.ui.screen.CleanupScreen
import app.amethyst.ui.screen.DiagnosticsScreen
import app.amethyst.ui.screen.EngineDetailScreen
import app.amethyst.ui.screen.FallbackScreen
import app.amethyst.ui.screen.HomeScreen
import app.amethyst.ui.screen.InsertingScreen
import app.amethyst.ui.screen.LicensesScreen
import app.amethyst.ui.screen.QuickMenuScreen
import app.amethyst.ui.screen.RecognitionScreen
import app.amethyst.ui.screen.RecordingScreen
import app.amethyst.ui.screen.ReliabilityScreen
import app.amethyst.ui.screen.SettingsPage
import app.amethyst.ui.screen.SettingsScreen
import app.amethyst.ui.screen.StatsScreen
import app.amethyst.ui.screen.UpdatesScreen
import app.amethyst.ui.screen.VoiceCommandsScreen
import app.amethyst.ui.theme.AmberColors
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    private val setupState = MutableStateFlow(SetupState())

    /** Sent to switch the accessibility service on and not back with it on yet; see [SetupState.accessibilityTried]. */
    private var accessibilityTried = false

    /** An app whose page the quick menu asked for, until the screens have opened it. */
    private val appPageRequest = MutableStateFlow<String?>(null)

    private val requestMicrophone =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            refreshSetup()
            // The mic foreground service may only be started while we are visible, so this
            // is the right moment - right after the grant, with the app in front.
            if (granted) MicForegroundService.start(this)
        }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshSetup() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Diagnostics and the test field show recent dictations: keep them out of the Recents
        // thumbnail. Screenshots stay possible - they are how a problem gets reported.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        // The background runs behind the status and navigation bars; their icons follow the
        // theme once it is known (applyTheme), the phone's until then.
        enableEdgeToEdge()
        val graph = AmethystApp.graph(this)
        accessibilityTried = savedInstanceState?.getBoolean(KEY_ACCESSIBILITY_TRIED) ?: false
        refreshSetup()
        // Recreated (a rotation): the page asked for was opened then.
        if (savedInstanceState == null) takeAppPage(intent)

        setContent {
            // Null until the settings are read: as the phone is set, which on Android 12 and
            // later already is the choice made last time (applyTheme).
            val theme = graph.loadedSettings.collectAsStateWithLifecycle().value?.ui?.theme
            val dark = (theme ?: ThemeMode.SYSTEM).dark(isSystemInDarkTheme())
            LaunchedEffect(theme, dark) { applyTheme(theme, dark) }
            AmberTheme(dark) {
                AmethystRoot(
                    graph = graph,
                    appPage = appPageRequest.collectAsStateWithLifecycle().value,
                    onAppPageOpened = { appPageRequest.value = null },
                    onInstallLanguage = { tag -> installLanguage(tag) },
                    setup = setupState.collectAsStateWithLifecycle().value,
                    actions = SetupActions(
                        requestMicrophone = {
                            requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        requestNotifications = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        openAccessibility = ::openAccessibility,
                        openAccessibilityList = ::openAccessibilityList,
                        openAppInfo = { Setup.openAppSettings(this) },
                        openBattery = ::openBattery,
                        startMicService = {
                            MicForegroundService.start(this)
                            refreshSetup()
                        },
                    ),
                )
            }
        }
    }

    /**
     * What of the theme lies outside the screens: the status and navigation bar icons, dark on
     * a light page, and the window behind the page. From Android 12 the choice is also given to
     * Android ([theme], null while not read yet), which then starts Amber in it - no flash of
     * the other theme at the next launch. Before Android 12 a launch shows the phone's theme
     * for a moment when the choice differs.
     */
    private fun applyTheme(theme: ThemeMode?, dark: Boolean) {
        if (theme != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(UiModeManager::class.java)?.setApplicationNightMode(
                when (theme) {
                    ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                    ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                    ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                },
            )
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(NAV_SCRIM_LIGHT, NAV_SCRIM_DARK) { dark },
        )
        window.setBackgroundDrawable(ColorDrawable(AmberColors.of(dark).background.toArgb()))
    }

    /** Brought to the front again by the quick menu, perhaps for an app's page. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeAppPage(intent)
    }

    override fun onResume() {
        super.onResume()
        // Back in front, by the service or by hand: nothing left to bring back.
        AmethystApp.graph(this).returnToAmber.cancel()
        // Accessibility and battery state change outside the app, in system settings.
        refreshSetup()
        ensureMicServiceRunning()
        // Amber's screen is open: the GitHub build asks for a new release, once a day at most.
        AmethystApp.graph(this).updates.checkIfDue()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ACCESSIBILITY_TRIED, accessibilityTried)
    }

    /**
     * To Amber's switch in Android's settings, asking to be brought back once it is on. The
     * screens learn the trip was made, so a sideloaded Amber can show the way past Android's
     * lock if the user comes back without it.
     */
    private fun openAccessibility() {
        val back = AmethystApp.graph(this).returnToAmber
        back.sending(SettingsTrip.ACCESSIBILITY)
        if (Setup.openAccessibilitySettings(this)) {
            accessibilityTried = true
        } else {
            back.cancel()
            Toast.makeText(this, R.string.accessibility_not_opened, Toast.LENGTH_LONG).show()
        }
        refreshSetup()
    }

    /**
     * To the accessibility list without a trip back: the service is on already, the user goes
     * there to switch another Amber off ([SetupState.otherAmbers]).
     */
    private fun openAccessibilityList() {
        if (!Setup.openAccessibilitySettings(this)) {
            Toast.makeText(this, R.string.accessibility_not_opened, Toast.LENGTH_LONG).show()
        }
    }

    /** To the battery list, watched while the user is there; back to Amber once Amber is exempt. */
    private fun openBattery() {
        val back = AmethystApp.graph(this).returnToAmber
        back.sending(SettingsTrip.BATTERY)
        if (!Setup.openBatterySettings(this)) back.cancel()
    }

    /**
     * Starting a microphone foreground service is only permitted while the app is visible,
     * and this is the one moment that is reliably true. Doing it on every resume keeps later
     * bubble taps off the proxy-activity fallback - including after a reboot, where nothing
     * else could have started it.
     */
    private fun ensureMicServiceRunning() {
        val state = setupState.value
        if (!state.microphoneGranted || state.micServiceRunning) return

        MicForegroundService.start(this)
        // The service flips its flag in onStartCommand, so re-read it a moment later
        // instead of showing a stale "not running" to the user.
        lifecycleScope.launch {
            delay(SERVICE_START_GRACE_MS)
            refreshSetup()
        }
    }

    /**
     * Asks the system recognizer to fetch the offline pack. Where that API is missing or
     * refuses, the voice-input settings are the next best place to do it by hand.
     */
    private fun installLanguage(languageTag: String?) {
        if (AndroidOnDeviceEngine.requestLanguageDownload(this, languageTag)) {
            Toast.makeText(this, R.string.language_download_started, Toast.LENGTH_LONG).show()
            return
        }
        val opened = runCatching {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }.isSuccess
        if (!opened) Setup.openAppSettings(this)
        Toast.makeText(this, R.string.language_download_by_hand, Toast.LENGTH_LONG).show()
    }

    private fun refreshSetup() {
        val state = Setup.snapshot(this, accessibilityTried)
        // On is on: the next time it is off, the trip starts over.
        if (state.accessibilityEnabled) accessibilityTried = false
        setupState.value = state
    }

    /** An app's page asked for in [intent] (the quick menu's "App page"), if it names an app there is. */
    private fun takeAppPage(intent: Intent?) {
        val asked = runCatching { intent?.getStringExtra(EXTRA_APP_PAGE) }.getOrNull() ?: return
        Launcher.installed(this, asked)?.let { appPageRequest.value = it }
    }

    companion object {
        private const val SERVICE_START_GRACE_MS = 600L
        private const val KEY_ACCESSIBILITY_TRIED = "accessibilityTried"

        /** Behind a three-button navigation bar, as androidx.activity's own defaults. */
        private val NAV_SCRIM_LIGHT = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        private val NAV_SCRIM_DARK = Color.argb(0x80, 0x1B, 0x1B, 0x1B)

        /**
         * The package whose page to open, from the quick menu. The activity is exported: only an
         * installed app that already has a page is taken, and nothing is made for it here.
         */
        private const val EXTRA_APP_PAGE = "app.amethyst.extra.APP_PAGE"

        /**
         * Amber brought to the front from the accessibility service - on its main screen, or on
         * [appPage]'s page with Settings and the app styles behind it for Back.
         */
        fun intent(context: Context, appPage: String? = null): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .apply { if (appPage != null) putExtra(EXTRA_APP_PAGE, appPage) }
    }
}

/** Callbacks that need an Activity, passed down so the screens stay free of Android plumbing. */
class SetupActions(
    val requestMicrophone: () -> Unit,
    val requestNotifications: () -> Unit,
    val openAccessibility: () -> Unit,
    /** The accessibility list, with no return trip: to switch another Amber off. */
    val openAccessibilityList: () -> Unit,
    val openAppInfo: () -> Unit,
    val openBattery: () -> Unit,
    val startMicService: () -> Unit,
)

private sealed interface Screen {
    /** The introduction; its step lives beside the stack, see [AmethystScreens]. */
    data object Onboarding : Screen
    data object Home : Screen
    data object Settings : Screen
    data class Page(val page: SettingsPage) : Screen
    data class EngineDetail(val engineId: String) : Screen
    data class AppStyle(val packageName: String) : Screen
}

@Composable
private fun Screen.title(): String = when (this) {
    Screen.Onboarding -> ""
    Screen.Home -> "Amber" // English: the app's name
    Screen.Settings -> stringResource(R.string.title_settings)
    is Screen.EngineDetail -> stringResource(R.string.settings_recognition)
    is Screen.AppStyle -> stringResource(R.string.title_app_style)
    is Screen.Page -> stringResource(
        when (page) {
            SettingsPage.RECOGNITION -> R.string.settings_recognition
            SettingsPage.CLEANUP -> R.string.settings_cleanup
            SettingsPage.FALLBACK -> R.string.settings_fallback
            SettingsPage.RECORDING -> R.string.settings_recording
            SettingsPage.INSERTING -> R.string.settings_inserting
            SettingsPage.APP_STYLES -> R.string.settings_app_styles
            SettingsPage.BUBBLE -> R.string.settings_bubble
            SettingsPage.QUICK_MENU -> R.string.settings_quick_menu
            SettingsPage.COMMANDS -> R.string.settings_voice_commands
            SettingsPage.STATS -> R.string.settings_statistics
            SettingsPage.RELIABILITY -> R.string.settings_reliability
            SettingsPage.DIAGNOSTICS -> R.string.settings_diagnostics
            SettingsPage.UPDATES -> R.string.settings_updates
            SettingsPage.LICENSES -> R.string.settings_licenses
        },
    )
}

@Composable
private fun AmethystRoot(
    graph: Graph,
    setup: SetupState,
    actions: SetupActions,
    appPage: String?,
    onAppPageOpened: () -> Unit,
    onInstallLanguage: (String?) -> Unit,
) {
    // Nothing but the background until the stored settings are read: the defaults would start
    // everyone - not only a fresh install - in the introduction for a moment.
    val loaded = graph.loadedSettings.collectAsStateWithLifecycle().value
    if (loaded == null) {
        AmberBackground {}
        return
    }
    AmethystScreens(graph, setup, actions, appPage, onAppPageOpened, onInstallLanguage, introduce = !loaded.onboardingDone)
}

@Composable
private fun AmethystScreens(
    graph: Graph,
    setup: SetupState,
    actions: SetupActions,
    /** An app whose page the quick menu asked for; [onAppPageOpened] once it is handled. */
    appPage: String?,
    onAppPageOpened: () -> Unit,
    onInstallLanguage: (String?) -> Unit,
    introduce: Boolean,
) {
    // A back stack: Home → Settings → a page → an engine, and back the same way. A fresh
    // install starts in the introduction instead of Home.
    var stack by remember { mutableStateOf(listOf(if (introduce) Screen.Onboarding else Screen.Home)) }
    val screen = stack.last()

    // The quick menu's "App page": that page, with the way back through the app styles and
    // Settings. Never into the introduction - it is finished first. Only to a page there is:
    // the quick menu makes the app's profile as it opens Amber, so it may land a moment later;
    // anyone else asking gets Amber as it is, and nothing is made for them.
    LaunchedEffect(appPage) {
        if (appPage == null) return@LaunchedEffect
        val hasPage = withTimeoutOrNull(APP_PAGE_WAIT_MS) {
            graph.settings.first { AppProfiles.forPackage(it.profiles, appPage) != null }
        } != null
        if (hasPage && Screen.Onboarding !in stack) {
            stack = listOf(Screen.Home, Screen.Settings, Screen.Page(SettingsPage.APP_STYLES), Screen.AppStyle(appPage))
        }
        onAppPageOpened()
    }
    // Saved, so a trip to Android's settings that recreates the activity comes back to it.
    var step by rememberSaveable { mutableStateOf(OnboardingStep.WELCOME) }
    val testContext = LocalContext.current
    val tester = remember(graph, testContext) { ConnectionTester(graph, testContext) }
    val settings by graph.settings.collectAsStateWithLifecycle()
    val statList by graph.stats.all.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Follows the clock, so an app left open overnight starts the new day at zero: once at
    // midnight, and with every new dictation (which may be the first of a new day).
    val today by produceState(graph.stats.dayOf(System.currentTimeMillis()), statList) {
        while (true) {
            val now = System.currentTimeMillis()
            value = graph.stats.dayOf(now)
            delay(graph.stats.millisUntilNextDay(now))
        }
    }
    val stats = remember(statList, settings.stats.typingWpm, today) {
        StatsSummary.of(statList, today, settings.stats.typingWpm)
    }
    // Read so the line under the flame follows the download; forEngine reads their current values.
    // The stores read every model file when they are built, so only for a local engine or the
    // introduction - not on every start for everyone.
    val watchModels = screen == Screen.Onboarding || screen is Screen.EngineDetail ||
        settings.activeEngine?.let(ModelDownloads::downloadsModel) == true
    val parakeetState = if (watchModels) graph.parakeetModels.state.collectAsStateWithLifecycle().value else ParakeetState.Missing
    val whisperStates = if (watchModels) graph.whisperModels.states.collectAsStateWithLifecycle().value else emptyMap()
    val waitingFor = if (watchModels) graph.modelDownloads.waiting.collectAsStateWithLifecycle().value else null
    val download = if (watchModels) {
        remember(settings.activeEngine, parakeetState, whisperStates, waitingFor) {
            settings.activeEngine?.let(graph.modelDownloads::forEngine)
        }
    } else {
        null
    }
    val apps = remember(context) { AndroidAppSource(context) }
    val appUsage = remember(statList) { AppChoice.usage(statList) }

    fun updateSettings(transform: (AmethystSettings) -> AmethystSettings) {
        scope.launch { graph.settingsStore.update(transform) }
    }

    // Which build this is and its version, read on the IO pool at the start; null for the moment
    // before. The GitHub build alone checks for updates.
    val build by graph.updates.build.collectAsStateWithLifecycle()

    /** A release page, in the browser. */
    fun openUrl(url: String) {
        val opened = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!opened) Toast.makeText(context, context.getString(R.string.no_browser, url), Toast.LENGTH_LONG).show()
    }

    fun open(next: Screen) {
        stack = stack + next
    }

    // What the cleanup's service list needs, wherever it shows: Settings → Cleanup, the
    // introduction's step, one app's own model.
    fun cleanupAccess(onOpenEngine: ((String) -> Unit)?) = CleanupAccess(
        hasKeyFor = { alias -> graph.secretStore.has(alias) },
        onSaveKey = { alias, key -> graph.secretStore.put(alias, key) },
        onUpdateSettings = ::updateSettings,
        fetchModels = { baseUrl, key ->
            withContext(Dispatchers.IO) { ServerModels.fetch(graph.httpTransport, baseUrl, key) }
        },
        fetchModelsOf = { engine ->
            withContext(Dispatchers.IO) {
                // Only to the host the key was given for.
                val key = graph.secretStore.get(engine.apiKeyAlias)?.takeIf { KeyBinding.allows(engine) }
                ServerModels.fetch(graph.httpTransport, engine.baseUrl.orEmpty(), key)
            }
        },
        testKey = { engine, model, key -> tester.testCleanup(engine, model, key) },
        onOpenEngine = onOpenEngine,
    )
    // Built once: the pages remember their rows by it.
    val cleanupAccess = remember(graph, tester) { cleanupAccess { engineId -> open(Screen.EngineDetail(engineId)) } }
    val introductionCleanup = remember(graph, tester) { cleanupAccess(onOpenEngine = null) }

    // Only for the introduction, and off the main thread: it loads the Whisper library to ask
    // about the CPU. Read once - memory and the CPU do not change.
    val phone by produceState<PhoneFacts?>(null, screen == Screen.Onboarding) {
        if (screen == Screen.Onboarding && value == null) value = withContext(Dispatchers.IO) { Setup.phoneFacts(context) }
    }

    /** The introduction's engine choice: added if missing, with its model, and made active. */
    fun useEngine(option: EngineOption) {
        val preset = EnginePresets.byId(option.presetId) ?: return
        updateSettings { current ->
            val base = current.engines.firstOrNull { it.id == preset.id }
                ?: EngineConfig.fromPreset(preset, languageTag = EngineConfig.defaultLanguageTag(preset.kind, PhoneLocales.tags()))
            val config = option.whisperModelId?.let { base.copy(modelId = it) } ?: base
            current.withEngine(config).copy(activeEngineId = config.id)
        }
    }

    fun back() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    BackHandler(enabled = stack.size > 1) { back() }
    BackHandler(enabled = screen == Screen.Onboarding && Onboarding.previous(step, settings) != null) {
        Onboarding.previous(step, settings)?.let { step = it }
    }

    AmberBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // The introduction draws its own line: where you are, Back and Skip.
            if (screen != Screen.Onboarding) {
                TopBar(
                    title = screen.title(),
                    showBack = screen != Screen.Home,
                    showSettings = screen == Screen.Home,
                    onBack = ::back,
                    onSettings = { open(Screen.Settings) },
                )
            }

            AnimatedContent(
                targetState = screen,
                // A plain cross-fade; screens do not slide or bounce.
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
                label = "screen",
                modifier = Modifier.fillMaxSize(),
            ) { current ->
                when (current) {
                    Screen.Onboarding -> {
                        val records by graph.dictationLog.records.collectAsStateWithLifecycle()
                        OnboardingScreen(
                            step = step,
                            settings = settings,
                            setup = setup,
                            actions = actions,
                            content = OnboardingContent(
                                phone = phone,
                                metered = graph.modelDownloads.metered(),
                                parakeet = parakeetState,
                                whisper = whisperStates,
                                waitingFor = waitingFor,
                                onChooseEngine = { option, mobileData ->
                                    useEngine(option)
                                    graph.modelDownloads.start(option, mobileData)
                                },
                                onWaitForWifi = { option ->
                                    useEngine(option)
                                    graph.modelDownloads.waitForWifi(option)
                                },
                                onOpenRecognition = { open(Screen.Page(SettingsPage.RECOGNITION)) },
                                download = download,
                                apps = apps,
                                onUpdateSettings = ::updateSettings,
                                bubbleEvents = graph.bubbleEvents,
                                hasLastDictation = DictationLog.lastDictation(records) != null,
                                // No way from the introduction to a server's own page.
                                cleanup = introductionCleanup,
                            ),
                            onStep = { step = it },
                            onFinish = {
                                if (!settings.onboardingDone) updateSettings { it.copy(onboardingDone = true) }
                                stack = listOf(Screen.Home)
                            },
                        )
                    }

                    Screen.Home -> HomeScreen(
                        setup = setup,
                        actions = actions,
                        settings = settings,
                        dictationState = graph.dictation.state,
                        stats = stats,
                        download = download,
                        onStartTest = { graph.dictation.start(TriggerSource.IN_APP) },
                        onStopTest = { graph.dictation.stop() },
                        onOpenRecognition = { open(Screen.Page(SettingsPage.RECOGNITION)) },
                        onOpenCleanup = { open(Screen.Page(SettingsPage.CLEANUP)) },
                        onOpenStats = { open(Screen.Page(SettingsPage.STATS)) },
                        onOpenActiveEngine = { open(Screen.EngineDetail(settings.activeEngineId)) },
                        onInstallLanguage = { onInstallLanguage(settings.activeEngine?.let(LanguageList::forDictation)) },
                        onUseLocalWhisper = {
                            val preset = EnginePresets.LOCAL_WHISPER
                            if (settings.engines.none { it.id == preset.id }) {
                                updateSettings {
                                    it.withEngine(EngineConfig.fromPreset(preset, languageTag = EngineConfig.defaultLanguageTag(preset.kind, PhoneLocales.tags())))
                                }
                            }
                            open(Screen.EngineDetail(preset.id))
                        },
                        update = build?.let { UpdatePolicy.forHome(it.channel, settings.updates, it.version) },
                        onDownloadUpdate = { notice -> openUrl(notice.url) },
                        onDismissUpdate = { notice ->
                            updateSettings { it.copy(updates = it.updates.copy(dismissedVersion = notice.version)) }
                        },
                    )

                    Screen.Settings -> {
                        val records by graph.dictationLog.records.collectAsStateWithLifecycle()
                        SettingsScreen(
                            settings = settings,
                            setup = setup,
                            diagnosticsCount = records.size,
                            version = build?.version.orEmpty(),
                            releaseChannel = build?.channel,
                            onAdvancedChange = { on -> updateSettings { it.copy(ui = it.ui.copy(advanced = on)) } },
                            onThemeChange = { theme -> updateSettings { it.copy(ui = it.ui.copy(theme = theme)) } },
                            onOpen = { page -> open(Screen.Page(page)) },
                            onReplayIntroduction = {
                                // On top of Settings, the flag left alone: Back on the first step
                                // returns here, and leaving halfway changes nothing.
                                step = OnboardingStep.WELCOME
                                open(Screen.Onboarding)
                            },
                        )
                    }

                    is Screen.Page -> {
                        // The pages that show model lists ask Amber PCs and Servers for theirs.
                        if (current.page == SettingsPage.RECOGNITION || current.page == SettingsPage.CLEANUP) {
                            LaunchedEffect(current.page) { graph.amberCatalogs.refreshAll() }
                        }
                        when (current.page) {
                            SettingsPage.RECOGNITION -> RecognitionScreen(
                                settings = settings,
                                hasKeyFor = { alias -> graph.secretStore.has(alias) },
                                medianWaitMs = stats.medianWaitMs,
                                onUpdateSettings = ::updateSettings,
                                onOpenEngine = { engineId -> open(Screen.EngineDetail(engineId)) },
                            )
                            SettingsPage.CLEANUP -> CleanupScreen(settings = settings, access = cleanupAccess)
                            SettingsPage.FALLBACK -> FallbackScreen(settings, ::updateSettings)
                            SettingsPage.RECORDING -> RecordingScreen(settings, ::updateSettings)
                            SettingsPage.INSERTING -> InsertingScreen(settings, ::updateSettings)
                            SettingsPage.APP_STYLES -> AppStylesScreen(
                                settings, ::updateSettings,
                                apps = apps,
                                usage = appUsage,
                                onOpenApp = { pkg -> open(Screen.AppStyle(pkg)) },
                            )
                            SettingsPage.BUBBLE -> BubbleScreen(
                                settings, ::updateSettings,
                                apps = apps,
                                usage = appUsage,
                                onOpenQuickMenu = { open(Screen.Page(SettingsPage.QUICK_MENU)) },
                            )
                            SettingsPage.QUICK_MENU -> QuickMenuScreen(settings, ::updateSettings)
                            SettingsPage.COMMANDS -> VoiceCommandsScreen(settings, ::updateSettings)
                            SettingsPage.STATS -> StatsScreen(
                                settings = settings,
                                stats = stats,
                                today = today,
                                engineName = { id -> settings.engines.firstOrNull { it.id == id }?.let { Texts.engineName(context, it) } ?: id },
                                appName = apps::label,
                                onUpdateSettings = ::updateSettings,
                                onClear = { graph.stats.clear() },
                            )
                            SettingsPage.RELIABILITY -> ReliabilityScreen(setup, actions)
                            SettingsPage.LICENSES -> LicensesScreen()
                            SettingsPage.UPDATES -> {
                                val checking by graph.updates.checking.collectAsStateWithLifecycle()
                                // "3 hours ago" moves on while the page is open.
                                val now by produceState(System.currentTimeMillis()) {
                                    while (true) {
                                        delay(UPDATES_CLOCK_MS)
                                        value = System.currentTimeMillis()
                                    }
                                }
                                build?.let { info ->
                                    UpdatesScreen(
                                        settings = settings,
                                        channel = info.channel,
                                        installedVersion = info.version,
                                        checking = checking,
                                        now = now,
                                        onUpdateSettings = ::updateSettings,
                                        onCheckNow = { graph.updates.checkNow() },
                                        onOpenUrl = ::openUrl,
                                    )
                                }
                            }
                            SettingsPage.DIAGNOSTICS -> {
                                val records by graph.dictationLog.records.collectAsStateWithLifecycle()
                                val keyboardLog by graph.keyboardLog.collectAsStateWithLifecycle()
                                val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
                                DiagnosticsScreen(
                                    records = records,
                                    keyboardLog = keyboardLog,
                                    otherAmbers = setup.otherAmbers,
                                    onOpenAccessibility = actions.openAccessibilityList,
                                    onClear = {
                                        graph.dictationLog.clear()
                                        graph.keyboardLog.value = emptyList()
                                    },
                                    appTitle = "Amber $version".trim(), // English: the report's heading
                                )
                            }
                        }
                    }

                    is Screen.AppStyle -> {
                        val profile = settings.profiles.firstOrNull { it.packageName == current.packageName }
                        if (profile == null) {
                            // Just added from the picker and not stored yet, or removed: as for engines.
                            LaunchedEffect(current.packageName) {
                                delay(ENGINE_APPEAR_GRACE_MS)
                                if (graph.settings.value.profiles.none { it.packageName == current.packageName }) back()
                            }
                        } else {
                            // Its cleanup picker lists what Amber PCs and Servers offer.
                            LaunchedEffect(current.packageName) { graph.amberCatalogs.refreshAll() }
                            AppStyleScreen(
                                profile = profile,
                                settings = settings,
                                apps = apps,
                                access = cleanupAccess,
                                modelReady = graph::modelReady,
                                onUpdateSettings = ::updateSettings,
                                onRemoved = ::back,
                            )
                        }
                    }

                    is Screen.EngineDetail -> {
                        val tinfoilLog by graph.tinfoilLog.collectAsStateWithLifecycle()
                        val config = settings.engines.firstOrNull { it.id == current.engineId }
                        if (config == null) {
                            // Not in the settings yet (just added) or deleted from under us;
                            // decide in an effect, never during composition.
                            LaunchedEffect(current.engineId) {
                                delay(ENGINE_APPEAR_GRACE_MS)
                                if (graph.settings.value.engines.none { it.id == current.engineId }) back()
                            }
                        } else {
                            LaunchedEffect(config.id) { graph.amberCatalogs.refresh(config) }
                            EngineDetailScreen(
                                config = config,
                                catalog = settings.catalogFor(config),
                                active = config.id == settings.activeEngineId,
                                // Read once per engine, not on every recomposition: this is a
                                // decrypt out of EncryptedSharedPreferences.
                                storedKey = remember(config.apiKeyAlias) {
                                    graph.secretStore.get(config.apiKeyAlias)
                                },
                                whisperModels = whisperStates,
                                onSaveKey = { alias, value ->
                                    if (value.isBlank()) graph.secretStore.clear(alias)
                                    else graph.secretStore.put(alias, value)
                                },
                                onSave = { updated: EngineConfig ->
                                    scope.launch {
                                        graph.settingsStore.update { it.withEngine(updated) }
                                        // Once stored, so the answer finds the engine at its new
                                        // address. A new address is asked at once, the same one
                                        // once a minute.
                                        graph.amberCatalogs.refresh(updated)
                                    }
                                },
                                onActivate = { updateSettings { it.copy(activeEngineId = config.id) } },
                                cleanupOnly = Servers.cleanupOnly(settings, config),
                                onDelete = {
                                    updateSettings { current ->
                                        val remaining = current.engines.filterNot { it.id == config.id }
                                        current.copy(
                                            engines = remaining,
                                            serverCatalogs = current.serverCatalogs - config.id,
                                            // Never Claude: it recognizes no speech.
                                            activeEngineId = if (current.activeEngineId == config.id) {
                                                Servers.nextActive(remaining)
                                            } else {
                                                current.activeEngineId
                                            },
                                        )
                                    }
                                    back()
                                },
                                onTest = { candidate ->
                                    tester.test(candidate).also { result ->
                                        if (result is ConnectionTester.Result.Ok) graph.amberCatalogs.refresh(candidate, force = true)
                                    }
                                },
                                onDownloadModel = { spec -> graph.whisperModels.download(spec) },
                                onImportModel = { uri -> graph.whisperModels.import(uri) },
                                onCancelModel = { id -> graph.whisperModels.cancel(id) },
                                onDeleteModel = { id -> graph.whisperModels.delete(id) },
                                enclaveLog = tinfoilLog,
                                onVerifyEnclave = { url -> graph.tinfoilVerifier.verify(url, forceFresh = true) },
                                parakeetState = parakeetState,
                                onDownloadParakeet = { graph.parakeetModels.download() },
                                onCancelParakeet = { graph.parakeetModels.cancel() },
                                onDeleteParakeet = { graph.parakeetModels.delete() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TopBar(
    title: String,
    showBack: Boolean,
    showSettings: Boolean,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showBack) IconAction(onClick = onBack, contentDescription = stringResource(R.string.back), glyph = BackGlyph)
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = AmberPalette.Text,
            modifier = Modifier.weight(1f),
        )
        if (showSettings) IconAction(onClick = onSettings, contentDescription = stringResource(R.string.title_settings), glyph = GearGlyph)
    }
}

private const val ENGINE_APPEAR_GRACE_MS = 500L

/** How long an app page asked for by the quick menu may take to appear in the settings. */
private const val APP_PAGE_WAIT_MS = 2_000L

private const val UPDATES_CLOCK_MS = 30_000L

