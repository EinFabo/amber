package app.amethyst.accessibility

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.amethyst.Graph
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
            toast("Too late - the dictation has moved on.")
            return false
        }
        val dictation = graph.dictation
        val app = otherApp(currentApp)
        // Each says what happened; copy, the cleanup switch and hiding say it themselves.
        return when (action) {
            QuickAction.INSERT_AGAIN -> dictation.insertAgain().also { if (it) toast("Last dictation inserted.") }
            QuickAction.COPY_LAST -> dictation.copyLast()
            QuickAction.CLEANUP_OFF_HERE -> app != null && cleanupHere(app, on = false)
            QuickAction.CLEANUP_ON_HERE -> app != null && cleanupHere(app, on = true)
            QuickAction.HIDE_HERE -> app?.let { hideIn(it); true } ?: false
            QuickAction.CANCEL -> done("Dictation cancelled.") { dictation.cancel() }
            QuickAction.SEND_WITHOUT_CLEANUP -> done("Sending without the cleanup.") { dictation.sendWithoutCleanup() }
            QuickAction.START_OVER -> done("Recording again from the start.") { dictation.startOver() }
            QuickAction.KEEP_AS_RECOGNIZED -> done("Keeping the text as recognized.") { dictation.keepAsRecognized() }
        }
    }

    private fun done(message: String, action: () -> Unit): Boolean {
        action()
        toast(message)
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
        toast(if (on) "Cleanup on in $label." else "Cleanup off in $label.")
        return true
    }

    private fun labelOf(app: String): String = runCatching {
        service.packageManager.getApplicationInfo(app, 0).loadLabel(service.packageManager).toString()
    }.getOrNull() ?: app

    private fun toast(message: String) {
        main.post { Toast.makeText(service, message, Toast.LENGTH_SHORT).show() }
    }
}
