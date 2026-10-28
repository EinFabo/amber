package app.amethyst.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.amethyst.AmethystApp
import app.amethyst.Graph
import app.amethyst.capture.MicForegroundService
import app.amethyst.core.capture.TriggerSource
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.EngineConfig
import app.amethyst.engine.AndroidOnDeviceEngine
import app.amethyst.ui.screen.EngineDetailScreen
import app.amethyst.ui.screen.HomeScreen
import app.amethyst.ui.screen.SettingsScreen
import app.amethyst.ui.screen.SubPage
import app.amethyst.ui.screen.StatsScreen
import app.amethyst.ui.screen.SettingsPage
import app.amethyst.ui.screen.ReliabilityScreen
import app.amethyst.ui.screen.RecordingScreen
import app.amethyst.ui.screen.InsertingScreen
import app.amethyst.ui.screen.EnginesScreen
import app.amethyst.ui.screen.DiagnosticsCard
import app.amethyst.ui.screen.BubbleScreen
import app.amethyst.ui.screen.AppStylesScreen
import app.amethyst.core.stats.StatsSummary
import app.amethyst.core.apps.AppChoice
import app.amethyst.ui.AndroidAppSource
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val setupState = MutableStateFlow(SetupState())

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
        // The glass background runs behind the status and navigation bars.
        enableEdgeToEdge()
        val graph = AmethystApp.graph(this)
        refreshSetup()

        setContent {
            AmberTheme {
                AmethystRoot(
                    graph = graph,
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
                        openAccessibility = { Setup.openAccessibilitySettings(this) },
                        openAppInfo = { Setup.openAppSettings(this) },
                        openBattery = { Setup.openBatterySettings(this) },
                        startMicService = {
                            MicForegroundService.start(this)
                            refreshSetup()
                        },
                    ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Accessibility and battery state change outside the app, in system settings.
        refreshSetup()
        ensureMicServiceRunning()
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
            Toast.makeText(this, "Download started. Android will report back shortly.", Toast.LENGTH_LONG).show()
            return
        }
        val opened = runCatching {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }.isSuccess
        if (!opened) Setup.openAppSettings(this)
        Toast.makeText(
            this,
            "Download the language under Speech recognition → Offline speech recognition.",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun refreshSetup() {
        setupState.value = Setup.snapshot(this)
    }

    private companion object {
        const val SERVICE_START_GRACE_MS = 600L
    }
}

/** Callbacks that need an Activity, passed down so the screens stay free of Android plumbing. */
class SetupActions(
    val requestMicrophone: () -> Unit,
    val requestNotifications: () -> Unit,
    val openAccessibility: () -> Unit,
    val openAppInfo: () -> Unit,
    val openBattery: () -> Unit,
    val startMicService: () -> Unit,
)

private sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data class Page(val page: SettingsPage) : Screen
    data class EngineDetail(val engineId: String) : Screen
}

private fun Screen.title(): String = when (this) {
    Screen.Home -> "Amber"
    Screen.Settings -> "Settings"
    is Screen.EngineDetail -> "Engine"
    is Screen.Page -> when (page) {
        SettingsPage.ENGINES -> "Engine"
        SettingsPage.RECORDING -> "Recording"
        SettingsPage.INSERTING -> "Inserting"
        SettingsPage.APP_STYLES -> "App styles"
        SettingsPage.BUBBLE -> "Bubble"
        SettingsPage.STATS -> "Statistics"
        SettingsPage.RELIABILITY -> "Reliability"
        SettingsPage.DIAGNOSTICS -> "Diagnostics"
    }
}

@Composable
private fun AmethystRoot(
    graph: Graph,
    setup: SetupState,
    actions: SetupActions,
    onInstallLanguage: (String?) -> Unit,
) {
    // A back stack: Home → Settings → a page → an engine, and back the same way.
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    val screen = stack.last()
    val tester = remember(graph) { ConnectionTester(graph) }
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
    val apps = remember(context) { AndroidAppSource(context) }
    val appUsage = remember(statList) { AppChoice.usage(statList) }

    fun updateSettings(transform: (AmethystSettings) -> AmethystSettings) {
        scope.launch { graph.settingsStore.update(transform) }
    }

    fun open(next: Screen) {
        stack = stack + next
    }

    fun back() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    BackHandler(enabled = stack.size > 1) { back() }

    AmberBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            TopBar(
                title = screen.title(),
                showBack = screen != Screen.Home,
                showSettings = screen == Screen.Home,
                onBack = ::back,
                onSettings = { open(Screen.Settings) },
            )

            AnimatedContent(
                targetState = screen,
                // A plain cross-fade; screens do not slide or bounce.
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
                label = "screen",
                modifier = Modifier.fillMaxSize(),
            ) { current ->
                when (current) {
                    Screen.Home -> HomeScreen(
                        setup = setup,
                        actions = actions,
                        settings = settings,
                        dictationState = graph.dictation.state,
                        stats = stats,
                        onStartTest = { graph.dictation.start(TriggerSource.IN_APP) },
                        onStopTest = { graph.dictation.stop() },
                        onOpenEngines = { open(Screen.Page(SettingsPage.ENGINES)) },
                        onOpenStats = { open(Screen.Page(SettingsPage.STATS)) },
                        onOpenActiveEngine = { open(Screen.EngineDetail(settings.activeEngineId)) },
                        onInstallLanguage = { onInstallLanguage(settings.activeEngine?.languageTag) },
                        onUseLocalWhisper = {
                            val preset = EnginePresets.LOCAL_WHISPER
                            if (settings.engines.none { it.id == preset.id }) {
                                updateSettings {
                                    it.withEngine(EngineConfig.fromPreset(preset, languageTag = EngineConfig.defaultLanguageTag(preset.kind)))
                                }
                            }
                            open(Screen.EngineDetail(preset.id))
                        },
                    )

                    Screen.Settings -> {
                        val records by graph.dictationLog.records.collectAsStateWithLifecycle()
                        SettingsScreen(
                            settings = settings,
                            setup = setup,
                            diagnosticsCount = records.size,
                            version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() },
                            onOpen = { page -> open(Screen.Page(page)) },
                        )
                    }

                    is Screen.Page -> when (current.page) {
                        SettingsPage.ENGINES -> EnginesScreen(
                            settings = settings,
                            hasKeyFor = { alias -> graph.secretStore.has(alias) },
                            medianWaitMs = stats.medianWaitMs,
                            onUpdateSettings = ::updateSettings,
                            onOpenEngine = { engineId -> open(Screen.EngineDetail(engineId)) },
                        )
                        SettingsPage.RECORDING -> RecordingScreen(settings, ::updateSettings)
                        SettingsPage.INSERTING -> InsertingScreen(settings, ::updateSettings)
                        SettingsPage.APP_STYLES -> AppStylesScreen(settings, ::updateSettings, apps = apps, usage = appUsage)
                        SettingsPage.BUBBLE -> BubbleScreen(
                            settings, ::updateSettings,
                            apps = apps,
                            usage = appUsage,
                        )
                        SettingsPage.STATS -> StatsScreen(
                            settings = settings,
                            stats = stats,
                            today = today,
                            engineName = { id -> settings.engines.firstOrNull { it.id == id }?.displayName ?: id },
                            appName = apps::label,
                            onUpdateSettings = ::updateSettings,
                            onClear = { graph.stats.clear() },
                        )
                        SettingsPage.RELIABILITY -> ReliabilityScreen(setup, actions)
                        SettingsPage.DIAGNOSTICS -> {
                            val records by graph.dictationLog.records.collectAsStateWithLifecycle()
                            val keyboardLog by graph.keyboardLog.collectAsStateWithLifecycle()
                            SubPage {
                                DiagnosticsCard(
                                    records = records,
                                    keyboardLog = keyboardLog,
                                    onClear = {
                                        graph.dictationLog.clear()
                                        graph.keyboardLog.value = emptyList()
                                    },
                                )
                            }
                        }
                    }

                    is Screen.EngineDetail -> {
                        val tinfoilLog by graph.tinfoilLog.collectAsStateWithLifecycle()
                        val parakeetState by graph.parakeetModels.state.collectAsStateWithLifecycle()
                        val whisperModels by graph.whisperModels.states.collectAsStateWithLifecycle()
                        val config = settings.engines.firstOrNull { it.id == current.engineId }
                        if (config == null) {
                            // Not in the settings yet (just added) or deleted from under us;
                            // decide in an effect, never during composition.
                            LaunchedEffect(current.engineId) {
                                delay(ENGINE_APPEAR_GRACE_MS)
                                if (graph.settings.value.engines.none { it.id == current.engineId }) back()
                            }
                        } else {
                            EngineDetailScreen(
                                config = config,
                                active = config.id == settings.activeEngineId,
                                // Read once per engine, not on every recomposition: this is a
                                // decrypt out of EncryptedSharedPreferences.
                                storedKey = remember(config.apiKeyAlias) {
                                    graph.secretStore.get(config.apiKeyAlias)
                                },
                                whisperModels = whisperModels,
                                onSaveKey = { alias, value ->
                                    if (value.isBlank()) graph.secretStore.clear(alias)
                                    else graph.secretStore.put(alias, value)
                                },
                                onSave = { updated: EngineConfig ->
                                    updateSettings { it.withEngine(updated) }
                                },
                                onActivate = { updateSettings { it.copy(activeEngineId = config.id) } },
                                onDelete = {
                                    updateSettings { current ->
                                        val remaining = current.engines.filterNot { it.id == config.id }
                                        current.copy(
                                            engines = remaining,
                                            activeEngineId = if (current.activeEngineId == config.id) {
                                                remaining.firstOrNull()?.id ?: ""
                                            } else {
                                                current.activeEngineId
                                            },
                                        )
                                    }
                                    back()
                                },
                                onTest = { candidate -> tester.test(candidate) },
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
        if (showBack) IconAction(onClick = onBack, contentDescription = "Back", glyph = BackGlyph)
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = AmberPalette.Text,
            modifier = Modifier.weight(1f),
        )
        if (showSettings) IconAction(onClick = onSettings, contentDescription = "Settings", glyph = GearGlyph)
    }
}

private const val ENGINE_APPEAR_GRACE_MS = 500L

