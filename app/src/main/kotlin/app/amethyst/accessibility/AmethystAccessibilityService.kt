package app.amethyst.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.amethyst.core.capture.TriggerSource
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import app.amethyst.Graph
import app.amethyst.AmethystApp
import app.amethyst.bubble.BubbleController
import app.amethyst.core.bubble.TextFieldKind
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
class AmethystAccessibilityService : AccessibilityService() {

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

    private var keyboard: KeyboardTracker? = null

    /** Package of the app currently in front, for logs and per-app behaviour. */
    @Volatile
    var foregroundPackage: String? = null
        private set

    /** A field that takes dictation has focus - not a password, PIN or number field. */
    @Volatile
    var editableFieldFocused: Boolean = false
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "accessibility service connected")

        if (!scope.isActive) scope = newScope()

        graph = AmethystApp.graph(this)
        textInserter = TextInserter(this, graph.strategyStore)

        // Attaching is left to the settings collector below, so the bubble switch is the
        // single place that decides whether the overlay exists.
        bubble = BubbleController(
            service = this,
            onTap = { graph.dictation.toggle() },
            onHoldStart = {
                if (graph.dictation.isRunning) {
                    false
                } else {
                    graph.dictation.start(TriggerSource.BUBBLE, holdToTalk = true)
                    true
                }
            },
            onHoldEnd = { graph.dictation.stop() },
            onMoved = { horizontal, vertical ->
                graph.appScope.launch {
                    graph.settingsStore.update {
                        it.copy(bubble = it.bubble.copy(horizontalBias = horizontal, verticalBias = vertical))
                    }
                }
            },
        )

        keyboard = KeyboardTracker(this, graph.keyboardLog) { top -> bubble?.setKeyboardTop(top) }

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

    /**
     * Kept cheap on purpose: this runs for every focus and window change on the phone. From
     * Android 13 the input method callbacks say whether a field takes text, so no node is
     * fetched here at all; below that, only a focus event pays for one lookup.
     */
    override fun onAccessibilityEvent(incoming: AccessibilityEvent?) {
        val event = incoming ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (bubbleShown) keyboard?.windowsChanged()
                event.packageName?.toString()?.let { pkg ->
                    if (pkg != packageName) foregroundPackage = pkg
                    // The keyboard and the system bars send window changes too; they are not
                    // the app the user is in.
                    if (pkg != SYSTEM_UI && !isKeyboard(pkg)) bubble?.setCurrentApp(pkg)
                }
                // Switching apps or closing a dialog sends no focus event for the field that
                // went away, so look at what has input focus now.
                if (!tracksInputDirectly) updateTextFieldFocus(currentFocusKind())
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED -> if (!tracksInputDirectly) {
                val kind = kindOf(event.source)
                if (kind != TextFieldKind.NONE) event.packageName?.toString()?.let { bubble?.setCurrentApp(it) }
                updateTextFieldFocus(kind)
            }

            // Each one costs a getWindows() call; only the bubble needs the keyboard's edge.
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> if (bubbleShown) keyboard?.windowsChanged()
        }
    }

    private val bubbleShown: Boolean
        get() = graph.settings.value.bubble.enabled

    /** Whether [pkg] is the keyboard in use, e.g. "com.google.android.inputmethod.latin/...". */
    private fun isKeyboard(pkg: String): Boolean = runCatching {
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$pkg/") == true
    }.getOrDefault(false)

    private val tracksInputDirectly: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * The app that owns the field the text will go into: the focused input's package, which
     * can be Amber itself (its test field), else the last app in front.
     */
    fun targetPackage(): String? = focusedInputPackage() ?: foregroundPackage

    /** The package of the node with input focus, if any - the most certain target there is. */
    fun focusedInputPackage(): String? =
        runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.packageName?.toString() }.getOrNull()

    private fun currentFocusKind(): TextFieldKind =
        runCatching { kindOf(findFocus(AccessibilityNodeInfo.FOCUS_INPUT)) }.getOrDefault(TextFieldKind.NONE)

    private fun kindOf(node: AccessibilityNodeInfo?): TextFieldKind =
        if (node?.isEditable != true) TextFieldKind.NONE else TextFieldKind.of(node.inputType, node.isPassword)

    /**
     * From Android 13 the service's input method hears when a field starts and stops taking
     * text - the same signal that makes a keyboard slide in, with the field's input type.
     * That is a better answer to "is the bubble needed right now" than focus events, which
     * some apps never send.
     */
    @Volatile
    private var inputKind = TextFieldKind.NONE

    private fun updateTextFieldFocus(focusedKind: TextFieldKind) {
        val kind = if (inputKind != TextFieldKind.NONE) inputKind else focusedKind
        val dictatable = kind.wantsDictation
        if (dictatable && !editableFieldFocused) graph.textFieldFocused()
        editableFieldFocused = dictatable
        bubble?.setTextFieldFocused(dictatable)
        // Any field, a PIN pad too: its keyboard is just as much in the way. Right away, not
        // on the next window event - this is what lets the bubble move with the keyboard.
        keyboard?.focusChanged(kind != TextFieldKind.NONE)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreateInputMethod(): InputMethod = object : InputMethod(this) {
        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
            super.onStartInput(attribute, restarting)
            inputKind = TextFieldKind.of(attribute.inputType)
            // The field's own app: the surest answer to "where is the user typing".
            if (inputKind != TextFieldKind.NONE) attribute.packageName?.let { bubble?.setCurrentApp(it) }
            updateTextFieldFocus(TextFieldKind.NONE)
        }

        override fun onFinishInput() {
            super.onFinishInput()
            inputKind = TextFieldKind.NONE
            updateTextFieldFocus(TextFieldKind.NONE)
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
        keyboard?.stop()
        keyboard = null
        bubble?.detach()
        bubble = null
        if (::graph.isInitialized && graph.accessibilityService === this) {
            graph.accessibilityService = null
        }
        scope.cancel()
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)


    private companion object {
        const val TAG = "Amber"
        const val SYSTEM_UI = "com.android.systemui"
    }
}
