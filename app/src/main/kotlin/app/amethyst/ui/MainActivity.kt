package app.amethyst.ui

import android.Manifest
import android.animation.ValueAnimator
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.amethyst.AmethystApp
import app.amethyst.Graph
import app.amethyst.R
import app.amethyst.SecureWindow
import app.amethyst.Texts
import app.amethyst.apps.Launcher
import app.amethyst.core.apps.AppChoice
import app.amethyst.core.capture.TriggerSource
import app.amethyst.core.dictation.DictationLog
import app.amethyst.core.engine.EngineOption
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.PhoneFacts
import app.amethyst.core.engine.KeyBinding
import app.amethyst.core.engine.ServerModels
import app.amethyst.core.engine.Servers
import app.amethyst.core.onboarding.FirstRun
import app.amethyst.core.onboarding.MicrophoneAsk
import app.amethyst.core.onboarding.MicrophoneWay
import app.amethyst.core.onboarding.RealAppNudges
import app.amethyst.core.onboarding.Onboarding
import app.amethyst.core.onboarding.OnboardingPath
import app.amethyst.core.onboarding.OnboardingStep
import app.amethyst.core.onboarding.SettingsTrip
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppProfile
import app.amethyst.core.history.HistoryEntry
import app.amethyst.core.history.HistoryKeep
import app.amethyst.core.history.HistoryOffer
import app.amethyst.core.privacy.SecurityMode
import app.amethyst.data.OwnClipboard
import app.amethyst.core.settings.AppProfiles
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.LanguageList
import app.amethyst.core.settings.ThemeMode
import app.amethyst.core.ui.Navigation
import app.amethyst.core.ui.Screen
import app.amethyst.core.ui.SettingsPage
import app.amethyst.core.update.UpdatePolicy
import app.amethyst.data.PhoneLocales
import app.amethyst.engine.AndroidOnDeviceEngine
import app.amethyst.engine.FirstRunSetup
import app.amethyst.engine.ModelDownloads
import app.amethyst.engine.ParakeetState
import app.amethyst.ui.AndroidAppSource
import app.amethyst.ui.onboarding.OnboardingContent
import app.amethyst.ui.onboarding.OnboardingScreen
import app.amethyst.ui.screen.AboutScreen
import app.amethyst.ui.screen.AppStyleScreen
import app.amethyst.ui.screen.AppStylesScreen
import app.amethyst.ui.screen.BubbleScreen
import app.amethyst.ui.screen.CleanupAccess
import app.amethyst.ui.screen.CleanupScreen
import app.amethyst.ui.screen.DiagnosticsScreen
import app.amethyst.ui.screen.EngineDetailScreen
import app.amethyst.ui.screen.HomeScreen
import app.amethyst.ui.screen.LicensesScreen
import app.amethyst.ui.screen.PrivacyPolicyScreen
import app.amethyst.ui.screen.QuickMenuScreen
import app.amethyst.ui.screen.RecognitionScreen
import app.amethyst.ui.screen.FallbackScreen
import app.amethyst.ui.screen.HistoryScreen
import app.amethyst.ui.screen.OwnWordsScreen
import app.amethyst.ui.screen.RecordingScreen
import app.amethyst.ui.screen.SecurityModeScreen
import app.amethyst.ui.screen.ReliabilityScreen
import app.amethyst.ui.screen.SettingsScreen
import app.amethyst.ui.screen.StatsScreen
import app.amethyst.ui.screen.PrivacyScreen
import app.amethyst.ui.screen.TextScreen
import app.amethyst.ui.screen.UpdatesScreen
import app.amethyst.ui.screen.VoiceCommandsScreen
import app.amethyst.ui.screen.title
import app.amethyst.ui.theme.AmberColors
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
            // Remembered so the next "Allow" knows whether Android still asks (MicrophoneAsk).
            getSharedPreferences(MIC_PREFS, MODE_PRIVATE).edit().putBoolean(KEY_MIC_REFUSED, MicrophoneAsk.refused(granted)).apply()
            refreshSetup()
        }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshSetup() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // From Android 13 no Recents picture at all. Before, and for screenshots, the window is
        // hidden while a screen shows dictated words (ShowsDictatedText) or the
        // security mode is on; elsewhere screenshots stay possible - they are how a problem gets
        // reported.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        SecureWindow.follow(this)
        // The background runs behind the status and navigation bars; their icons follow the
        // theme once it is known (applyTheme), the phone's until then.
        enableEdgeToEdge()
        val graph = AmethystApp.graph(this)
        // Keystore and Tink on the IO pool now, not on the main thread when a page asks for a key.
        graph.warmSecretStore()
        accessibilityTried = savedInstanceState?.getBoolean(KEY_ACCESSIBILITY_TRIED) ?: false
        // Here on the main thread, once: the first frame shows what is set up, not a moment of
        // everything missing. Every later look is off it (refreshSetup).
        takeSetup(Setup.snapshot(this, accessibilityTried))
        // Recreated (a rotation): the page asked for was opened then.
        if (savedInstanceState == null) takeAppPage(intent)

        // The settings are read a moment after the first frame. Until then the choice made
        // last time stands in - without it a light Amber on a dark phone began dark. Before
        // Android 12 this is all there is: Android's own picture of the starting app still
        // follows the phone there.
        val remembered = rememberedTheme()
        if (remembered != null) {
            val phoneDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            window.setBackgroundDrawable(ColorDrawable(AmberColors.of(remembered.dark(phoneDark)).background.toArgb()))
        }

        setContent {
            // Null until the settings are read: the choice made last time, else as the phone
            // is set.
            val theme = graph.loadedSettings.collectAsStateWithLifecycle().value?.ui?.theme ?: remembered
            val dark = (theme ?: ThemeMode.SYSTEM).dark(isSystemInDarkTheme())
            LaunchedEffect(theme, dark) { applyTheme(theme, dark) }
            // Amber's own fields tell the accessibility service of their focus (reportsOwnField).
            AmberTheme(dark) {
                CompositionLocalProvider(LocalOwnField provides graph.ownFieldFocused) {
                    AmethystRoot(
                        graph = graph,
                        appPage = appPageRequest.collectAsStateWithLifecycle().value,
                        onAppPageOpened = { appPageRequest.value = null },
                        onInstallLanguage = { tag -> installLanguage(tag) },
                        setup = setupState.collectAsStateWithLifecycle().value,
                        actions = SetupActions(
                            requestMicrophone = ::askForMicrophone,
                            requestNotifications = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            openAccessibility = ::openAccessibility,
                            openAccessibilityList = ::openAccessibilityList,
                            openAppInfo = { Setup.openAppSettings(this) },
                            openBattery = ::openBattery,
                        ),
                    )
                }
            }
        }
    }

    /**
     * What of the theme lies outside the screens: the status and navigation bar icons, dark on
     * a light page, and the window behind the page. From Android 12 the choice is also given to
     * Android ([theme], null while not read yet), which then starts Amber in it - no flash of
     * the other theme at the next launch. The choice is also kept where the next launch can
     * read it at once ([rememberedTheme]).
     */
    private fun applyTheme(theme: ThemeMode?, dark: Boolean) {
        if (theme != null && theme != rememberedTheme()) {
            getSharedPreferences(THEME_PREFS, MODE_PRIVATE).edit().putString(KEY_THEME, theme.name).apply()
        }
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

    /** The theme chosen when Amber last ran, readable before the settings are; null on a first start. */
    private fun rememberedTheme(): ThemeMode? =
        getSharedPreferences(THEME_PREFS, MODE_PRIVATE).getString(KEY_THEME, null)
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }

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

    /**
     * "Allow" for the microphone, wherever it is: Android's question while Android still asks;
     * after a second refusal it answers no without a word, so Amber's app info opens instead,
     * where the microphone can still be granted.
     */
    private fun askForMicrophone() {
        val refused = getSharedPreferences(MIC_PREFS, MODE_PRIVATE).getBoolean(KEY_MIC_REFUSED, false)
        when (MicrophoneAsk.way(refused, shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO))) {
            MicrophoneWay.ASK -> requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
            MicrophoneWay.APP_INFO -> Setup.openAppSettings(this)
        }
    }

    /** To the battery list, watched while the user is there; back to Amber once Amber is exempt. */
    private fun openBattery() {
        val back = AmethystApp.graph(this).returnToAmber
        back.sending(SettingsTrip.BATTERY)
        if (!Setup.openBatterySettings(this)) back.cancel()
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

    private var setupRefresh: Job? = null

    /**
     * What is set up, looked at again on the IO pool: the snapshot asks the accessibility
     * settings, the notification, power and package managers - binder calls each, on every
     * return to Amber. The newest look wins.
     */
    private fun refreshSetup() {
        val tried = accessibilityTried
        setupRefresh?.cancel()
        setupRefresh = lifecycleScope.launch {
            takeSetup(withContext(Dispatchers.IO) { Setup.snapshot(this@MainActivity, tried) })
        }
    }

    private fun takeSetup(state: SetupState) {
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
        private const val KEY_ACCESSIBILITY_TRIED = "accessibilityTried"
        private const val THEME_PREFS = "theme"
        private const val KEY_THEME = "theme"
        private const val MIC_PREFS = "microphone"
        private const val KEY_MIC_REFUSED = "refused"

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
)

/**
 * The title over a screen: the name on the row that led there - an app's page is titled with
 * the app, a speech recognition's with its name.
 */
@Composable
private fun Screen.title(settings: AmethystSettings, apps: AppSource): String = when (this) {
    Screen.Onboarding -> ""
    Screen.Home -> APP_NAME
    Screen.Settings -> stringResource(R.string.title_settings)
    is Screen.EngineDetail -> settings.engines.firstOrNull { it.id == engineId }?.let { recognitionRowName(it) }
        ?: stringResource(R.string.settings_recognition)
    is Screen.AppStyle -> AppProfiles.forPackage(settings.profiles, packageName)?.label
        ?: remember(packageName) { apps.label(packageName) }
    is Screen.Page -> stringResource(page.title())
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
    // A back stack: Home → Settings → a page → an engine, and back the same way (Navigation).
    // Saved, so a rotation, split screen, a new font size or Android ending the app in the
    // background comes back to the same screen instead of Home.
    var stack by rememberSaveable(stateSaver = stackSaver(introduce)) { mutableStateOf(Navigation.start(introduce)) }
    val screen = stack.last()
    // Each place in the stack keeps its own state while a screen above covers it - where a page
    // was scrolled to, what was typed - and gives it up once it is left (Navigation.keys).
    val entries = rememberSaveableStateHolder()
    // The places on screen now: a page taken off the stack stays while it slides out. Its state
    // goes only once it has gone (below, as it leaves the composition) - given up earlier, the
    // same page opened again while still sliding out would come back unable to keep its state.
    val shownKeys = remember { mutableSetOf<String>() }
    var keptStack by remember { mutableStateOf(stack) }
    LaunchedEffect(stack) {
        Navigation.forgotten(keptStack, stack).filterNot(shownKeys::contains).forEach(entries::removeState)
        keptStack = stack
    }

    // The quick menu's "App page": that page, with the way back through the app styles and
    // Settings. Never into the introduction - it is finished first. Only to a page there is:
    // the quick menu makes the app's profile as it opens Amber, so it may land a moment later;
    // anyone else asking gets Amber as it is, and nothing is made for them.
    LaunchedEffect(appPage) {
        if (appPage == null) return@LaunchedEffect
        val hasPage = withTimeoutOrNull(APP_PAGE_WAIT_MS) {
            graph.settings.first { AppProfiles.forPackage(it.profiles, appPage) != null }
        } != null
        if (hasPage) stack = Navigation.toAppPage(stack, appPage)
        onAppPageOpened()
    }
    // Saved, so a trip to Android's settings that recreates the activity comes back to it.
    var step by rememberSaveable { mutableStateOf(OnboardingStep.WELCOME) }
    // The way through it, chosen on the welcome step.
    var path by rememberSaveable { mutableStateOf(OnboardingPath.DEEP) }
    val testContext = LocalContext.current
    val tester = remember(graph, testContext) { ConnectionTester(graph, testContext) }
    val settings by graph.settings.collectAsStateWithLifecycle()
    // The screens with keys ask the secret store whether one is stored; they wait the moment it
    // may still be building after a cold start rather than build it on the main thread. Home and
    // the introduction show at once - a fresh install starts there, when building the store
    // takes longest - and only the introduction's cleanup step waits.
    val secretsReady by graph.secretStoreReady.collectAsStateWithLifecycle()
    val statList by graph.stats.all.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The loops below run while Amber's screen is started, not behind another app.
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Summed up off the main thread, following the clock ([Graph.statsSummary]).
    val statsView by graph.statsSummary.collectAsStateWithLifecycle()
    val stats = statsView.summary
    val today = statsView.today
    // Read so the line under the flame follows the download; forEngine reads their current values.
    // The stores read every model file when they are built, so only for a local engine or the
    // introduction - not on every start for everyone. And while the first run still fetches a
    // model for this phone: the small one that stands in, or the better one after it.
    val pendingOption = FirstRun.wanted(settings)
    val watchModels = screen == Screen.Onboarding || screen is Screen.EngineDetail || pendingOption != null ||
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
    val pendingStage = FirstRun.stage(settings)
    val pending = pendingOption?.takeIf { pendingStage != null }?.let { option ->
        val (pendingDownload, failed) = remember(option, parakeetState, whisperStates, waitingFor) {
            graph.modelDownloads.forOption(option) to graph.modelDownloads.failed(option)
        }
        PendingModel(option, pendingDownload, failed, pendingStage!!, takesOver = FirstRun.pending(settings)) { mobileData -> graph.firstRun.download(mobileData) }
    }
    val apps = remember(context) { AndroidAppSource(context) }
    val appUsage = remember(statList) { AppChoice.usage(statList) }
    // The apps on the phone, read once off the main thread, for the line that names one of them.
    val installedApps by produceState<Map<String, String>?>(null, apps) {
        value = runCatching { apps.all() }.getOrDefault(emptyList()).associate { it.packageName to it.label }
    }
    val nudge = RealAppNudges.of(RealAppNudges.reached(settings.firstRun.realAppReached, statList), setup.ready && settings.bubble.enabled, installedApps)
    // Home's "Last dictation" while it is kept: looked at again with every dictation, every
    // change of what is kept, and every half minute - so it goes when its ten minutes are up.
    val cleanupTrouble by graph.cleanupTrouble.open.collectAsStateWithLifecycle()
    val records by graph.memory.log.records.collectAsStateWithLifecycle()
    val keptChanges by graph.memory.kept.changes.collectAsStateWithLifecycle()
    // In the security mode nothing is kept, so Home offers no "Try again" there.
    val canTryAgainHome = remember(keptChanges) { graph.memory.kept.hasRecording }
    val lastDictation by produceState<String?>(null, records, keptChanges, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = graph.memory.kept.lastDictation(records)?.text
                delay(LAST_DICTATION_RECHECK_MS)
            }
        }
    }

    // How many dictations the history keeps, for Home's row: counted without opening any, again
    // with every change and every minute, as the oldest run out of time.
    val historyKeep = SecurityMode.historyKeep(settings)
    val historyChanges by graph.historyChanges.collectAsStateWithLifecycle()
    val historyCount by produceState<Int?>(null, historyKeep, historyChanges, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = if (historyKeep == HistoryKeep.OFF) null else withContext(Dispatchers.IO) { graph.history.count(historyKeep) }
                delay(HISTORY_RECOUNT_MS)
            }
        }
    }

    // Undo for a change one tap made: the message outlives the page that made it (an app's page
    // closes with "Remove"), so it lives here, under every screen.
    val snackbars = remember { SnackbarHostState() }
    val undoScope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.undo)
    val undo = remember(snackbars, undoScope, undoLabel) {
        UndoOffer { message, onUndo ->
            undoScope.launch {
                // A newer change replaces the older one's message instead of queueing behind it.
                snackbars.currentSnackbarData?.dismiss()
                if (snackbars.showSnackbar(message, actionLabel = undoLabel, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) onUndo()
            }
        }
    }

    // On the app's scope, not the screen's: a change made just before the screen goes away -
    // Back, a rotation - would be cancelled with it and silently not saved.
    fun updateSettings(transform: (AmethystSettings) -> AmethystSettings) {
        graph.appScope.launch { graph.settingsStore.update(transform) }
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

    // The page Back's gesture draws aside (its Navigation.topKey) and how far, 0..1; null when
    // none is. Kept after a Back until the page has gone, so it leaves from where the finger
    // let go of it.
    var dragged by remember { mutableStateOf<String?>(null) }
    var dragEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    val dragProgress = remember { Animatable(0f) }
    val gestureScope = rememberCoroutineScope()

    fun open(next: Screen) {
        dragged = null
        stack = Navigation.open(stack, next)
    }

    // What the cleanup's service list needs, wherever it shows: Settings → Cleanup, the
    // introduction's step, one app's own model.
    fun cleanupAccess(onOpenEngine: ((String) -> Unit)?) = CleanupAccess(
        hasKeyFor = { alias -> graph.secretStore.has(alias) },
        onSaveKey = { alias, key ->
            graph.secretStore.put(alias, key)
            graph.keySaved(alias)
        },
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
        updateSettings { FirstRun.use(it, option, PhoneLocales.tags()) }
    }

    fun back() {
        stack = Navigation.back(stack)
    }

    // With "Remove animations" on the phone the screens change at once and the gesture moves
    // nothing, as the other moving parts of the app do (Timeline). Asked each time: turning it on
    // changes no configuration, so Amber may stay open across it.
    fun animate() = ValueAnimator.areAnimatorsEnabled()

    // Back's gesture (Android 14+) draws the page aside as the finger moves; let go, it goes
    // back, or settles where it was when the gesture is called off. Before Android 14, and for
    // the Back button, the flow ends at once and only the going back is left.
    PredictiveBackHandler(enabled = stack.size > 1) { progress ->
        val key = Navigation.topKey(stack)
        dragProgress.snapTo(0f)
        dragged = key
        try {
            progress.collect { event ->
                dragEdge = event.swipeEdge
                if (animate()) dragProgress.snapTo(event.progress)
            }
            back()
            gestureScope.launch {
                delay(SCREEN_OUT_MS.toLong())
                if (dragged == key) dragged = null
            }
        } catch (cancelled: CancellationException) {
            gestureScope.launch {
                dragProgress.animateTo(0f, spring())
                if (dragged == key) dragged = null
            }
            throw cancelled
        }
    }
    BackHandler(enabled = screen == Screen.Onboarding && Onboarding.previous(step, settings, path) != null) {
        Onboarding.previous(step, settings, path)?.let { step = it }
    }

    AmberBackground {
        CompositionLocalProvider(LocalUndo provides undo) {
            // Clear of the status and navigation bars, a camera cutout (landscape) and the keyboard,
            // so a field being typed in is never under it: the pages shrink above it and scroll the
            // field into view. Taken here once, so a page asking for the keyboard's room again
            // (PracticeStep) gets none twice.
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                // The introduction draws its own line: where you are, Back and Skip.
                if (screen != Screen.Onboarding) {
                    TopBar(
                        title = screen.title(settings, apps),
                        showBack = screen != Screen.Home,
                        showSettings = screen == Screen.Home,
                        wordmark = screen == Screen.Home,
                        onBack = ::back,
                        onSettings = { open(Screen.Settings) },
                    )
                }

                val shift = with(LocalDensity.current) { SCREEN_SHIFT.roundToPx() }
                val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                AnimatedContent(
                    targetState = stack,
                    // Only a new screen on top moves; the stack changing under it does not.
                    contentKey = Navigation::topKey,
                    transitionSpec = { screenChange(Navigation.isBack(initialState, targetState), shift, rtl, animate()) },
                    label = "screen",
                    modifier = Modifier.fillMaxSize(),
                ) { shown ->
                    val current = shown.last()
                    val key = Navigation.topKey(shown)
                    entries.SaveableStateProvider(key) {
                        DisposableEffect(key) {
                            shownKeys += key
                            onDispose {
                                shownKeys -= key
                                // Gone from the screen and from the stack: left, not just covered.
                                if (key !in Navigation.keys(stack)) entries.removeState(key)
                            }
                        }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    if (dragged != key) return@graphicsLayer
                                    val at = dragProgress.value
                                    // Smaller, and moving with the finger, away from the edge it came from.
                                    val scale = 1f - BACK_SCALE * at
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = (if (dragEdge == BackEventCompat.EDGE_LEFT) 1 else -1) * shift * at
                                },
                        ) {
                            if (current == Screen.Home || current == Screen.Onboarding || secretsReady) when (current) {
                                Screen.Onboarding -> {
                                    val records by graph.memory.log.records.collectAsStateWithLifecycle()
                                    val languagePack by graph.firstRun.languagePack.collectAsStateWithLifecycle()
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
                                            secretsReady = secretsReady,
                                            pending = pending,
                                            nudge = nudge,
                                            languagePack = FirstRun.languagePack(settings, languagePack, FirstRunSetup.phoneTag()),
                                            checkLanguagePack = graph.firstRun::checkLanguagePack,
                                            onGetLanguagePack = { onInstallLanguage(settings.activeEngine?.let(LanguageList::forDictation)) },
                                        ),
                                        onStep = { step = it },
                                        onFinish = {
                                            if (!settings.onboardingDone) updateSettings { it.copy(onboardingDone = true) }
                                            stack = Navigation.start(introduce = false)
                                        },
                                        path = path,
                                        onPath = { chosen ->
                                            path = chosen
                                            // Only a new install is given anything here: on a replay both
                                            // leave every choice as it is and just walk through the steps.
                                            if (chosen == OnboardingPath.QUICK) graph.firstRun.quick(finish = false) else graph.firstRun.appStyles()
                                            Onboarding.next(OnboardingStep.WELCOME, settings, chosen)?.let { step = it }
                                        },
                                        onSkipAll = {
                                            // What Quick decides, without asking for anything: Home shows
                                            // what is still missing. On a replay it only leaves.
                                            graph.firstRun.quick(finish = true)
                                            stack = Navigation.start(introduce = false)
                                        },
                                    )
                                }

                                Screen.Home -> HomeScreen(
                                    languagePack = FirstRun.languagePack(settings, graph.firstRun.languagePack.collectAsStateWithLifecycle().value, FirstRunSetup.phoneTag()),
                                    checkLanguagePack = graph.firstRun::checkLanguagePack,
                                    setup = setup,
                                    actions = actions,
                                    settings = settings,
                                    dictationState = graph.dictation.state,
                                    stats = stats,
                                    download = download,
                                    pending = pending,
                                    nudge = nudge,
                                    lastDictation = lastDictation,
                                    historyCount = historyCount,
                                    onOpenHistory = { open(Screen.Page(SettingsPage.HISTORY)) },
                                    historyOffer = HistoryOffer.shows(settings),
                                    onHistoryOffer = { on -> updateSettings { if (on) HistoryOffer.accept(it) else HistoryOffer.decline(it) } },
                                    canTryAgain = canTryAgainHome,
                                    onTryAgain = { graph.dictation.tryAgain(inAmber = true) },
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
                                    onDismissOldAmber = { updateSettings { it.copy(ui = it.ui.copy(oldAmberDismissed = true)) } },
                                )

                                Screen.Settings -> SettingsScreen(
                                    settings = settings,
                                    setup = setup,
                                    version = build?.version.orEmpty(),
                                    releaseChannel = build?.channel,
                                    onThemeChange = { theme -> updateSettings { it.copy(ui = it.ui.copy(theme = theme)) } },
                                    onOpen = { page -> open(Screen.Page(page)) },
                                    cleanupTrouble = cleanupTrouble,
                                )

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
                                            onOpenFallback = { open(Screen.Page(SettingsPage.FALLBACK)) },
                                        )
                                        SettingsPage.CLEANUP -> CleanupScreen(settings = settings, access = cleanupAccess)
                                        SettingsPage.FALLBACK -> FallbackScreen(settings, ::updateSettings)
                                        SettingsPage.OWN_WORDS -> OwnWordsScreen(settings, ::updateSettings)
                                        SettingsPage.RECORDING -> RecordingScreen(settings, ::updateSettings)
                                        SettingsPage.INSERTING -> TextScreen(settings, ::updateSettings)
                                        SettingsPage.APP_STYLES -> AppStylesScreen(
                                            settings, ::updateSettings,
                                            apps = apps,
                                            usage = appUsage,
                                            onOpenApp = { pkg -> open(Screen.AppStyle(pkg)) },
                                            onOpenCleanup = { open(Screen.Page(SettingsPage.CLEANUP)) },
                                        )
                                        SettingsPage.BUBBLE -> BubbleScreen(
                                            settings, ::updateSettings,
                                            onOpenQuickMenu = { open(Screen.Page(SettingsPage.QUICK_MENU)) },
                                            apps = apps,
                                            usage = appUsage,
                                        )
                                        SettingsPage.QUICK_MENU -> QuickMenuScreen(settings, ::updateSettings)
                                        SettingsPage.HISTORY -> {
                                            // Opened: what is past its time goes, the rest is opened - only
                                            // here, while the phone is unlocked. Counted too: what cannot be
                                            // opened can still be deleted.
                                            val opened by produceState(emptyList<HistoryEntry>() to 0, historyKeep, historyChanges) {
                                                value = withContext(Dispatchers.IO) { graph.history.entries(historyKeep).let { it to graph.history.count(historyKeep) } }
                                            }
                                            val (entries, keptCount) = opened
                                            val keptChanges by graph.memory.kept.changes.collectAsStateWithLifecycle()
                                            val canTryAgain = remember(keptChanges) { graph.memory.kept.hasRecording }
                                            HistoryScreen(
                                                settings = settings,
                                                entries = entries,
                                                appName = apps::label,
                                                onKeepChange = { keep -> updateSettings { it.copy(privacy = it.privacy.copy(history = keep)) } },
                                                onCopy = { text ->
                                                    // From Android 13 the system confirms a copy itself.
                                                    if (OwnClipboard.copy(context, text) && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                                        Toast.makeText(context, R.string.dictation_copied, Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                onShare = { text ->
                                                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                                                    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                                },
                                                onDeleteAll = { graph.deleteHistory() },
                                                count = keptCount,
                                                canTryAgain = canTryAgain,
                                                onTryAgain = { graph.dictation.tryAgain(forHistory = true) },
                                            )
                                        }
                                        // Switched on, the graph forgets what is kept now.
                                        SettingsPage.PRIVACY -> PrivacyScreen(settings, onOpen = { page -> open(Screen.Page(page)) })
                                        SettingsPage.SECURITY_MODE -> SecurityModeScreen(settings) { on ->
                                            updateSettings { it.copy(privacy = it.privacy.copy(securityMode = on)) }
                                        }
                                        SettingsPage.COMMANDS -> VoiceCommandsScreen(settings, ::updateSettings)
                                        SettingsPage.STATS -> StatsScreen(
                                            settings = settings,
                                            stats = stats,
                                            today = today,
                                            engineName = { id -> settings.engines.firstOrNull { it.id == id }?.let { Texts.engineName(context, it) } ?: id },
                                            appName = apps::label,
                                            onUpdateSettings = ::updateSettings,
                                            onClear = { graph.clearStats() },
                                        )
                                        SettingsPage.RELIABILITY -> ReliabilityScreen(setup, actions)
                                        SettingsPage.LICENSES -> LicensesScreen()
                                        SettingsPage.PRIVACY_POLICY -> PrivacyPolicyScreen(onOpenUrl = ::openUrl)
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
                                            val records by graph.memory.log.records.collectAsStateWithLifecycle()
                                            val keyboardLog by graph.keyboardLog.collectAsStateWithLifecycle()
                                            val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
                                            DiagnosticsScreen(
                                                records = records,
                                                keyboardLog = keyboardLog,
                                                otherAmbers = setup.otherAmbers,
                                                onOpenAccessibility = actions.openAccessibilityList,
                                                // Undo brings them back: Insert again and Copy last read the same records.
                                                onClear = {
                                                    val cleared = graph.memory.log.records.value
                                                    val keyboard = graph.keyboardLog.value
                                                    graph.memory.log.clear()
                                                    graph.keyboardLog.value = emptyList()
                                                    undo.offer(context.getString(R.string.diag_cleared)) {
                                                        graph.memory.log.restore(cleared)
                                                        graph.keyboardLog.value = graph.keyboardLog.value + keyboard
                                                    }
                                                },
                                                appTitle = "Embersay $version".trim(), // English: the report's heading
                                                // The security mode never writes the clipboard.
                                                canCopy = SecurityMode.mayCopy(settings),
                                            )
                                        }
                                        SettingsPage.ABOUT -> {
                                            val records by graph.memory.log.records.collectAsStateWithLifecycle()
                                            AboutScreen(
                                                settings = settings,
                                                diagnosticsCount = records.size,
                                                version = build?.version.orEmpty(),
                                                releaseChannel = build?.channel,
                                                onOpen = { page -> open(Screen.Page(page)) },
                                                onReplayIntroduction = {
                                                    // On top of this page, the flag left alone: Back on the first
                                                    // step returns here, and leaving halfway changes nothing.
                                                    step = OnboardingStep.WELCOME
                                                    open(Screen.Onboarding)
                                                },
                                            )
                                        }
                                    }
                                }

                                is Screen.AppStyle -> {
                                    // No profile: an app listed for its hidden bubble only (or one just added
                                    // and not stored yet). Its page shows what a profile would start with;
                                    // the first change there makes one.
                                    val label = remember(current.packageName) { apps.label(current.packageName) }
                                    val profile = settings.profiles.firstOrNull { it.packageName == current.packageName }
                                        ?: AppProfile(current.packageName, label)
                                    // Its cleanup picker lists what Amber PCs and Servers offer.
                                    LaunchedEffect(current.packageName) { graph.amberCatalogs.refreshAll() }
                                    AppStyleScreen(
                                        profile = profile,
                                        settings = settings,
                                        access = cleanupAccess,
                                        modelReady = graph::modelReady,
                                        onUpdateSettings = ::updateSettings,
                                        onRemoved = ::back,
                                    )
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
                                                if (value.isBlank()) {
                                                    graph.secretStore.clear(alias)
                                                } else {
                                                    graph.secretStore.put(alias, value)
                                                    graph.keySaved(alias)
                                                }
                                            },
                                            // The app's scope too: the screen saves typed fields as it
                                            // goes away, the moment its own scope is cancelled.
                                            onSave = { updated: EngineConfig ->
                                                graph.appScope.launch {
                                                    graph.settingsStore.update { it.withEngine(updated) }
                                                    // Once stored, so the answer finds the engine at its new
                                                    // address. A new address is asked at once, the same one
                                                    // once a minute.
                                                    graph.amberCatalogs.refresh(updated)
                                                }
                                            },
                                            onActivate = { updateSettings { FirstRun.chosen(it, config.id) } },
                                            cleanupOnly = Servers.cleanupOnly(settings, config),
                                            onDelete = {
                                                // Its key goes with it, unless another server keeps it
                                                // under the same name: a server added again later starts
                                                // without one.
                                                config.apiKeyAlias?.let { alias ->
                                                    if (settings.engines.none { it.id != config.id && it.apiKeyAlias == alias }) graph.secretStore.clear(alias)
                                                }
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
            // Above the bars and the keyboard, as the pages are.
            UndoHost(
                snackbars,
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }
    }
}

/**
 * How one screen gives way to the next: the new one comes a short way from the side and
 * settles in, the old one fades as it moves a little the other way - forward from the end of
 * the line, Back from its start, as the introduction's steps do. With animations off, at once.
 */
private fun screenChange(back: Boolean, shift: Int, rtl: Boolean, animate: Boolean): ContentTransform {
    if (!animate) return EnterTransition.None togetherWith ExitTransition.None
    val from = (if (back) -1 else 1) * (if (rtl) -1 else 1)
    val settle = tween<IntOffset>(SCREEN_IN_MS, easing = LinearOutSlowInEasing)
    return (fadeIn(tween(SCREEN_IN_MS, easing = LinearOutSlowInEasing)) + slideInHorizontally(settle) { from * shift }) togetherWith
        (fadeOut(tween(SCREEN_OUT_MS)) + slideOutHorizontally(tween(SCREEN_OUT_MS)) { -from * shift / 3 })
}

/** The screen stack in Android's saved state, as Navigation writes it; unreadable, it starts over. */
private fun stackSaver(introduce: Boolean) = listSaver<List<Screen>, String>(
    save = { Navigation.save(it) },
    restore = { Navigation.restore(it, introduce) },
)

@Composable
internal fun TopBar(
    title: String,
    showBack: Boolean,
    showSettings: Boolean,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    /** Home: the name as its mark ([Wordmark]) instead of plain text. */
    wordmark: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showBack) IconAction(onClick = onBack, contentDescription = stringResource(R.string.back), glyph = BackGlyph)
        if (wordmark) {
            Box(Modifier.weight(1f)) { Wordmark() }
        } else {
            // A long app or server name takes a second line and then ends in "…"; it is the
            // page's heading for screen readers.
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = AmberPalette.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
        }
        if (showSettings) IconAction(onClick = onSettings, contentDescription = stringResource(R.string.title_settings), glyph = GearGlyph)
    }
}

private const val ENGINE_APPEAR_GRACE_MS = 500L

/** How far a screen comes from the side as it opens, and how far Back's gesture draws it aside. */
private val SCREEN_SHIFT = 30.dp

/** How long a new screen takes to settle in, and the old one to go. */
private const val SCREEN_IN_MS = 220
private const val SCREEN_OUT_MS = 100

/** How much smaller Back's gesture makes the page at its end. */
private const val BACK_SCALE = 0.1f

/** How long an app page asked for by the quick menu may take to appear in the settings. */
private const val APP_PAGE_WAIT_MS = 2_000L

private const val UPDATES_CLOCK_MS = 30_000L


/** How often Home counts the history again, as its oldest dictations run out of time. */
private const val HISTORY_RECOUNT_MS = 60_000L

/** How often Home looks again whether the last dictation is still kept. */
private const val LAST_DICTATION_RECHECK_MS = 30_000L
