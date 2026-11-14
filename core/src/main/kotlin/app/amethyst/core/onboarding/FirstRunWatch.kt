package app.amethyst.core.onboarding

import app.amethyst.core.engine.EngineOption
import app.amethyst.core.settings.AmethystSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** What the watch needs of the model downloads: the app's stores, or a fake in a test. */
interface ModelFetch {

    /** Whether [option]'s model is on the phone, as it changes. */
    fun ready(option: EngineOption): Flow<Boolean>

    /** Starts [option]'s download on Wi-Fi, or holds it until there is one. */
    suspend fun fetch(option: EngineOption)

    /** Lets go of a download held for [option]. */
    suspend fun letGo(option: EngineOption)
}

/**
 * The one thing that outlives the introduction - the recognition that waits for its model
 * ([FirstRun.pending]): its download is taken up again with every start of the process, and
 * when the model is there it takes over from Android's recognition by itself. A recognition
 * the user chose meanwhile stays, and the download nobody wants any more is let go.
 */
class FirstRunWatch(
    /** The stored settings, as they change - from the first value that was read. */
    private val settings: Flow<AmethystSettings>,
    private val update: suspend (transform: (AmethystSettings) -> AmethystSettings) -> Unit,
    /** Asked only when something waits: the app's stores read every model file when built. */
    private val downloads: ModelFetch,
    private val phoneTags: () -> List<String>,
    private val log: (String) -> Unit = {},
) {

    /** Runs for as long as the process lives. */
    suspend fun run() {
        settings.map { it.firstRun.pendingEngine }.distinctUntilChanged().collect { name ->
            // One at a time, to the end: the settling below changes the very value collected here.
            if (name == null) return@collect
            val option = FirstRun.pending(settings.first())
            val ready = if (option != null) {
                downloads.fetch(option)
                // Until the model is there, or the user chose a recognition of their own.
                combine(settings, downloads.ready(option)) { current, there -> there.takeIf { FirstRun.verdict(current, it) != PendingVerdict.WAIT } }
                    .filterNotNull().first()
            } else {
                false
            }
            var after: AmethystSettings? = null
            update { current -> FirstRun.settle(current, ready, phoneTags()).also { after = it } }
            log("first run: $name ${if (after?.let { FirstRun.pending(it) } == null) "settled" else "still waiting"}, active ${after?.activeEngineId}")
            // A download held for Wi-Fi that nobody wants any more is let go.
            if (option != null && after?.let { FirstRun.stillWanted(it, option) } == false) downloads.letGo(option)
        }
    }
}
