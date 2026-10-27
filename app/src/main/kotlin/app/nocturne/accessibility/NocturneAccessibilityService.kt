package app.nocturne.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import app.nocturne.Graph
import app.nocturne.NocturneApp
import app.nocturne.bubble.BubbleController
import app.nocturne.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Hosts the bubble and performs the insertion.
 *
 * Both jobs need an accessibility service, and both are the reason the permission is asked
 * for - the overlay is a TYPE_ACCESSIBILITY_OVERLAY, which avoids a second "draw over other
 * apps" grant, and the insertion needs window content access. No events are inspected beyond
 * which app and field currently have focus.
 */
class NocturneAccessibilityService : AccessibilityService() {

    private lateinit var graph: Graph

    /**
     * Recreated on every connect: onServiceConnected can run again on the same instance
     * (a service-info update, a rebind) and teardown cancels this scope. A cancelled scope
     * would leave the bubble attached but frozen - never rendering state, never reacting to
     * the on/off switch.
     */
    private var scope = newScope()

    lateinit var textInserter: TextInserter
        private set

    private var bubble: BubbleController? = null

    /** Package of the app currently in front, for logs and per-app behaviour. */
    @Volatile
    var foregroundPackage: String? = null
        private set

    @Volatile
    var editableFieldFocused: Boolean = false
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "accessibility service connected")

        if (!scope.isActive) scope = newScope()

        graph = NocturneApp.graph(this)
        textInserter = TextInserter(this, graph.strategyStore)

        // Attaching is left to the settings collector below, so the bubble switch is the
        // single place that decides whether the overlay exists.
        bubble = BubbleController(
            service = this,
            onTap = { graph.dictation.toggle() },
            onLongPress = { openSettings() },
        )

        graph.accessibilityService = this

        scope.launch {
            graph.dictation.state.collect { state -> bubble?.render(state) }
        }
        scope.launch {
            graph.settings.collectLatest { settings ->
                val controller = bubble ?: return@collectLatest
                if (settings.bubble.enabled) {
                    // Settings first: attach() builds the window from them, so applying
                    // afterwards would create it at the default position every time.
                    controller.applySettings(settings.bubble)
                    controller.attach()
                } else {
                    controller.detach()
                }
            }
        }
    }

    override fun onAccessibilityEvent(incoming: AccessibilityEvent?) {
        val event = incoming ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                event.packageName?.toString()
                    ?.takeIf { it != packageName }
                    ?.let { foregroundPackage = it }
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            -> {
                editableFieldFocused = event.source?.isEditable == true
                bubble?.setTextFieldFocused(editableFieldFocused)
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        bubble?.detach()
        bubble = null
        if (::graph.isInitialized && graph.accessibilityService === this) {
            graph.accessibilityService = null
        }
        scope.cancel()
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun openSettings() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    private companion object {
        const val TAG = "Nocturne"
    }
}
