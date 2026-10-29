package app.amethyst.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.widget.Toast
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
import app.amethyst.ui.MainActivity
import app.amethyst.core.bubble.Box
import app.amethyst.core.bubble.TextFieldKind
import app.amethyst.core.insert.InputTarget
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
            onHideIn = { pkg ->
                graph.appScope.launch {
                    graph.settingsStore.update {
                        it.copy(bubble = it.bubble.copy(hiddenIn = (it.bubble.hiddenIn + pkg).distinct()))
                    }
                }
                val name = runCatching { packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager) }.getOrNull() ?: pkg
                Toast.makeText(this, "Bubble hidden in $name. Show it again in Amber → Settings → Bubble.", Toast.LENGTH_LONG).show()
            },
        )

        keyboard = KeyboardTracker(this, graph.keyboardLog) { top, floating ->
            bubble?.setKeyboard(top, floating)
            // Apps move their fields when the keyboard comes or goes; look once they have.
            fieldCheck.schedule(FIELD_SETTLE_MS)
        }

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
                    // the app the user is in. Neither are Amber's own overlays - the hide
                    // target, a toast, the microphone helper: taken for the app in front, they
                    // switched off drag-to-hide for the next drag and let the bubble back into
                    // hidden apps. Only Amber's main screen counts.
                    val ownOverlay = pkg == packageName && event.className?.toString() != MainActivity::class.java.name
                    if (pkg != SYSTEM_UI && !isKeyboard(pkg) && !ownOverlay) bubble?.setCurrentApp(pkg)
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

    /**
     * The field that has input focus, as precisely as Android tells: app, window, and from
     * Android 13 the input method's field id. Not only editable nodes - a terminal view or a
     * web field takes text through the input connection without saying it is editable.
     */
    fun focusedTarget(): InputTarget? = runCatching {
        val editor = startedEditor()
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node != null) {
            val pkg = node.packageName?.toString() ?: return@runCatching null
            val sameEditor = editor?.takeIf { it.packageName == pkg }
            val secret = TextFieldKind.of(node.inputType, node.isPassword) == TextFieldKind.SECRET ||
                sameEditor?.let { TextFieldKind.of(it.inputType) == TextFieldKind.SECRET } == true
            InputTarget(
                packageName = pkg,
                windowId = node.windowId,
                fieldId = sameEditor?.fieldId,
                kind = if (secret) TextFieldKind.SECRET else TextFieldKind.TEXT,
            )
        } else if (editor != null) {
            InputTarget(
                packageName = editor.packageName ?: return@runCatching null,
                windowId = NO_WINDOW,
                fieldId = editor.fieldId,
                kind = TextFieldKind.of(editor.inputType).takeIf { it == TextFieldKind.SECRET } ?: TextFieldKind.TEXT,
            )
        } else {
            null
        }
    }.getOrNull()

    /** The EditorInfo of the field the service's input method is bound to (Android 13+). */
    private fun startedEditor(): EditorInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val method = inputMethod ?: return null
        return if (method.currentInputStarted) method.currentInputEditorInfo else null
    }

    /** Whether the focused node is a password field - Compose and View password fields can carry a plain text input type. */
    private fun focusedIsPassword(): Boolean =
        runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isPassword == true }.getOrDefault(false)

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
        fieldCheck.schedule(0)
        // Any field, a PIN pad too: its keyboard is just as much in the way. Right away, not
        // on the next window event - this is what lets the bubble move with the keyboard.
        keyboard?.focusChanged(kind != TextFieldKind.NONE)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreateInputMethod(): InputMethod = object : InputMethod(this) {
        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
            super.onStartInput(attribute, restarting)
            inputKind = TextFieldKind.of(attribute.inputType, isPassword = focusedIsPassword())
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

    /** Looks up the focused field's bounds, at most once per burst of focus and keyboard changes. */
    private inner class FieldCheck : Runnable {
        private val handler = Handler(Looper.getMainLooper())

        fun schedule(delayMs: Long) {
            handler.removeCallbacks(this)
            handler.postDelayed(this, delayMs)
        }

        fun stop() = handler.removeCallbacks(this)

        override fun run() {
            if (!bubbleShown) return
            val bounds = if (!editableFieldFocused) null else runCatching {
                findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { node -> Rect().also(node::getBoundsInScreen) }
            }.getOrNull()?.takeUnless { it.isEmpty }
            bubble?.setField(bounds?.let { Box(it.left, it.top, it.right, it.bottom) })
        }
    }

    private val fieldCheck = FieldCheck()

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
        fieldCheck.stop()
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

        /** Window id of a target known only through the input connection. */
        const val NO_WINDOW = -1

        /** Long enough for an app to relayout after the keyboard settled. */
        const val FIELD_SETTLE_MS = 150L
    }
}
