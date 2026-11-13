package app.amethyst.engine

import android.app.ActivityManager
import android.content.Context
import android.speech.SpeechRecognizer
import android.util.Log
import app.amethyst.apps.LauncherList
import app.amethyst.core.engine.EngineOption
import app.amethyst.core.engine.GateDecision
import app.amethyst.core.engine.PhoneFacts
import app.amethyst.core.onboarding.FirstRun
import app.amethyst.core.onboarding.PendingVerdict
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.data.PhoneLocales
import app.amethyst.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What a new install is given without being asked ([FirstRun]), done with what only the phone
 * knows: its memory and CPU, the apps on it, the models already there. And the one thing that
 * outlives the introduction - the recognition that waits for its model: its download is taken
 * up again with every start of the process (on Wi-Fi, or held for it), and when the model is
 * there it takes over from Android's recognition by itself.
 *
 * In the app's scope, not a screen's: Skip leaves the introduction the moment it is tapped.
 */
class FirstRunSetup(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: SettingsStore,
    private val loaded: StateFlow<AmethystSettings?>,
    /** Asked for only when something has to be fetched: the stores read every model file when built. */
    private val downloads: () -> ModelDownloads,
    private val launcher: LauncherList,
) {

    init {
        scope.launch { watchPending() }
    }

    /**
     * The quick setup's choices for this phone - the recognition, the app styles - and its
     * download started. With [finish] the introduction is over too (Skip). Nothing on a replay.
     */
    fun quick(finish: Boolean) {
        scope.launch {
            if (!FirstRun.applies(loaded.filterNotNull().first())) return@launch
            val (plan, installed) = withContext(Dispatchers.IO) {
                FirstRun.plan(phoneFacts(context)) { !downloads().needsDownload(it) } to installed()
            }
            var after: AmethystSettings? = null
            store.update { current -> FirstRun.quick(current, plan, installed, PhoneLocales.tags(), finish).also { after = it } }
            Log.i(TAG, "first run: ${plan.now.name} now, ${plan.later?.name ?: "nothing"} waiting, ${after?.profiles?.size ?: 0} app style(s)")
            // A recognition that waits is fetched by the watch below; one made active at once, here.
            val active = plan.now.takeIf { plan.later == null && it.downloadBytes > 0 }
            if (active != null && after?.let { FirstRun.stillWanted(it, active) } == true) fetch(active, mobileData = false)
        }
    }

    /** Styles for the known apps on this phone, for the way through the introduction that asks everything. */
    fun appStyles() {
        scope.launch {
            val current = loaded.filterNotNull().first()
            if (!FirstRun.applies(current) || current.firstRun.appStylesGiven) return@launch
            val installed = withContext(Dispatchers.IO) { installed() }
            store.update { FirstRun.appStyles(it, installed) }
        }
    }

    /** The waiting recognition's download once more - over mobile data too, if the user said so. */
    fun download(mobileData: Boolean) {
        scope.launch {
            FirstRun.pending(loaded.filterNotNull().first())?.let { fetch(it, mobileData) }
        }
    }

    private fun installed(): Set<String> = launcher.appsBlocking().mapTo(HashSet()) { it.packageName }

    /** Starts [option]'s download, or holds it for Wi-Fi where the gate would ask. */
    private suspend fun fetch(option: EngineOption, mobileData: Boolean) = withContext(Dispatchers.Main) {
        if (downloads().start(option, mobileData) == GateDecision.ASK) downloads().waitForWifi(option)
    }

    private suspend fun watchPending() {
        val settings = loaded.filterNotNull()
        settings.map { it.firstRun.pendingEngine }.distinctUntilChanged().collect { name ->
            // One at a time, to the end: the settling below changes the very value collected here.
            if (name == null) return@collect
            val option = FirstRun.pending(settings.first())
            val ready = if (option != null) {
                fetch(option, mobileData = false)
                // Until the model is there, or the user chose a recognition of their own.
                combine(settings, downloads().ready(option)) { current, there -> there.takeIf { FirstRun.verdict(current, it) != PendingVerdict.WAIT } }
                    .filterNotNull().first()
            } else {
                false
            }
            var after: AmethystSettings? = null
            store.update { current -> FirstRun.settle(current, ready, PhoneLocales.tags()).also { after = it } }
            Log.i(TAG, "first run: $name ${if (after?.let { FirstRun.pending(it) } == null) "settled" else "still waiting"}, active ${after?.activeEngineId}")
            // A download held for Wi-Fi that nobody wants any more is let go.
            if (option != null && after?.let { FirstRun.stillWanted(it, option) } == false) {
                withContext(Dispatchers.Main) { downloads().cancelWait(option) }
            }
        }
    }

    companion object {
        private const val TAG = "Amber"

        /**
         * What is weighed to suggest a recognition: memory, room for models, the CPU, Android's
         * own recognizer. Off the main thread - it loads the Whisper library to ask about the CPU.
         */
        fun phoneFacts(context: Context): PhoneFacts {
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
            // Where the stores put models (noBackupFilesDir/models), so the free space is theirs.
            val models = File(context.noBackupFilesDir, "models").apply { mkdirs() }
            return PhoneFacts(
                totalRamMb = memory.totalMem / 1_000_000,
                freeStorageMb = models.usableSpace / 1_000_000,
                whisperCpu = WhisperNative.cpuSupported(),
                androidOnDevice = AndroidOnDeviceEngine.onDeviceSpeech,
                androidRecognizer = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false),
            )
        }
    }
}
