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
import app.amethyst.core.onboarding.FirstRunWatch
import app.amethyst.core.onboarding.ModelFetch
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.data.PhoneLocales
import app.amethyst.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What a new install is given without being asked ([FirstRun]), done with what only the phone
 * knows: its memory and CPU, the apps on it, the models already there. And the one thing that
 * outlives the introduction - the model still to be fetched ([FirstRunWatch]): its download is
 * taken up again with every start of the process (on Wi-Fi, or held for it), whether its
 * recognition waits behind Android's or was made active at once.
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

    /**
     * The quick setup's choices for this phone - the recognition, the app styles; the watch
     * below starts its download. With [finish] the introduction is over too (Skip). Nothing
     * on a replay.
     */
    fun quick(finish: Boolean) {
        scope.launch {
            if (!FirstRun.applies(loaded.filterNotNull().first())) return@launch
            val (plan, installed) = withContext(Dispatchers.IO) {
                FirstRun.plan(phoneFacts(context)) { !downloads().needsDownload(it) } to installed()
            }
            var after: AmethystSettings? = null
            store.update { current -> FirstRun.quick(current, plan, installed, PhoneLocales.tags(), finish).also { after = it } }
            Log.i(TAG, "first run: ${plan.now.name} now, ${plan.download?.name ?: "nothing"} to fetch, ${after?.profiles?.size ?: 0} app style(s)")
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

    /** The wanted model's download once more - over mobile data too, if the user said so. */
    fun download(mobileData: Boolean) {
        scope.launch {
            FirstRun.wanted(loaded.filterNotNull().first())?.let { fetch(it, mobileData) }
        }
    }

    private fun installed(): Set<String> = launcher.appsBlocking().mapTo(HashSet()) { it.packageName }

    /** Starts [option]'s download, or holds it for Wi-Fi where the gate would ask. */
    private suspend fun fetch(option: EngineOption, mobileData: Boolean) = withContext(Dispatchers.Main) {
        if (downloads().start(option, mobileData) == GateDecision.ASK) downloads().waitForWifi(option)
    }

    /** The model downloads as the watch in `:core` sees them: started on Wi-Fi, or held for it. */
    private val fetching = object : ModelFetch {
        override fun ready(option: EngineOption): Flow<Boolean> = downloads().ready(option)

        override suspend fun fetch(option: EngineOption) {
            // One that runs or is held already - started by hand, perhaps over mobile data - is left alone.
            val inHand = withContext(Dispatchers.Main) { downloads().forOption(option) != null }
            if (!inHand) this@FirstRunSetup.fetch(option, mobileData = false)
        }

        override suspend fun letGo(option: EngineOption) = withContext(Dispatchers.Main) { downloads().letGo(option) }
    }

    private val watch = FirstRunWatch(loaded.filterNotNull(), store::update, fetching, PhoneLocales::tags) { Log.i(TAG, it) }

    init {
        scope.launch { watch.run() }
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
