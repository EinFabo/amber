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

    /** Starts [option]'s download on Wi-Fi - on any network with [mobileData] - or holds it until there is one. */
    suspend fun fetch(option: EngineOption, mobileData: Boolean)

    /** Lets go of [option]'s download, held or running. */
    suspend fun letGo(option: EngineOption)

    /** Deletes [option]'s model from the phone, and lets go of its download if one is held or runs. */
    suspend fun delete(option: EngineOption)
}

/**
 * The one thing that outlives the introduction - the models this install still has to fetch
 * ([FirstRun.wanted]), one after the other: each download is taken up again with every start
 * of the process, until it is on the phone. The small model that stands in comes first, over
 * mobile data too; the recognition that waited then takes over from it by itself, and the
 * small model is deleted ([FirstRun.leftover]). One that was active all along simply starts to
 * work. A recognition the user chose meanwhile stays, and the download nobody wants any more
 * is let go.
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
        settings.map { it.firstRun.copy(appStylesGiven = false) to FirstRun.leftover(it) }.distinctUntilChanged().collect { (firstRun, _) ->
            // One at a time, to the end: the settling below changes the very values collected here.
            tidy()
            val name = firstRun.pendingEngine ?: firstRun.wantedModel ?: return@collect
            val now = settings.first()
            val option = FirstRun.wanted(now)
            if (option != null) downloads.fetch(option, mobileData = FirstRun.anyNetwork(now, option))
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
            // A download nobody wants any more is let go, held for Wi-Fi or running.
            if (option != null && after?.let { FirstRun.stillWanted(it, option) } == false) downloads.letGo(option)
        }
    }

    /** The small model that stood in, deleted once nothing needs it - the file first, then the mark. */
    private suspend fun tidy() {
        val small = FirstRun.leftover(settings.first()) ?: return
        downloads.delete(small)
        update(FirstRun::tidy)
        log("first run: ${small.name} stood in and is not needed any more - deleted")
    }

    /** Whether the wait for [option] has ended, one way or the other. */
    private fun over(current: AmethystSettings, option: EngineOption, ready: Boolean): Boolean =
        FirstRun.wanted(current) != option || FirstRun.verdict(current, ready) != PendingVerdict.WAIT
}
