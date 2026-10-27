package app.nocturne.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.nocturne.Graph
import app.nocturne.NocturneApp
import app.nocturne.capture.MicForegroundService
import app.nocturne.core.settings.EngineConfig
import app.nocturne.core.settings.NocturneSettings
import app.nocturne.dictation.TriggerSource
import app.nocturne.ui.screen.EngineDetailScreen
import app.nocturne.ui.screen.HomeScreen
import app.nocturne.ui.screen.SettingsScreen
import app.nocturne.ui.theme.NocturnePalette
import app.nocturne.ui.theme.NocturneTheme
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
        val graph = NocturneApp.graph(this)
        refreshSetup()

        setContent {
            NocturneTheme {
                NocturneRoot(
                    graph = graph,
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
    val openBattery: () -> Unit,
    val startMicService: () -> Unit,
)

private sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data class EngineDetail(val engineId: String) : Screen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NocturneRoot(graph: Graph, setup: SetupState, actions: SetupActions) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val tester = remember(graph) { ConnectionTester(graph) }
    val settings by graph.settings.collectAsStateWithLifecycle()
    val dictation by graph.dictation.state.collectAsStateWithLifecycle()
    val records by graph.dictationLog.records.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    fun updateSettings(transform: (NocturneSettings) -> NocturneSettings) {
        scope.launch { graph.settingsStore.update(transform) }
    }

    Scaffold(
        containerColor = NocturnePalette.Background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (screen) {
                            Screen.Home -> "Nocturne"
                            Screen.Settings -> "Einstellungen"
                            is Screen.EngineDetail -> "Engine"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        color = NocturnePalette.Text,
                    )
                },
                navigationIcon = {
                    if (screen != Screen.Home) {
                        TextButton(onClick = { screen = Screen.Home }) {
                            Text("Zurück", color = NocturnePalette.Primary)
                        }
                    }
                },
                actions = {
                    if (screen == Screen.Home) {
                        TextButton(onClick = { screen = Screen.Settings }) {
                            Text("Einstellungen", color = NocturnePalette.Primary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NocturnePalette.Background,
                ),
            )
        },
    ) { innerPadding ->
        when (val current = screen) {
            Screen.Home -> HomeScreen(
                modifier = Modifier.padding(innerPadding),
                setup = setup,
                actions = actions,
                settings = settings,
                dictation = dictation,
                records = records,
                onStartTest = { graph.dictation.start(TriggerSource.IN_APP) },
                onStopTest = { graph.dictation.stop() },
                onClearDiagnostics = { graph.dictationLog.clear() },
                onOpenSettings = { screen = Screen.Settings },
            )

            Screen.Settings -> SettingsScreen(
                modifier = Modifier.padding(innerPadding),
                settings = settings,
                hasKeyFor = { alias -> graph.secretStore.has(alias) },
                onUpdateSettings = ::updateSettings,
                onOpenEngine = { engineId -> screen = Screen.EngineDetail(engineId) },
            )

            is Screen.EngineDetail -> {
                val config = settings.engines.firstOrNull { it.id == current.engineId }
                if (config == null) {
                    // Deleted from under us; leave the screen in an effect, never during
                    // composition.
                    LaunchedEffect(current.engineId) { screen = Screen.Settings }
                } else {
                    EngineDetailScreen(
                        modifier = Modifier.padding(innerPadding),
                        config = config,
                        storedKey = graph.secretStore.get(config.apiKeyAlias),
                        onSaveKey = { alias, value ->
                            if (value.isBlank()) graph.secretStore.clear(alias)
                            else graph.secretStore.put(alias, value)
                        },
                        onSave = { updated: EngineConfig ->
                            updateSettings { it.withEngine(updated) }
                        },
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
                            screen = Screen.Settings
                        },
                        onTest = { candidate -> tester.test(candidate) },
                    )
                }
            }
        }
    }
}
