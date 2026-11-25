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
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.amethyst.apps.Launcher
import app.amethyst.AmethystApp
import app.amethyst.Graph
import app.amethyst.R
import app.amethyst.bubble.BubbleController
import app.amethyst.DebugLog
import app.amethyst.core.apps.AppInFront
import app.amethyst.core.bubble.Box
import app.amethyst.core.bubble.BubbleEvent
import app.amethyst.core.bubble.FieldFocus
import app.amethyst.core.bubble.FieldPresence
import app.amethyst.core.bubble.SecretField
import app.amethyst.core.bubble.SystemShade
import app.amethyst.core.bubble.TextFieldKind
import app.amethyst.core.commands.RunMode
import app.amethyst.core.insert.InputTarget
import app.amethyst.dictation.DictationState
import app.amethyst.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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
import kotlinx.coroutines.withContext

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

    /** Short messages over the app in front, where a toast may not show ([app.amethyst.Messages]). */
    private val pill by lazy {
        MessagePill(
            service = this,
            dark = { graph.settings.value.ui.theme.dark(systemDark()) },
            screen = { bubble?.screen() },
            bubble = { bubble?.frame() },
        )
    }

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

    /** Which window is the app the user is in ([AppInFront]): only Amber's main screen counts of its own. */
    private val inFrontRule by lazy { AppInFront(ownPackage = packageName, ownMainScreen = MainActivity::class.java.name) }

    /** A field that takes dictation has focus - not a password, PIN or number field. */
    @Volatile
    var editableFieldFocused: Boolean = false
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "accessibility service connected")

        // From Android 13 the input method callbacks say which field takes text; the focus
        // events every app sends all day are then delivered for nothing.
        if (tracksInputDirectly) {
            runCatching {
                serviceInfo = serviceInfo?.apply { eventTypes = eventTypes and AccessibilityEvent.TYPE_VIEW_FOCUSED.inv() }
            }.onFailure { Log.w(TAG, "could not narrow the event types", it) }
        }

        if (!scope.isActive) scope = newScope()

        graph = AmethystApp.graph(this)
        textInserter = TextInserter(this, graph.strategyStore, graph.messages)
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
            onQuickActionOff = quickActions::off,
            onMoved = { side, edgeY ->
                graph.bubbleEvents.tryEmit(BubbleEvent.Docked(side))
                graph.appScope.launch {
                    graph.settingsStore.update { it.copy(bubble = it.bubble.copy(side = side, edgeY = edgeY)) }
                }
            },
            onHideIn = ::hideBubbleIn,
        )

        defaultKeyboard.start()
        bubble?.onNote = ::note
        keyboard = KeyboardTracker(this, graph.keyboardLog, defaultKeyboard::packageName) { top, floating, shown ->
            bubble?.setKeyboard(top, floating)
            // The keyboard closing is the one sign of a field an app took away without a word.
            if (presence.keyboard(shown)) applyPresence()
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
        // Amber's own fields, from its screen: below Android 13 the only word of them.
        scope.launch {
            graph.ownFieldFocused.collect { focused ->
                val signal = focus.own(focused, ::currentFocusKind) ?: return@collect
                Log.i(TAG, "field: Embersay's own field ${if (focused) "focused" else "left"}")
                if (focused) bubble?.setCurrentApp(packageName)
                updateTextFieldFocus(signal)
            }
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
                // SystemUI's shade, or the app that came over it: is the shade still down?
                if (event.packageName?.toString() == SYSTEM_UI || shadeOpen) checkShade()
                event.packageName?.toString()?.let { pkg ->
                    if (pkg != packageName) foregroundPackage = pkg
                    // Not the keyboard, the system bars or one of Amber's overlays ([AppInFront]).
                    inFrontRule.afterWindowChange(pkg, event.className?.toString(), fromKeyboard)?.let { app ->
                        appInFront = app
                        inFront(app)
                    }
                }
                // Switching apps or closing a dialog sends no focus event for the field that
                // went away, so look at what has input focus now - not for the keyboard's own
                // window, which moves no focus ([FieldFocus.windowChanged]).
                focus.windowChanged(fromKeyboard, ::currentFocusKind)?.let(::updateTextFieldFocus)
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED -> if (focus.watchesFocus) {
                val kind = kindOf(event.source)
                val pkg = event.packageName?.toString()
                if (kind != TextFieldKind.NONE) {
                    Log.i(TAG, "field: focus in ${DebugLog.redact(pkg)}, $kind")
                    pkg?.let(::inFront)
                }
                focus.focusEvent(kind, inOwnApp = pkg == packageName)?.let(::updateTextFieldFocus)
            }

            // Each one costs a getWindows() call; only the bubble and a message on screen need
            // the keyboard's edge, and a keyboard only comes, goes or changes size - a window
            // that changed its title, layer or focus is none of these.
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (mayMoveKeyboard(event.windowChanges)) {
                    if (bubbleShown) keyboard?.windowsChanged(graph.dictation.isRunning, bubbleOnScreen)
                    // A keyboard that came after the message would cover it, or put it on its keys.
                    pill.relayout()
                }
                if (mayMoveShade(event.windowChanges)) checkShade()
            }

            // The service asks for no other kind (accessibility_service_config.xml).
            else -> Unit
        }
    }

    /**
     * [pkg] is the app the user is in: the bubble follows it, and it counts as an app for the
     * quick menu even where the package manager does not know it - an app in another profile
     * ([app.amethyst.core.apps.AppsSeenInFront]).
     */
    private fun inFront(pkg: String) {
        noteSeenInFront(pkg)
        bubble?.setCurrentApp(pkg)
    }

    /** An app the user is in, for [app.amethyst.core.apps.AppsSeenInFront] - never Amber, never a home screen. */
    private fun noteSeenInFront(pkg: String) {
        if (inFrontRule.worthNoting(pkg) { Launcher.isHome(this, it) }) graph.appsSeenInFront.add(pkg)
    }

    /**
     * The app whose window has the focus right now, asked of the windows themselves. The last
     * window event can be stale: back from the recent apps into an app, Pixel's launcher sent
     * the last one, and the quick menu offered a style for the launcher (2026-11-22). Asked
     * once when the quick menu opens, not on every event.
     */
    fun appInFrontNow(): String? = runCatching {
        inFrontRule.focused(
            windows.map { window ->
                AppInFront.Window(
                    application = window.type == AccessibilityWindowInfo.TYPE_APPLICATION,
                    focused = window.isFocused,
                    owner = { window.root?.packageName?.toString() },
                )
            },
        )
    }.getOrNull()?.also(::noteSeenInFront)

    /** Whether a window change of these kinds can be a keyboard coming, going or resizing; 0 says nothing. */
    private fun mayMoveKeyboard(changes: Int): Boolean = changes == 0 || changes and KEYBOARD_WINDOW_CHANGES != 0

    /** Whether a window change of these kinds can be the shade coming down or going up; 0 says nothing. */
    private fun mayMoveShade(changes: Int): Boolean = changes == 0 || changes and SHADE_WINDOW_CHANGES != 0

    /** The notification shade or quick settings are down, as last seen ([checkShade]). */
    private var shadeOpen = false

    /**
     * Looks whether the notification shade or quick settings are pulled down ([SystemShade])
     * and tells the bubble, which steps aside while they are: an accessibility overlay lies
     * above the shade, and the bubble covered the notifications' text (Pixel 9a, 2026-11-22).
     * One getWindows() call; only while the bubble is switched on.
     */
    private fun checkShade() {
        val controller = bubble ?: return
        val open = bubbleShown && runCatching {
            val bounds = Rect()
            val seen = windows.map { window ->
                window.getBoundsInScreen(bounds)
                SystemShade.Window(
                    system = window.type == AccessibilityWindowInfo.TYPE_SYSTEM,
                    bounds = Box(bounds.left, bounds.top, bounds.right, bounds.bottom),
                    inUse = window.isFocused || window.isActive,
                    owner = { window.root?.packageName?.toString() },
                )
            }
            SystemShade.open(seen, controller.screen())
        }.getOrDefault(false)
        shadeOpen = open
        controller.setShadeOpen(open)
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
        runCatching { startActivity(intent) }.onFailure { Log.w(TAG, "could not bring Embersay back", it) }
    }

    /** Where the bubble is while it shows; a voice command's gesture keeps clear of it. */
    fun bubbleFrame(): Box? = bubble?.frame()

    /** Shows [text] as a short message over the app in front. Main thread; [app.amethyst.Messages] is the way in. */
    fun say(text: String) = pill.show(text)

    private fun systemDark(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

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
    fun focusedTarget(budgetMs: Long? = null): InputTarget? = runCatching {
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
            // Android names no node: a Compose field (up to 1.7). Its password fields may ask
            // for a plain text keyboard, so the field's node is searched for and asked.
            val secret = SecretField.isSecret(editor.inputType, null) ||
                (SecretField.searchNeeded(editor.inputType, focusFound = false) && searchedSecret(editor, budgetMs))
            InputTarget(
                packageName = editor.packageName ?: return@runCatching null,
                windowId = NO_WINDOW,
                fieldId = editor.fieldId,
                kind = if (secret) TextFieldKind.SECRET else TextFieldKind.TEXT,
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

    /**
     * Whether the field of [editor] is secret by its own node, found by searching the app's
     * windows: for a field Android's focus lookup names no node for. A walk - asked for only
     * where [SecretField.searchNeeded] says so, and what the bubble's search has found for
     * this input stands ([searchedVerdict]). With [budgetMs] the walk stops after that long
     * and says no: it is for the bubble and the tap, which must not wait for a big window.
     */
    private fun searchedSecret(editor: EditorInfo, budgetMs: Long?): Boolean {
        searchedVerdict?.let { return it }
        if (!::textInserter.isInitialized) return false
        val owner = editor.packageName ?: return false
        val node = if (budgetMs == null) {
            textInserter.searchFocusedEditable(owner)
        } else {
            textInserter.searchFocusedEditableWithin(owner, budgetMs).node
        }
        return node?.let { TextFieldKind.of(it.inputType, it.isPassword) == TextFieldKind.SECRET } == true
    }

    /**
     * What the search for the current input's node found: secret or not. Null while unknown -
     * not asked, still running, or stopped at its time limit.
     */
    @Volatile
    private var searchedVerdict: Boolean? = null

    /** Counts every input that starts or ends: an answer from the search for an older one is dropped. */
    private var inputGeneration = 0

    /** The search for the field's node is running: the bubble waits for it ([SecretField.Gate.WAIT]). */
    private var searchPending = false

    /**
     * Searches the app's windows for the started field's node off the main thread, within
     * [SecretField.SEARCH_BUDGET_MS], and hands the answer back to the main thread - unless another input
     * began or ended meanwhile. A failure of the search is no answer, as it was before.
     */
    private fun searchOffMain(editor: EditorInfo, restarting: Boolean) {
        val generation = ++inputGeneration
        val owner = editor.packageName
        searchPending = true
        scope.launch {
            val found: Pair<Boolean?, String?> = withContext(Dispatchers.IO) {
                try {
                    if (!::textInserter.isInitialized || owner == null) return@withContext null to null
                    val walk = textInserter.searchFocusedEditableWithin(owner, SecretField.SEARCH_BUDGET_MS)
                    val secret = walk.node?.let { TextFieldKind.of(it.inputType, it.isPassword) == TextFieldKind.SECRET } == true
                    // A walk stopped at its limit knows nothing: not secret, and asked again at the tap.
                    (if (walk.complete) secret else null) to "field search: ${walk.nodes} nodes, ${walk.millis} ms"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null to null
                }
            }
            if (generation != inputGeneration) return@launch
            found.second?.let(::note)
            searchPending = false
            searchedVerdict = found.first
            inputIsPassword = found.first == true
            inputKind = TextFieldKind.of(editor.inputType, isPassword = inputIsPassword)
            announceInput(editor, restarting)
        }
    }

    private fun currentFocusKind(): TextFieldKind =
        runCatching { kindOf(findFocus(AccessibilityNodeInfo.FOCUS_INPUT)) }.getOrDefault(TextFieldKind.NONE)

    /** A Compose field reports no input type, only that it is editable ([TextFieldKind.ofNode]). */
    private fun kindOf(node: AccessibilityNodeInfo?): TextFieldKind =
        if (node == null) TextFieldKind.NONE else TextFieldKind.ofNode(node.isEditable, node.inputType, node.isPassword)

    /**
     * From Android 13 the service's input method hears when a field starts and stops taking
     * text - the same signal that makes a keyboard slide in, with the field's input type.
     * That is a better answer to "is the bubble needed right now" than focus events, which
     * some apps never send.
     */
    @Volatile
    private var inputKind = TextFieldKind.NONE

    /** Whether a field is there to dictate into: it takes text and its keyboard has not closed. */
    private val presence = FieldPresence()

    /** Which field takes text, from the input method, focus events and Amber's own screen. */
    private val focus = FieldFocus(inputMethodTells = tracksInputDirectly)

    /** [FieldFocus.Signal.fresh]: another field than before took input - not a restart of the input in the same one. */
    private fun updateTextFieldFocus(signal: FieldFocus.Signal) {
        val kind = signal.kind
        val another = signal.fresh && kind != TextFieldKind.NONE && presence.kind != TextFieldKind.NONE
        presence.field(kind, signal.fresh)
        // Any field, a PIN pad too: its keyboard is just as much in the way. Right away, not
        // on the next window event, and before the bubble is told about the field: a bubble
        // whose place is where the keyboard opens is put off the keys first and appears
        // there, instead of sliding up once visible.
        if (another) keyboard?.newField() else keyboard?.focusChanged(kind != TextFieldKind.NONE)
        applyPresence()
    }

    private fun applyPresence() {
        // Two answers: a field takes text - what inserting and the quick menu go by,
        // keyboard or not - and the bubble is wanted for it, which ends with the keyboard.
        val takesText = presence.kind.wantsDictation
        val active = presence.active
        if (active != bubbleField) note("field ${if (active) "active" else "gone"}: ${presence.why}")
        if (takesText && !editableFieldFocused) graph.textFieldFocused(::targetPackage)
        editableFieldFocused = takesText
        bubbleField = active
        bubble?.setTextFieldFocused(active)
    }

    /** The bubble is wanted for a field right now ([FieldPresence.active]). */
    private var bubbleField = false

    /** One line for logcat and Diagnostics → Keyboard: why the bubble came, went or moved. */
    private fun note(line: String) {
        Log.i(TAG, line)
        graph.keyboardLog.value = (listOf(line) + graph.keyboardLog.value).take(NOTE_LINES)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreateInputMethod(): InputMethod = object : InputMethod(this) {
        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
            super.onStartInput(attribute, restarting)
            val before = inputKind
            // Asking the app for the focused node is a round trip into it. A restart is the
            // same field - some apps restart the input with every keystroke - so its answer
            // stands, and an input type that says "secret" needs no second opinion.
            val again = restarting && before != TextFieldKind.NONE
            if (!again) {
                inputGeneration++
                searchedVerdict = null
                searchPending = false
                val focused = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
                when (SecretField.gate(attribute.inputType, focusFound = focused != null)) {
                    SecretField.Gate.NEVER -> inputIsPassword = false
                    SecretField.Gate.SHOW -> inputIsPassword = focused?.isPassword == true
                    SecretField.Gate.WAIT -> {
                        // The bubble stays away until the search says what the field is.
                        inputIsPassword = false
                        inputKind = TextFieldKind.of(attribute.inputType)
                        searchOffMain(attribute, restarting)
                        return
                    }
                }
            }
            if (searchPending) return
            inputKind = TextFieldKind.of(attribute.inputType, isPassword = inputIsPassword)
            announceInput(attribute, restarting, before)
        }

        override fun onFinishInput() {
            super.onFinishInput()
            Log.i(TAG, "field: input finished")
            inputGeneration++
            searchPending = false
            searchedVerdict = null
            inputKind = TextFieldKind.NONE
            inputIsPassword = false
            updateTextFieldFocus(focus.inputFinished())
        }
    }

    /** The field's kind is known: log it, note the app in front, and tell the bubble. */
    private fun announceInput(attribute: EditorInfo, restarting: Boolean, before: TextFieldKind = TextFieldKind.NONE) {
        if (!restarting || inputKind != before) {
            Log.i(TAG, "field: input started in ${DebugLog.redact(attribute.packageName?.toString())}, $inputKind${if (restarting) ", restarting" else ""}")
        }
        // The field's own app: the surest answer to "where is the user typing".
        if (inputKind != TextFieldKind.NONE) attribute.packageName?.let(::inFront)
        updateTextFieldFocus(focus.inputStarted(inputKind, restarting))
    }

    /** The node of the field taking input called itself a password field, asked once per field. */
    private var inputIsPassword = false

    override fun onInterrupt() = Unit

    /** The bubble stays out of [pkg] from now on - dropped on the hide target, or "Hide here". */
    private fun hideBubbleIn(pkg: String) {
        graph.appScope.launch {
            graph.settingsStore.update {
                it.copy(bubble = it.bubble.copy(hiddenIn = (it.bubble.hiddenIn + pkg).distinct()))
            }
        }
        val name = Launcher.label(this, pkg) ?: pkg
        graph.messages.show(getString(R.string.bubble_hidden_in, name))
    }

    /** A rotation: the bubble goes back to its edge, at the same share of the height. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bubble?.screenChanged()
        pill.screenChanged()
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
     *
     * A dictation being recorded ends with the screen, as if tapped: with the bubble gone
     * nothing else could stop it, and it would record on in the pocket. Its text is kept, and
     * the dictation says so once the phone is back.
     */
    private val lockWatch = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val locked = when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> true
                Intent.ACTION_USER_PRESENT -> false
                else -> isLocked()
            }
            bubble?.setLocked(locked)
            if (!::graph.isInitialized) return
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                graph.dictation.screenOff()
                // The phone may lie on a table now: what is past its time goes before anyone picks it up.
                graph.pruneHistory()
            } else if (!locked) {
                graph.dictation.screenBack()
            }
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
        keyboard?.stop()
        keyboard = null
        defaultKeyboard.stop()
        bubble?.detach()
        screenAwake.hold(false)
        pill.remove()
        bubble = null
        if (::graph.isInitialized && graph.accessibilityService === this) {
            // Switched off mid-dictation: with the service go the bubble that could stop the
            // recording and the only way to insert its text - it must not record on unseen.
            graph.dictation.cancel()
            graph.accessibilityService = null
        }
        scope.cancel()
    }

    /**
     * A failure nobody caught in the bubble's and the keyboard's work ends the process, as a crash
     * anywhere else on the main thread does. These coroutines are long-lived collectors - the
     * bubble's look, the screen-awake hold, the bubble window going on and off - and one that
     * failed would be gone for good: a bubble stuck listening, a screen held awake. Ended, the
     * service is bound again by Android and starts afresh.
     */
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error -> workFailed(error) })

    /**
     * Handed to the crash handler ([app.amethyst.core.dictation.ExitLog.installCrashHandler]),
     * which keeps it in Diagnostics across the restart and then lets Android end the process -
     * the one record of it. Only without a crash handler is it kept for this process alone.
     */
    private fun workFailed(error: Throwable) {
        Log.e(TAG, "service work failed: ${error.javaClass.name}")
        val crash = Thread.getDefaultUncaughtExceptionHandler()
        if (crash != null) {
            crash.uncaughtException(Thread.currentThread(), error)
        } else {
            runCatching { AmethystApp.graph(this).backgroundFailed(error) }
        }
    }


    private companion object {
        const val TAG = "Embersay"
        const val SYSTEM_UI = SystemShade.SYSTEM_UI

        /** Window id of a target known only through the input connection. */
        const val NO_WINDOW = -1

        /** The window changes a keyboard shows up as: its window added, removed or resized. */
        const val KEYBOARD_WINDOW_CHANGES = AccessibilityEvent.WINDOWS_CHANGE_ADDED or
            AccessibilityEvent.WINDOWS_CHANGE_REMOVED or
            AccessibilityEvent.WINDOWS_CHANGE_BOUNDS

        /** The window changes the shade shows up as: its window shown, gone, resized, or taking the focus. */
        const val SHADE_WINDOW_CHANGES = AccessibilityEvent.WINDOWS_CHANGE_ADDED or
            AccessibilityEvent.WINDOWS_CHANGE_REMOVED or
            AccessibilityEvent.WINDOWS_CHANGE_BOUNDS or
            AccessibilityEvent.WINDOWS_CHANGE_FOCUSED or
            AccessibilityEvent.WINDOWS_CHANGE_ACTIVE

        /** Lines kept for Diagnostics → Keyboard, the keyboard's readings and these notes together. */
        const val NOTE_LINES = 40
    }
}
