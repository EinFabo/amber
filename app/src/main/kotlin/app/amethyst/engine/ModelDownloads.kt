package app.amethyst.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import app.amethyst.core.engine.DownloadGate
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.EngineOption
import app.amethyst.core.engine.GateDecision
import app.amethyst.core.engine.ModelDownload
import app.amethyst.core.engine.whisper.ModelSlots.SlotState
import app.amethyst.core.engine.whisper.WhisperModels
import app.amethyst.core.settings.EngineConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The introduction's downloads: started at once on Wi-Fi, over mobile data only when the user
 * said so, or held until an unmetered network appears. Also answers whether the active
 * engine is still downloading, so a dictation can wait instead of failing.
 */
class ModelDownloads(
    context: Context,
    private val parakeet: ParakeetModelStore,
    private val whisper: WhisperModelStore,
) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val _waiting = MutableStateFlow<EngineOption?>(null)

    /** The download held for Wi-Fi, if any. */
    val waiting: StateFlow<EngineOption?> = _waiting

    private var callback: ConnectivityManager.NetworkCallback? = null

    /** True without a network too: then nothing should start over what might be mobile data. */
    fun metered(): Boolean = connectivity?.isActiveNetworkMetered ?: true

    /**
     * Starts [option]'s download, or holds it for Wi-Fi when the network is metered and the user
     * has not allowed mobile data. ASK leaves the choice to the caller's buttons.
     */
    fun start(option: EngineOption, mobileDataAllowed: Boolean): GateDecision {
        val decision = DownloadGate.decide(metered(), mobileDataAllowed, waitingForWifi = _waiting.value == option, needed = needsDownload(option))
        when (decision) {
            GateDecision.START -> {
                stopWaiting()
                launch(option)
            }
            GateDecision.WAIT -> waitForWifi(option)
            GateDecision.ASK -> Unit
            // The engine is ready (or needs nothing): a wait held for another one is moot now.
            GateDecision.NONE -> stopWaiting()
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

    /** Holds [option] until an unmetered network appears - while Amber's process lives. */
    fun waitForWifi(option: EngineOption) {
        stopWaiting()
        if (!needsDownload(option)) return
        _waiting.value = option
        val manager = connectivity ?: return
        val watch = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return
                if (_waiting.compareAndSet(option, null)) {
                    Log.i(TAG, "unmetered network - starting the held ${option.name} download")
                    stopWaiting()
                    launch(option)
                }
            }
        }
        callback = watch
        runCatching { manager.registerDefaultNetworkCallback(watch) }
            .onFailure { Log.w(TAG, "cannot watch the network: ${it.message}") }
    }

    private fun stopWaiting() {
        _waiting.value = null
        callback?.let { watch -> runCatching { connectivity?.unregisterNetworkCallback(watch) } }
        callback = null
    }

    private fun launch(option: EngineOption) {
        when {
            option == EngineOption.PARAKEET -> parakeet.download()
            option.whisperModelId != null -> WhisperModels.byId(option.whisperModelId)?.let(whisper::download)
            else -> Unit
        }
    }

    /** The download the [config] engine is waiting for, or null when it is not downloading. */
    fun forEngine(config: EngineConfig): ModelDownload? = when (config.kind) {
        // A model on the phone wins over a wait held for it - it was fetched some other way.
        EngineKind.LOCAL_SHERPA -> when (val state = parakeet.state.value) {
            is ParakeetState.Busy -> ModelDownload.Running(state.received, state.total)
            is ParakeetState.Ready -> null
            else -> ModelDownload.WaitingForWifi.takeIf { _waiting.value == EngineOption.PARAKEET }
        }
        EngineKind.WHISPER_CPP -> {
            val modelId = config.modelId ?: WhisperModels.default.id
            when (val state = whisper.states.value[modelId]) {
                is SlotState.Busy -> ModelDownload.Running(state.received, state.total ?: 0)
                is SlotState.Ready -> null
                else -> ModelDownload.WaitingForWifi.takeIf { _waiting.value?.whisperModelId == modelId }
            }
        }
        else -> null
    }

    companion object {
        private const val TAG = "Amber"

        /** Whether [config] runs on a model Amber downloads - the only engines a download holds up. */
        fun downloadsModel(config: EngineConfig): Boolean =
            config.kind == EngineKind.LOCAL_SHERPA || config.kind == EngineKind.WHISPER_CPP
    }
}
