package app.amethyst.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.amethyst.core.engine.DownloadGate
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.EngineOption
import app.amethyst.core.engine.FileProblem
import app.amethyst.core.engine.GateDecision
import app.amethyst.core.engine.ModelDownload
import app.amethyst.core.engine.download.DownloadHold
import app.amethyst.core.engine.download.HoldStep
import app.amethyst.core.engine.download.NetworkFacts
import app.amethyst.core.engine.whisper.ModelSlots.SlotState
import app.amethyst.core.engine.whisper.WhisperModels
import app.amethyst.core.settings.EngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The introduction's downloads: started at once on Wi-Fi, over mobile data only when the user
 * said so, or held until an unmetered network appears. A download taken in hand here stays in
 * hand until its model is on the phone: after a failure it is tried again by itself
 * ([DownloadHold] decides when), on a network that reaches the internet. Nothing polls and
 * nothing wakes the phone - the network's changes and one timer for the next try drive it,
 * while Amber's process lives; the next process takes it up from the stored settings
 * ([FirstRunSetup]). Also answers whether the active engine is still downloading, so a
 * dictation can wait instead of failing.
 *
 * Called on the main thread; the network's callbacks arrive there too.
 */
class ModelDownloads(
    context: Context,
    private val scope: CoroutineScope,
    private val parakeet: ParakeetModelStore,
    private val whisper: WhisperModelStore,
) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** The download in hand, and when it may start or try again. */
    private var held: EngineOption? = null
    private val hold = DownloadHold(SystemClock::elapsedRealtime)

    /** The network as the callback last described it; null without one. */
    private var network: NetworkFacts? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** The try that runs, watched to its end; and the one timer for the next. */
    private var attempt: Job? = null
    private var timer: Job? = null

    private val _waiting = MutableStateFlow<EngineOption?>(null)

    /** The download held for Wi-Fi, if any. */
    val waiting: StateFlow<EngineOption?> = _waiting

    /** The download in hand that waits for a connection or for its next try. */
    private var retrying: EngineOption? = null

    /** True without a network too: then nothing should start over what might be mobile data. */
    fun metered(): Boolean = connectivity?.isActiveNetworkMetered ?: true

    /**
     * The user's own start of [option]'s download: at once where the network allows it,
     * otherwise ASK leaves the choice to the caller's buttons. Mobile data allowed for it once
     * stays allowed while it is in hand.
     */
    fun start(option: EngineOption, mobileDataAllowed: Boolean): GateDecision {
        val allowed = mobileDataAllowed || (held == option && hold.mobileData)
        val decision = DownloadGate.decide(metered(), allowed, waitingForWifi = _waiting.value == option, needed = needsDownload(option))
        when (decision) {
            GateDecision.START -> {
                take(option, allowed)
                launch(option)
            }
            GateDecision.WAIT -> waitForWifi(option)
            GateDecision.ASK -> Unit
            // The engine is ready (or needs nothing): a wait held for another one is moot now.
            GateDecision.NONE -> release()
        }
        return decision
    }

    /** Whether [option] still has something to fetch. */
    fun needsDownload(option: EngineOption): Boolean {
        val whisperId = option.whisperModelId
        return when {
            option == EngineOption.PARAKEET -> !parakeet.isReady()
            whisperId != null -> !whisper.isReady(whisperId)
            else -> false
        }
    }

    /** Holds [option] until an unmetered network appears that reaches the internet - while Amber's process lives. */
    fun waitForWifi(option: EngineOption) {
        if (!needsDownload(option)) {
            release()
            return
        }
        take(option, mobileData = false)
        next()
    }

    /**
     * Takes [option]'s download up without the user: started on Wi-Fi that reaches the
     * internet - on any network that does with [mobileData] - and held until there is one. One
     * in hand already is left as it is.
     */
    fun resume(option: EngineOption, mobileData: Boolean = false) {
        if (held == option || isBusy(option)) return
        if (!mobileData) return waitForWifi(option)
        if (!needsDownload(option)) {
            release()
            return
        }
        take(option, mobileData = true)
        next()
    }

    /** Deletes [option]'s model, letting go of its download first; only Whisper models are ever deleted here. */
    fun delete(option: EngineOption) {
        cancelWait(option)
        option.whisperModelId?.let(whisper::delete)
    }

    private fun take(option: EngineOption, mobileData: Boolean) {
        if (held != option) attempt?.cancel()
        timer?.cancel()
        held = option
        hold.take(mobileData)
        watchNetwork()
    }

    /** Nothing in hand any more: its model is there, nobody wants it, or no waiting will help. */
    private fun release() {
        held = null
        attempt?.cancel()
        timer?.cancel()
        callback?.let { watch -> runCatching { connectivity?.unregisterNetworkCallback(watch) } }
        callback = null
        network = null
        show(waiting = null, retrying = null)
    }

    /** What the download in hand does now: start, or wait for Wi-Fi, a connection, or its turn. */
    private fun next() {
        val option = held ?: return
        if (!needsDownload(option)) {
            release()
            return
        }
        if (attempt?.isActive == true) return
        timer?.cancel()
        when (hold.step(network)) {
            HoldStep.START -> launch(option)
            HoldStep.WAIT_FOR_WIFI -> show(waiting = option, retrying = null)
            HoldStep.WAIT_FOR_NETWORK -> show(waiting = null, retrying = option)
            HoldStep.WAIT_FOR_TURN -> {
                show(waiting = null, retrying = option)
                // One timer, no loop. It stands still while the phone sleeps; the network coming
                // back after the sleep asks again, and by then the turn has come.
                val wait = hold.notBeforeMs - SystemClock.elapsedRealtime()
                timer = scope.launch(Dispatchers.Main) {
                    delay(wait.coerceAtLeast(0))
                    next()
                }
            }
        }
    }

    /** Starts a try and watches it to its end. */
    private fun launch(option: EngineOption) {
        timer?.cancel()
        val before = bytesKept(option)
        when {
            option == EngineOption.PARAKEET -> parakeet.download()
            option.whisperModelId != null -> WhisperModels.byId(option.whisperModelId)?.let(whisper::download)
            else -> Unit
        }
        show(waiting = null, retrying = null)
        attempt?.cancel()
        attempt = scope.launch(Dispatchers.Main) {
            // The stores are busy from the call above on, so the first state that is not is this try's end.
            val problem = ended(option).first { it != BUSY }
            attempt = null
            if (held == option) ended(option, problem, before)
        }
    }

    private fun ended(option: EngineOption, end: Any?, before: Long) {
        val problem = end as? FileProblem
        when {
            !needsDownload(option) -> release()
            // Cancelled by hand on its page.
            problem == null -> release()
            hold.failed(problem, progressKept = bytesKept(option) > before, resumes = resumes(option)) -> {
                Log.i(TAG, "${option.name} download failed (${problem::class.simpleName}), try ${hold.failures} - the next one follows")
                next()
            }
            else -> {
                Log.i(TAG, "${option.name} download failed (${problem::class.simpleName}) - left to the user")
                release()
            }
        }
    }

    /** [BUSY] while [option]'s download runs, then its [FileProblem], or null: there, or cancelled. */
    private fun ended(option: EngineOption): Flow<Any?> {
        val whisperId = option.whisperModelId
        return when {
            option == EngineOption.PARAKEET -> parakeet.state.map {
                when (it) {
                    is ParakeetState.Busy -> BUSY
                    is ParakeetState.Failed -> it.problem
                    else -> null
                }
            }
            whisperId != null -> whisper.states.map {
                when (val state = it[whisperId]) {
                    is SlotState.Busy -> BUSY
                    is SlotState.Failed -> state.problem
                    else -> null
                }
            }
            else -> flowOf(null)
        }
    }

    private fun isBusy(option: EngineOption): Boolean = when {
        option == EngineOption.PARAKEET -> parakeet.state.value is ParakeetState.Busy
        else -> whisper.states.value[option.whisperModelId] is SlotState.Busy
    }

    /** Only Parakeet's files are continued where they stopped; a Whisper model starts over. */
    private fun resumes(option: EngineOption): Boolean = option == EngineOption.PARAKEET

    private fun bytesKept(option: EngineOption): Long = if (resumes(option)) parakeet.bytesOnPhone() else 0

    private fun show(waiting: EngineOption?, retrying: EngineOption?) {
        this.retrying = retrying
        _waiting.value = waiting
    }

    /** Follows the default network for as long as something is in hand. */
    private fun watchNetwork() {
        if (callback != null) return
        val manager = connectivity ?: return
        network = runCatching { manager.getNetworkCapabilities(manager.activeNetwork)?.let(::facts) }.getOrNull()
        val watch = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed(facts(capabilities))
            override fun onLost(network: Network) = changed(null)
        }
        callback = watch
        runCatching { manager.registerDefaultNetworkCallback(watch, main) }
            .onFailure { Log.w(TAG, "cannot watch the network: ${it.message}") }
    }

    private fun changed(now: NetworkFacts?) {
        if (callback == null || now == network) return
        val back = now?.validated == true && network?.validated != true
        network = now
        if (back) hold.networkBack()
        next()
    }

    /** Validated: Android reached the internet through it - a Wi-Fi with a login page has not. */
    private fun facts(capabilities: NetworkCapabilities) = NetworkFacts(
        validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
    )

    /** Lets go of a download held for [option], whatever it waits for; one held for another stays. */
    fun cancelWait(option: EngineOption) {
        if (held == option) release()
    }

    /**
     * Lets go of [option]'s download altogether, held or running: the recognition it was for
     * is no longer the one in use.
     */
    fun letGo(option: EngineOption) {
        cancelWait(option)
        if (!needsDownload(option)) return
        Log.i(TAG, "${option.name} is not wanted any more - its download is let go")
        if (option == EngineOption.PARAKEET) parakeet.cancel() else option.whisperModelId?.let(whisper::cancel)
    }

    /** Whether [option]'s model is on the phone, as it changes - for the recognition that waits for it. */
    fun ready(option: EngineOption): Flow<Boolean> {
        val whisperId = option.whisperModelId
        return when {
            option == EngineOption.PARAKEET -> parakeet.state.map { it is ParakeetState.Ready }
            whisperId != null -> whisper.states.map { it[whisperId] is SlotState.Ready }
            else -> flowOf(true)
        }
    }

    /** The download the [config] engine is waiting for, or null when it is not downloading. */
    fun forEngine(config: EngineConfig): ModelDownload? = when (config.kind) {
        EngineKind.LOCAL_SHERPA -> forOption(EngineOption.PARAKEET)
        EngineKind.WHISPER_CPP -> forWhisper(config.modelId ?: WhisperModels.default.id)
        else -> null
    }

    /** Whether [option]'s download gave up - nothing waiting will fix that, only another try. */
    fun failed(option: EngineOption): Boolean {
        if (held == option) return false
        val whisperId = option.whisperModelId
        return when {
            option == EngineOption.PARAKEET -> parakeet.state.value is ParakeetState.Failed
            whisperId != null -> whisper.states.value[whisperId] is SlotState.Failed
            else -> false
        }
    }

    /** The same for one of the introduction's options, active or not. */
    fun forOption(option: EngineOption): ModelDownload? {
        val whisperId = option.whisperModelId
        return when {
            // A model on the phone wins over a wait held for it - it was fetched some other way.
            option == EngineOption.PARAKEET -> when (val state = parakeet.state.value) {
                is ParakeetState.Busy -> ModelDownload.Running(state.received, state.total)
                is ParakeetState.Ready -> null
                else -> inHand { it == EngineOption.PARAKEET }
            }
            whisperId != null -> forWhisper(whisperId)
            else -> null
        }
    }

    private fun forWhisper(modelId: String): ModelDownload? = when (val state = whisper.states.value[modelId]) {
        is SlotState.Busy -> ModelDownload.Running(state.received, state.total ?: 0)
        is SlotState.Ready -> null
        else -> inHand { it.whisperModelId == modelId }
    }

    /** What a download in hand that does not run is waiting for. */
    private fun inHand(isIt: (EngineOption) -> Boolean): ModelDownload? = when {
        _waiting.value?.let(isIt) == true -> ModelDownload.WaitingForWifi
        retrying?.let(isIt) == true -> ModelDownload.Retrying
        else -> null
    }

    companion object {
        private const val TAG = "Amber"

        /** Stands for "still running" among a try's ends. */
        private val BUSY = Any()

        /** Whether [config] runs on a model Amber downloads - the only engines a download holds up. */
        fun downloadsModel(config: EngineConfig): Boolean =
            config.kind == EngineKind.LOCAL_SHERPA || config.kind == EngineKind.WHISPER_CPP
    }
}
