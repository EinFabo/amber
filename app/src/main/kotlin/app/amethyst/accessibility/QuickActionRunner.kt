package app.amethyst.accessibility

import app.amethyst.R
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.amethyst.Graph
import app.amethyst.core.bubble.BubbleEvent
import app.amethyst.core.bubble.QuickAction
import app.amethyst.core.bubble.QuickActions
import app.amethyst.core.bubble.QuickMenuContext
import app.amethyst.core.bubble.QuickPhase
import app.amethyst.core.bubble.QuickSlot
import app.amethyst.core.commands.RunMode
import app.amethyst.core.engine.CleanupRouting
import app.amethyst.core.settings.AppProfiles
import app.amethyst.dictation.DictationState
import kotlinx.coroutines.launch

/**
 * The bubble's quick menu on the dictation side: what each slot offers right now
 * ([QuickActions]) and running the chosen action. [currentApp] is the app the bubble sees in
 * front - the one drag-to-hide uses too.
 */
class QuickActionRunner(
    private val service: AmethystAccessibilityService,
    private val graph: Graph,
    /** Hides the bubble in an app, as a drop on the hide target does. */
    private val hideIn: (packageName: String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    fun slots(currentApp: String?): List<QuickSlot?> = QuickActions.slots(context(currentApp))

    /** False when the action could not run - the dictation moved on, or there was nothing to do. */
    fun run(action: QuickAction, currentApp: String?): Boolean {
        if (!QuickActions.stillValid(action, phase())) {
            toast(service.getString(R.string.quick_too_late))
            return false
        }
        val dictation = graph.dictation
        val app = otherApp(currentApp)
        // Each says what happened; copy, the cleanup switch and hiding say it themselves.
        val ran = when (action) {
            QuickAction.INSERT_AGAIN -> dictation.insertAgain().also { if (it) toast(service.getString(R.string.quick_done_inserted)) }
            QuickAction.COPY_LAST -> dictation.copyLast()
            QuickAction.CLEANUP_OFF_HERE -> app != null && cleanupHere(app, on = false)
            QuickAction.CLEANUP_ON_HERE -> app != null && cleanupHere(app, on = true)
            QuickAction.HIDE_HERE -> app?.let { hideIn(it); true } ?: false
            QuickAction.CANCEL -> done(R.string.quick_done_cancelled) { dictation.cancel() }
            QuickAction.SEND_WITHOUT_CLEANUP -> done(R.string.quick_done_no_cleanup) { dictation.sendWithoutCleanup() }
            QuickAction.START_OVER -> done(R.string.quick_done_start_over) { dictation.startOver() }
            QuickAction.KEEP_AS_RECOGNIZED -> done(R.string.quick_done_as_recognized) { dictation.keepAsRecognized() }
            QuickAction.PAUSE -> dictation.pause().also { if (it) toast(service.getString(R.string.quick_done_paused)) }
            QuickAction.RESUME -> dictation.resume().also { if (it) toast(service.getString(R.string.quick_done_resumed)) }
        }
        if (ran) graph.bubbleEvents.tryEmit(BubbleEvent.QuickActionRun(action))
        return ran
    }

    private fun done(message: Int, action: () -> Unit): Boolean {
        action()
        toast(service.getString(message))
        return true
    }

    private fun context(currentApp: String?): QuickMenuContext {
        val settings = graph.settings.value
        val app = otherApp(currentApp)
        val hasKey = { alias: String? -> graph.secretStore.has(alias) }
        return QuickMenuContext(
            phase = phase(),
            hasLastDictation = graph.dictationLog.lastDictation != null,
            fieldFocused = service.editableFieldFocused,
            otherAppInFront = app != null,
            cleanupHere = QuickActions.cleanupHere(settings, app, hasKey),
            cleanupPlanned = CleanupRouting.decide(settings, service.targetPackage(), fellBackFrom = null, hasKey = hasKey).choice != null,
            command = graph.dictation.mode.value == RunMode.COMMAND,
            canPause = graph.dictation.canPause,
            paused = (graph.dictation.state.value as? DictationState.Listening)?.paused == true,
        )
    }

    private fun phase(): QuickPhase = when (graph.dictation.state.value) {
        DictationState.Starting, is DictationState.Listening -> QuickPhase.RECORDING
        is DictationState.Transcribing -> QuickPhase.WORKING
        DictationState.Idle, is DictationState.Done, is DictationState.Commanded, is DictationState.Failed -> QuickPhase.IDLE
    }

    private fun otherApp(currentApp: String?): String? = currentApp?.takeIf { it != service.packageName }

    private fun cleanupHere(app: String, on: Boolean): Boolean {
        val label = labelOf(app)
        graph.appScope.launch {
            graph.settingsStore.update { it.copy(profiles = AppProfiles.withCleanupHere(it.profiles, app, label, on)) }
        }
        toast(service.getString(if (on) R.string.quick_done_cleanup_on else R.string.quick_done_cleanup_off, label))
        return true
    }

    private fun labelOf(app: String): String = runCatching {
        service.packageManager.getApplicationInfo(app, 0).loadLabel(service.packageManager).toString()
    }.getOrNull() ?: app

    private fun toast(message: String) {
        main.post { Toast.makeText(service, message, Toast.LENGTH_SHORT).show() }
    }
}
