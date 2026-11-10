package app.amethyst.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.amethyst.AmethystApp
import app.amethyst.Graph
import app.amethyst.R
import app.amethyst.bubble.BubbleController
import app.amethyst.core.bubble.Box
import app.amethyst.core.bubble.BubbleEvent
import app.amethyst.core.bubble.TextFieldKind
import app.amethyst.core.commands.RunMode
import app.amethyst.core.insert.InputTarget
import app.amethyst.dictation.DictationState
import app.amethyst.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
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

    /** Carries out voice commands; refuses everything while they are switched off. */
    lateinit var commands: CommandExecutor

    /** Edits the field with the cursor: for "Undo last", and for the text commands. */
    lateinit var fieldEditor: FieldEditor
        private set

    private var bubble: BubbleController? = null

    private var keyboard: KeyboardTracker? = null

    /** The screen stays on from the start of a dictation until its text went in, or failed. */
    private val screenAwake by lazy { ScreenAwake(this) }

    /** The keyboard in use, kept current by an observer: asked on every window change. */
    private val defaultKeyboard by lazy { DefaultKeyboard(contentResolver) }

    /** Package of the app currently in front, for logs and per-app behaviour. */
    @Volatile
    var foregroundPackage: String? = null
        private set

    /**
     * The app the user is in: like [foregroundPackage], but never the keyboard, the system bars
     * or one of Amber's overlays. A voice command checks with it that an app it opened came up.
     */
    @Volatile
    var appInFront: String? = null
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
        fieldEditor = FieldEditor(this, textInserter)
        commands = CommandExecutor(this, graph)

        val quickActions = QuickActionRunner(this, graph, ::hideBubbleIn)
        // Attaching is left to the settings collector below, so the bubble switch is the
        // single place that decides whether the overlay exists.
        bubble = BubbleController(
            service = this,
            onTap = { graph.dictation.toggle() },
            onDoubleTap = { graph.dictation.commandInstead() },
            quickMenu = quickActions::slots,
            onQuickAction = quickActions::run,
            onMoved = { side, edgeY, aboveKeyboardDp ->
                graph.bubbleEvents.tryEmit(BubbleEvent.Docked(side))
                graph.appScope.launch {
                    graph.settingsStore.update {
                        it.copy(
                            bubble = it.bubble.copy(
                                side = side,
                                edgeY = edgeY ?: it.bubble.edgeY,
                                aboveKeyboardDp = aboveKeyboardDp ?: it.bubble.aboveKeyboardDp,
                            ),
                        )
                    }
                }
            },
            onHideIn = ::hideBubbleIn,
        )

        defaultKeyboard.start()
        keyboard = KeyboardTracker(this, graph.keyboardLog, defaultKeyboard::packageName) { top, floating ->
            bubble?.setKeyboard(top, floating)
            // Apps move their fields when the keyboard comes or goes; look once they have.
            fieldCheck.schedule(FIELD_SETTLE_MS)
        }

        // The lock screen gets no bubble: told when the screen goes off, comes on and is unlocked.
        val lockEvents = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // Exported, or the unlock never arrives and the bubble stays away: USER_PRESENT comes from
        // SystemUI, which has an app's uid, not the system's. All three are protected broadcasts,
        // so no other app can send them.
        ContextCompat.registerReceiver(this, lockWatch, lockEvents, ContextCompat.RECEIVER_EXPORTED)
        lockWatchRegistered = true
        bubble?.setLocked(isLocked())

        graph.accessibilityService = this
        // The first tap after a process start should not wait for the keystore, the HTTP client
        // and the engine factory to be built: they are, now, on the IO pool.
        graph.warmUp()
        // The GitHub build asks for a new release once a day, at a moment like this one.
        graph.updates.checkIfDue()
        // Switched on just now from Amber's setup: back to Amber, without the user pressing Back.
        graph.returnToAmber.serviceConnected(this)

        scope.launch {
            graph.dictation.state.collect { state -> bubble?.render(state) }
        }
        scope.launch {
            graph.dictation.state
                .map { it is DictationState.Starting || it is DictationState.Listening || it is DictationState.Transcribing }
                .distinctUntilChanged()
                .collect(screenAwake::hold)
        }
        scope.launch {
            combine(graph.dictation.state, graph.dictation.mode) { state, mode ->
                mode == RunMode.COMMAND && state != DictationState.Idle
            }.collect { command -> bubble?.setCommand(command) }
        }
        scope.launch {
            graph.settings.collectLatest { settings ->
                val controller = bubble ?: return@collectLatest
                if (settings.bubble.enabled) {
                    // Settings first: attach() builds the window from them, so applying
                    // afterwards would create it at the default position every time.
                    controller.applySettings(settings.bubble, commands = settings.commandsEnabled)
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
                val fromKeyboard = event.packageName?.toString()?.let(::isKeyboard) == true
                if (bubbleShown) keyboard?.windowsChanged(graph.dictation.isRunning, bubbleOnScreen, fromKeyboardApp = fromKeyboard)
                event.packageName?.toString()?.let { pkg ->
                    if (pkg != packageName) foregroundPackage = pkg
                    // The keyboard and the system bars send window changes too; they are not
                    // the app the user is in. Neither are Amber's own overlays - the hide
                    // target, a toast, the microphone helper: taken for the app in front, they
                    // switched off drag-to-hide for the next drag and let the bubble back into
                    // hidden apps. Only Amber's main screen counts.
                    val ownOverlay = pkg == packageName && event.className?.toString() != MainActivity::class.java.name
                    if (pkg != SYSTEM_UI && !fromKeyboard && !ownOverlay) {
                        appInFront = pkg
                        bubble?.setCurrentApp(pkg)
                    }
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
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> if (bubbleShown) keyboard?.windowsChanged(graph.dictation.isRunning, bubbleOnScreen)
        }
    }

    private val bubbleShown: Boolean
        get() = graph.settings.value.bubble.enabled

    /** The bubble is on screen now - also where no field is detected, when it shows everywhere. */
    private val bubbleOnScreen: Boolean
        get() = bubble?.visible == true

    /** Whether [pkg] is the keyboard in use, e.g. "com.google.android.inputmethod.latin". */
    private fun isKeyboard(pkg: String): Boolean = defaultKeyboard.isKeyboard(pkg)

    private val tracksInputDirectly: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * The app that owns the field the text will go into: the focused input's package, which
     * can be Amber itself (its test field), else the last app in front.
     */
    fun targetPackage(): String? = focusedInputPackage() ?: foregroundPackage

    /**
     * Brings Amber's screen back to the front, as it was left - the launcher's own intent
     * reuses the task, and REORDER_TO_FRONT keeps a second copy of the screen from stacking on
     * it. An accessibility service may start activities from the background.
     */
    fun bringAmberBack() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { startActivity(intent) }.onFailure { Log.w(TAG, "could not bring Amber back", it) }
    }

    /** Where the bubble is while it shows; a voice command's gesture keeps clear of it. */
    fun bubbleFrame(): Box? = bubble?.frame()

    /** Out of sight for a screenshot taken by voice, and back. Main thread. */
    fun hideBubbleForCapture(hidden: Boolean) {
        bubble?.setHiddenForCapture(hidden)
    }

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
        // Any field, a PIN pad too: its keyboard is just as much in the way. Right away, not
        // on the next window event - this is what lets the bubble move with the keyboard.
        // Before the bubble is told about the field: it is placed above the keyboard that is
        // on its way first, and then appears there, instead of sliding up once visible.
        keyboard?.focusChanged(kind != TextFieldKind.NONE)
        bubble?.setTextFieldFocused(dictatable)
        fieldCheck.schedule(0)
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

    /** The bubble stays out of [pkg] from now on - dropped on the hide target, or "Hide here". */
    private fun hideBubbleIn(pkg: String) {
        graph.appScope.launch {
            graph.settingsStore.update {
                it.copy(bubble = it.bubble.copy(hiddenIn = (it.bubble.hiddenIn + pkg).distinct()))
            }
        }
        val name = runCatching { packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager) }.getOrNull() ?: pkg
        Toast.makeText(this, getString(R.string.bubble_hidden_in, name), Toast.LENGTH_LONG).show()
    }

    /** A rotation: the bubble goes back to its edge, at the same share of the height. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bubble?.screenChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    /**
     * Screen off counts as locked at once (the lock may follow a moment later); on again it is
     * whatever the keyguard says; unlocked when the user is present.
     */
    private val lockWatch = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            bubble?.setLocked(
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> true
                    Intent.ACTION_USER_PRESENT -> false
                    else -> isLocked()
                },
            )
        }
    }
    private var lockWatchRegistered = false

    private fun isLocked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun teardown() {
        if (lockWatchRegistered) {
            runCatching { unregisterReceiver(lockWatch) }
            lockWatchRegistered = false
        }
        if (::commands.isInitialized) commands.close()
        fieldCheck.stop()
        keyboard?.stop()
        keyboard = null
        defaultKeyboard.stop()
        bubble?.detach()
        screenAwake.hold(false)
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
