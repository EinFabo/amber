package app.amethyst.accessibility

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import app.amethyst.Graph
import app.amethyst.R
import app.amethyst.Texts
import app.amethyst.core.bubble.BubbleEvent
import app.amethyst.core.bubble.QuickAction
import app.amethyst.core.bubble.QuickActions
import app.amethyst.core.bubble.QuickMenuContext
import app.amethyst.core.bubble.QuickMenuLayout
import app.amethyst.core.bubble.QuickPhase
import app.amethyst.core.bubble.QuickSlot
import app.amethyst.core.commands.RunMode
import app.amethyst.core.engine.CleanupRouting
import app.amethyst.core.settings.AppProfiles
import app.amethyst.core.settings.WritingStyle
import app.amethyst.dictation.DictationState
import app.amethyst.ui.MainActivity
import app.amethyst.ui.QuickStyleActivity
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

    /** The idle places as the user put them together (Settings -> Bubble -> Quick menu), else today's. */
    fun slots(currentApp: String?): List<QuickSlot?> =
        QuickActions.slots(context(currentApp), QuickMenuLayout.idle(graph.settings.value.bubble.quickMenuIdle))

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
            QuickAction.UNDO_LAST -> dictation.undoLast()
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
            QuickAction.OPEN_AMBER -> open(MainActivity.intent(service))
            QuickAction.STYLE_HERE -> app != null && withProfile(app) { open(QuickStyleActivity.intent(service, app)) }
            QuickAction.NEXT_STYLE -> app != null && nextStyle(app)
            QuickAction.APP_PAGE -> app != null && withProfile(app) { open(MainActivity.intent(service, appPage = app)) }
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
            canUndo = graph.dictation.lastInsertion?.packageName.let { it != null && it == service.targetPackage() },
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

    /**
     * [then] once [app] has a profile - made as the cleanup switch makes one, under its label in
     * the style as spoken - so the window or page it opens has something to show.
     */
    private fun withProfile(app: String, then: () -> Boolean): Boolean {
        val label = labelOf(app)
        graph.appScope.launch {
            graph.settingsStore.update { it.copy(profiles = AppProfiles.ensure(it.profiles, app, label)) }
            main.post { then() }
        }
        return true
    }

    /** The app's style one step on; the toast names the new one, and says so where no cleanup would apply it. */
    private fun nextStyle(app: String): Boolean {
        val label = labelOf(app)
        val hasKey = { alias: String? -> graph.secretStore.has(alias) }
        graph.appScope.launch {
            var style: WritingStyle? = null
            graph.settingsStore.update { settings ->
                val profiles = AppProfiles.withNextStyle(settings.profiles, app, label)
                style = AppProfiles.forPackage(profiles, app)?.style
                settings.copy(profiles = profiles)
            }
            val now = style ?: return@launch
            val runs = QuickActions.cleanupHere(graph.settings.value, app, hasKey).runs
            val name = Texts.writingStyle(service, now)
            toast(service.getString(if (runs) R.string.quick_done_style else R.string.quick_done_style_no_cleanup, label, name))
        }
        return true
    }

    /** Starts one of Amber's screens over the app in front; the accessibility service may, from the background. */
    private fun open(intent: Intent): Boolean {
        val opened = runCatching { service.startActivity(intent) }
            .onFailure { Log.w(TAG, "could not open ${intent.component?.shortClassName}", it) }
            .isSuccess
        if (!opened) toast(service.getString(R.string.quick_open_failed))
        return opened
    }

    private fun labelOf(app: String): String = runCatching {
        service.packageManager.getApplicationInfo(app, 0).loadLabel(service.packageManager).toString()
    }.getOrNull() ?: app

    private fun toast(message: String) {
        main.post { Toast.makeText(service, message, Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        const val TAG = "Amber"
    }
}
