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
 * The one thing that outlives the introduction - the model this install still has to fetch
 * ([FirstRun.wanted]): its download is taken up again with every start of the process, until
 * it is on the phone. A recognition that waited for it then takes over from Android's by
 * itself; one that was active all along simply starts to work. A recognition the user chose
 * meanwhile stays, and the download nobody wants any more is let go.
 */
class FirstRunWatch(
    /** The stored settings, as they change - from the first value that was read. */
    private val settings: Flow<AmethystSettings>,
    private val update: suspend (transform: (AmethystSettings) -> AmethystSettings) -> Unit,
    /** Asked only when something is wanted: the app's stores read every model file when built. */
    private val downloads: ModelFetch,
    private val phoneTags: () -> List<String>,
    private val log: (String) -> Unit = {},
) {

    /** Runs for as long as the process lives. */
    suspend fun run() {
        settings.map { it.firstRun.pendingEngine to it.firstRun.wantedModel }.distinctUntilChanged().collect { (waiting, active) ->
            // One at a time, to the end: the settling below changes the very values collected here.
            val name = waiting ?: active ?: return@collect
            val option = FirstRun.wanted(settings.first())
            if (option != null) downloads.fetch(option)
            var after: AmethystSettings? = null
            do {
                // Until the model is there, or the user chose a recognition of their own.
                val ready = option != null && combine(settings, downloads.ready(option)) { current, there -> there.takeIf { over(current, option, it) } }
                    .filterNotNull().first()
                update { current ->
                    // What is wanted may be another model by now; that one gets a round of its own.
                    val mine = FirstRun.wanted(current).let { it == null || it == option }
                    (if (mine) FirstRun.settle(current, ready, phoneTags()) else current).also { after = it }
                }
                // Again while it is still this model's turn: the waiting recognition made active by hand.
            } while (option != null && after?.let { FirstRun.wanted(it) } == option)
            log("first run: $name settled, active ${after?.activeEngineId}")
            // A download held for Wi-Fi that nobody wants any more is let go.
            if (option != null && after?.let { FirstRun.stillWanted(it, option) } == false) downloads.letGo(option)
        }
    }

    /** Whether the wait for [option] has ended, one way or the other. */
    private fun over(current: AmethystSettings, option: EngineOption, ready: Boolean): Boolean =
        FirstRun.wanted(current) != option || FirstRun.verdict(current, ready) != PendingVerdict.WAIT
}
