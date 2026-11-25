package app.amethyst.engine

import android.app.ActivityManager
import android.content.Context
import android.speech.SpeechRecognizer
import android.util.Log
import app.amethyst.apps.LauncherList
import app.amethyst.core.engine.EngineOption
import app.amethyst.core.engine.GateDecision
import app.amethyst.core.engine.LanguagePack
import app.amethyst.core.engine.PhoneFacts
import app.amethyst.core.onboarding.FirstRun
import app.amethyst.core.onboarding.FirstRunWatch
import app.amethyst.core.onboarding.LanguagePackAnswer
import app.amethyst.core.onboarding.ModelFetch
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.data.PhoneLocales
import app.amethyst.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * What a new install is given without being asked ([FirstRun]), done with what only the phone
 * knows: its memory and CPU, the apps on it, the models already there. And the one thing that
 * outlives the introduction - the models still to be fetched ([FirstRunWatch]): each download is
 * taken up again with every start of the process (on Wi-Fi, or held for it; the small model that
 * stands in on any network), whether its recognition waits behind the small model or was made
 * active at once.
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
            val fetch = plan.downloads.joinToString(" then ") { it.name }.ifEmpty { "nothing" }
            Log.i(TAG, "first run: ${plan.now.name} now, ${plan.later?.name ?: "nothing"} later, $fetch to fetch, ${after?.profiles?.size ?: 0} app style(s)")
            // Android's recognition, picked where no model fits, fails every dictation without
            // the offline pack for its language: asked for at once, while the user is here.
            val picked = after ?: return@launch
            val pack = checkLanguagePack(picked)
            if (FirstRun.fetchesLanguagePack(plan, pack)) {
                val tag = FirstRun.languagePackTag(picked, phoneTag()) ?: return@launch
                val asked = withContext(Dispatchers.Main) { AndroidOnDeviceEngine.requestLanguageDownload(context, tag) }
                Log.i(TAG, "first run: Android's language pack for $tag is missing - ${if (asked) "its download asked for" else "Android refused to fetch it"}")
            }
        }
    }

    private val _languagePack = MutableStateFlow<LanguagePackAnswer?>(null)

    /**
     * What Android said last about the offline pack of the language its recognition hears, for
     * the introduction's last steps ([FirstRun.languagePack] tells whether it still counts).
     */
    val languagePack: StateFlow<LanguagePackAnswer?> = _languagePack.asStateFlow()

    /**
     * Asks Android again about the active recognition's language pack ([languagePack]) - once
     * its download dialog is closed, while the pack downloads. The pack, or null where the
     * active recognition is not Android's on-device one or Android does not say.
     */
    suspend fun checkLanguagePack(): LanguagePack? = checkLanguagePack(loaded.filterNotNull().first())

    private suspend fun checkLanguagePack(settings: AmethystSettings): LanguagePack? {
        val tag = FirstRun.languagePackTag(settings, phoneTag()) ?: return null
        val pack = AndroidOnDeviceEngine.languagePack(context, tag) ?: return null
        if (_languagePack.value?.pack != pack) Log.i(TAG, "Android's language pack for $tag: $pack")
        _languagePack.value = LanguagePackAnswer(tag, pack)
        return pack
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

    /** The wanted model's download once more - over mobile data too, if the user said so, or if it is the small one. */
    fun download(mobileData: Boolean) {
        scope.launch {
            val current = loaded.filterNotNull().first()
            FirstRun.wanted(current)?.let { fetch(it, mobileData || FirstRun.anyNetwork(current, it)) }
        }
    }

    private fun installed(): Set<String> = launcher.appsBlocking().mapTo(HashSet()) { it.packageName }

    /** The user's start of [option]'s download; held for Wi-Fi where the gate would ask. */
    private suspend fun fetch(option: EngineOption, mobileData: Boolean) = withContext(Dispatchers.Main) {
        if (downloads().start(option, mobileData) == GateDecision.ASK) downloads().waitForWifi(option)
    }

    /** The model downloads as the watch in `:core` sees them: started on Wi-Fi, or held for it. */
    private val fetching = object : ModelFetch {
        override fun ready(option: EngineOption): Flow<Boolean> = downloads().ready(option)

        // One that runs or is held already - started by hand, perhaps over mobile data - is left alone.
        override suspend fun fetch(option: EngineOption, mobileData: Boolean) = withContext(Dispatchers.Main) { downloads().resume(option, mobileData) }

        override suspend fun letGo(option: EngineOption) = withContext(Dispatchers.Main) { downloads().letGo(option) }

        override suspend fun delete(option: EngineOption) = withContext(Dispatchers.Main) { downloads().delete(option) }
    }

    private val watch = FirstRunWatch(loaded.filterNotNull(), store::update, fetching, PhoneLocales::tags) { Log.i(TAG, it) }

    init {
        scope.launch { watch.run() }
    }

    companion object {
        private const val TAG = "Embersay"

        /** The language Android's recognition hears where none is set for it ([AndroidOnDeviceEngine]). */
        fun phoneTag(): String = Locale.getDefault().toLanguageTag()

        /** Whether the phone has a speech recognizer of Android's at all; a de-Googled one comes without. */
        fun androidRecognizer(context: Context): Boolean =
            runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

        /**
         * What is weighed to suggest a recognition: memory, room for models, the CPU, Android's
         * own recognizer. Off the main thread - it loads the Whisper library to ask about the CPU.
         */
        fun phoneFacts(context: Context): PhoneFacts {
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
            // Where the stores put models (noBackupFilesDir/models), so the free space is theirs.
            val models = ModelRoom(context, File(context.noBackupFilesDir, "models"))
            return PhoneFacts(
                totalRamMb = memory.totalMem / 1_000_000,
                freeStorageMb = models.available() / 1_000_000,
                whisperCpu = WhisperNative.cpuSupported(),
                androidOnDevice = AndroidOnDeviceEngine.onDeviceSpeech,
                androidRecognizer = androidRecognizer(context),
            )
        }
    }
}
