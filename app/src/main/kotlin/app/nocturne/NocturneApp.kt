package app.nocturne

import android.app.Application
import android.content.Context
import app.nocturne.accessibility.NocturneAccessibilityService
import app.nocturne.core.settings.NocturneSettings
import app.nocturne.data.SecretStore
import app.nocturne.data.SettingsStore
import app.nocturne.data.StrategyStore
import app.nocturne.dictation.DictationController
import app.nocturne.engine.EngineFactory
import app.nocturne.core.net.OkHttpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class NocturneApp : Application() {

    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
    }

    companion object {
        fun graph(context: Context): Graph =
            (context.applicationContext as NocturneApp).graph
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
    val secretStore = SecretStore(app)
    val strategyStore = StrategyStore(app)

    /** Synchronously readable settings, for the service paths that cannot suspend. */
    val settings: StateFlow<NocturneSettings> = settingsStore.settings
        .stateIn(appScope, SharingStarted.Eagerly, NocturneSettings.defaults())

    val engineFactory = EngineFactory(app, OkHttpTransport(), secretStore)

    val dictation = DictationController(app, this, appScope)

    /** Set by the accessibility service while it is connected; null means no insertion path. */
    @Volatile
    var accessibilityService: NocturneAccessibilityService? = null
}
