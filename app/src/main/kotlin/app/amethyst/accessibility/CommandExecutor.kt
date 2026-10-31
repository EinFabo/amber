package app.amethyst.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import app.amethyst.Graph
import app.amethyst.apps.Launcher
import app.amethyst.core.commands.AppMatch
import app.amethyst.core.commands.Command
import app.amethyst.core.bubble.Box
import app.amethyst.core.commands.CommandOutcome
import app.amethyst.core.commands.CommandTrace
import app.amethyst.core.commands.Direction
import app.amethyst.core.commands.EditTarget
import app.amethyst.core.commands.GesturePath
import app.amethyst.core.commands.GlobalAction
import app.amethyst.core.commands.RepeatRun
import app.amethyst.core.commands.Repeats
import app.amethyst.core.commands.Repetition
import app.amethyst.core.commands.ScrollAction
import app.amethyst.core.commands.ScrollMove
import app.amethyst.core.commands.ScrollPick
import app.amethyst.core.commands.ScrollRun
import app.amethyst.core.commands.ScrollSnapshot
import app.amethyst.core.commands.ScrollTarget
import app.amethyst.core.commands.Scrollable
import app.amethyst.core.commands.SettingsPage
import app.amethyst.core.commands.Step
import app.amethyst.core.commands.Stroke
import app.amethyst.core.commands.VolumeChange
import app.amethyst.core.insert.InputTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Carries out a voice command - the only place Amber acts for the user. Every command passes
 * one gate first: voice commands switched on, the phone unlocked. Nothing the screen shows is
 * read: a scroll looks for which nodes can scroll and how large they are, and while it repeats,
 * where the scrolled node and the items inside it sit - to see whether it moved; a text command
 * reads the field it was started in, nothing else. Logcat gets the kind of command only, never
 * what was heard, which app or any text. What each step did goes into the command's
 * [CommandTrace] instead, for Diagnostics: names and counts, never a field's text.
 */
class CommandExecutor(private val service: AmethystAccessibilityService, private val graph: Graph) {

    private val device = DeviceControls(service)
    private val editor = FieldEditor(service, service.textInserter)

    /**
     * [startedIn]: the field that had the cursor when the command's recording began, if any.
     * [trace] gets a line for every decision and step.
     */
    suspend fun execute(command: Command, startedIn: InputTarget?, trace: CommandTrace): CommandOutcome {
        if (!graph.settings.value.commands.enabled) {
            trace.note("gate: voice commands are switched off")
            return CommandOutcome.Refused("Voice commands are off.")
        }
        if (service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) {
            trace.note("gate: the phone is locked")
            return CommandOutcome.Refused("Unlock the phone first.")
        }
        trace.note("gate: passed (commands on, phone unlocked)")
        Log.i(TAG, "command: ${command.kind()}")
        return when (command) {
            is Command.Navigate -> navigate(command.action, command.times, trace)
            is Command.OpenApp -> open(command.name, trace)
            is Command.Scroll -> scroll(command.direction, command.times, trace)
            is Command.ScrollToEnd -> scrollToEnd(command.direction, trace)
            is Command.Page -> page(command.direction, command.times, trace)
            is Command.Swipe -> swipe(command.direction, command.times, trace)
            is Command.Media -> withContext(Dispatchers.Main) { device.press(command.key, trace) }
            is Command.Volume -> withContext(Dispatchers.Main) { volume(command.change, command.times, trace) }
            is Command.Torch -> withContext(Dispatchers.Main) { device.torch(command.on, trace) }
            is Command.OpenSettings -> openSettings(command.page, trace)
            is Command.Edit -> withContext(Dispatchers.Default) { edit(command, startedIn, trace) }
        }
    }

    /**
     * A text command, only in the field it was started in and never in a password field
     * ([EditTarget]) - recognition took seconds, and meanwhile the user may have tapped into
     * another chat, where "einfügen" would paste the clipboard.
     */
    private suspend fun edit(command: Command.Edit, startedIn: InputTarget?, trace: CommandTrace): CommandOutcome {
        val now = service.focusedTarget()
        trace.note("target check: ${EditTarget.verdict(startedIn, now)}")
        EditTarget.refusal(startedIn, now)?.let { return CommandOutcome.Refused(it) }
        return editor.apply(command.edit, command.times, trace)
    }

    /** The service is going away: let go of what watches the phone. */
    fun close() = device.close()

    private suspend fun navigate(action: GlobalAction, times: Int, trace: CommandTrace): CommandOutcome {
        val (id, done) = when (action) {
            GlobalAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK to "Back"
            GlobalAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME to "Home screen"
            GlobalAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS to "Recent apps"
            GlobalAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS to "Notifications"
            GlobalAction.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS to "Quick settings"
            // Before Android 12 there is no action for it; Back closes an open shade.
            GlobalAction.CLOSE_NOTIFICATIONS ->
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
                else AccessibilityService.GLOBAL_ACTION_BACK) to "Notifications closed"
            GlobalAction.LOCK_SCREEN -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN to "Locked"
            GlobalAction.SCREENSHOT -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT to "Screenshot taken"
            GlobalAction.SPLIT_SCREEN -> AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN to "Split screen"
            GlobalAction.POWER_MENU -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG to "Power menu"
        }
        val refused = "Android refused: ${done.lowercase()}."
        if (action == GlobalAction.SCREENSHOT) {
            val taken = withoutBubble { service.performGlobalAction(id) }
            trace.note("global action $action, the bubble hidden for the capture: ${if (taken) "taken" else "refused"}")
            return if (taken) CommandOutcome.Done(done) else CommandOutcome.Failed(refused)
        }
        trace.note("global action $action")
        return steps(times, trace) {
            if (withContext(Dispatchers.Main) { service.performGlobalAction(id) }) Step.Done else Step.Failed(refused)
        }.outcome(done, "nothing changed")
    }

    /**
     * With commands on the bubble shows everywhere, so a screenshot by voice would always have
     * it in the picture: it steps out for the capture, which Android takes a moment after the call.
     */
    private suspend fun withoutBubble(action: () -> Boolean): Boolean {
        withContext(Dispatchers.Main) { service.hideBubbleForCapture(true) }
        delay(CAPTURE_SETTLE_MS)
        return try {
            withContext(Dispatchers.Main) { action() }.also { delay(CAPTURE_MS) }
        } finally {
            // Also when a tap stopped the command meanwhile: the bubble comes back either way.
            withContext(NonCancellable + Dispatchers.Main) { service.hideBubbleForCapture(false) }
        }
    }

    private suspend fun open(name: String, trace: CommandTrace): CommandOutcome {
        val apps = withContext(Dispatchers.IO) { Launcher.apps(service) }
        trace.note("app search: “$name” among ${apps.size} launcher apps")
        return when (val match = AppMatch.find(name, apps) { it.label }) {
            is AppMatch.Found -> {
                val app = match.app
                trace.note("Found: ${app.label} (${AppMatch.tier(name, app.label).words()})")
                val intent = service.packageManager.getLaunchIntentForPackage(app.packageName)
                if (intent == null) {
                    trace.note("no launch intent for ${app.packageName}")
                    return CommandOutcome.Failed("${app.label} cannot be opened.")
                }
                start(intent, app.label, app.packageName, trace)
            }
            is AppMatch.Ambiguous -> {
                val labels = match.candidates.map { it.label }
                val more = if (labels.size > MAX_TRACED) ", ${labels.size - MAX_TRACED} more" else ""
                trace.note(
                    "Ambiguous: ${labels.size} apps by ${AppMatch.tier(name, labels.first()).words()} - " +
                        labels.take(MAX_TRACED).joinToString() + more,
                )
                CommandOutcome.Failed("Which one? ${labels.take(MAX_NAMED).joinToString()} - say the full name.")
            }
            AppMatch.NotFound -> {
                trace.note("NotFound: no exact name, start, word inside or near miss")
                CommandOutcome.Failed("No app called “$name”.")
            }
        }
    }

    private fun AppMatch.Tier.words(): String = when (this) {
        AppMatch.Tier.EXACT -> "exact name"
        AppMatch.Tier.START -> "start of the name"
        AppMatch.Tier.INSIDE -> "word inside the name"
        AppMatch.Tier.CLOSE -> "near miss"
    }

    /** A page of Android's settings - opened only; what is on it the user switches. */
    private suspend fun openSettings(page: SettingsPage, trace: CommandTrace): CommandOutcome {
        val (actions, label) = when (page) {
            SettingsPage.WIFI -> listOf(Settings.ACTION_WIFI_SETTINGS) to "Wi-Fi settings"
            SettingsPage.BLUETOOTH -> listOf(Settings.ACTION_BLUETOOTH_SETTINGS) to "Bluetooth settings"
            SettingsPage.SOUND -> listOf(Settings.ACTION_SOUND_SETTINGS) to "Sound settings"
            SettingsPage.DISPLAY -> listOf(Settings.ACTION_DISPLAY_SETTINGS) to "Display settings"
            SettingsPage.BATTERY -> listOf(Intent.ACTION_POWER_USAGE_SUMMARY, Settings.ACTION_BATTERY_SAVER_SETTINGS) to "Battery settings"
        }
        // The settings app has a launcher icon, so the manifest's launcher query makes it visible here.
        val resolved = actions.firstNotNullOfOrNull { action ->
            val intent = Intent(action)
            intent.resolveActivity(service.packageManager)?.packageName?.let { intent to it }
        }
        if (resolved == null) {
            trace.note("settings page $page: nothing resolves ${actions.joinToString()}")
            return CommandOutcome.Failed("This phone has no $label page Amber can open.")
        }
        val (intent, owner) = resolved
        trace.note("settings page $page: ${intent.action} resolved to $owner")
        return start(intent, label, owner, trace)
    }

    /**
     * The system binds accessibility services allowed to start activities from the background.
     * A start Android blocks anyway fails without an error, so the app in front is checked.
     */
    private suspend fun start(intent: Intent, label: String, packageName: String, trace: CommandTrace): CommandOutcome {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = withContext(Dispatchers.Main) { runCatching { service.startActivity(intent) } }
        started.exceptionOrNull()?.let { error ->
            trace.note("start: Android threw ${error::class.java.simpleName}")
            return CommandOutcome.Failed("Android did not let Amber open $label.")
        }
        val sentAt = SystemClock.elapsedRealtime()
        repeat(LAUNCH_CHECKS) {
            if (service.appInFront == packageName) {
                trace.note("launch check: $packageName came to the front after ${SystemClock.elapsedRealtime() - sentAt} ms")
                return CommandOutcome.Done("Opened $label")
            }
            delay(LAUNCH_CHECK_MS)
        }
        trace.note(
            "launch check: $packageName not in front within ${SystemClock.elapsedRealtime() - sentAt} ms " +
                "(in front: ${service.appInFront ?: "not known"})",
        )
        return CommandOutcome.Failed("$label did not come to the front - Android may have blocked the start.")
    }

    // ---- Scrolling, pages, swiping ----

    /** Once: as v1, by the node's action or a stroke. More often: step by step ([moving]). */
    private suspend fun scroll(direction: Direction, times: Int, trace: CommandTrace): CommandOutcome {
        val did = "Scrolled ${direction.word()}"
        if (times == 1) return once(did) { scrollStep(direction, look(trace), page = false, trace) }
        return moving(direction, page = false, times, look(trace), trace).outcome(did, "nothing more to scroll")
    }

    private suspend fun page(direction: Direction, times: Int, trace: CommandTrace): CommandOutcome {
        val did = "Paged ${direction.word()}"
        if (times == 1) return once(did) { scrollStep(direction, look(trace), page = true, trace) }
        return moving(direction, page = true, times, look(trace), trace).outcome(did, "nothing more to scroll")
    }

    /** "Ganz nach unten": step after step until the end, at most [Repeats.MAX_TO_END]. */
    private suspend fun scrollToEnd(direction: Direction, trace: CommandTrace): CommandOutcome {
        val end = when (direction) {
            Direction.DOWN -> "the bottom"
            Direction.UP -> "the top"
            Direction.LEFT -> "the left end"
            Direction.RIGHT -> "the right end"
        }
        val screen = look(trace)
        if (ScrollRun.move(screen.nodes, direction, pages = false, scrolledNode = false) == ScrollMove.Gesture) {
            // Nothing here says it scrolls, so nothing can say where its end is: one stroke, not thirty unseen.
            trace.note("picked: nothing says it scrolls, so no end can be seen - one stroke instead of a run")
            return if (stroke(direction, trace)) CommandOutcome.Done("Scrolled ${direction.word()} once - this screen does not show where its end is")
            else CommandOutcome.Failed(GESTURE_REFUSED)
        }
        val run = moving(direction, page = false, Repeats.MAX_TO_END, screen, trace)
        return when (run.stop) {
            Step.Unchanged -> CommandOutcome.Done(if (run.done == 0) "Already at $end" else "Scrolled to $end")
            null -> CommandOutcome.Done("Scrolled ${direction.word()} ${run.done} times - still not at $end")
            else -> run.outcome("Scrolled ${direction.word()}", "nothing more to scroll")
        }
    }

    private suspend fun swipe(direction: Direction, times: Int, trace: CommandTrace): CommandOutcome =
        steps(times, trace) {
            if (gesture(GesturePath.swipe(direction, screenWidth(), screenHeight(), service.bubbleFrame()), trace)) Step.Done
            else Step.Failed(GESTURE_REFUSED)
        }.outcome("Swiped ${direction.word()}", "nothing changed")

    private suspend fun once(did: String, act: suspend () -> Boolean): CommandOutcome =
        if (act()) CommandOutcome.Done(did) else CommandOutcome.Failed(GESTURE_REFUSED)

    /**
     * [times] steps with [Repetition.PAUSE_MS] between them, stopping at the first that fails;
     * each step and the end of the run go into [trace].
     */
    private suspend fun steps(times: Int, trace: CommandTrace, step: suspend () -> Step): RepeatRun {
        var n = 0
        val run = Repetition.run(times, pause = { delay(Repetition.PAUSE_MS) }) {
            n++
            step().also { result ->
                trace.note(
                    "step $n: " + when (result) {
                        Step.Done -> "done"
                        Step.Unchanged -> "nothing changed"
                        is Step.Failed -> "failed - ${result.message}"
                    },
                )
            }
        }
        trace.note(
            when (val stop = run.stop) {
                null, Step.Done -> "stopped: count reached (${run.done} of ${run.asked})"
                Step.Unchanged -> "stopped at step ${run.done + 1} of ${run.asked}: nothing changed"
                is Step.Failed -> "stopped at step ${run.done + 1} of ${run.asked}: ${stop.message}"
            },
        )
        return run
    }

    /**
     * Repeated scrolling, step by step as [ScrollRun] decides: the picked node's action, a
     * pause for the content to settle - the pause between steps - then that node looked at
     * again, its place and the places of what lies inside it, nothing else on the screen. From
     * the second step on only that node counts; the run ends where it cannot go on, refuses or
     * did not move, and never falls back to a stroke there. Only where nothing on that axis says
     * it scrolls do strokes make up the count, unseen.
     */
    private suspend fun moving(direction: Direction, page: Boolean, times: Int, screen: Screen, trace: CommandTrace): RepeatRun {
        val pages = page && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        var candidates = screen.scrollables
        var scrolledNode = false
        var step = 0
        // Why the step that ended the run did, for the last line.
        var ended = "nothing changed"
        val run = Repetition.run(times, pause = {}) {
            step++
            when (val move = ScrollRun.move(candidates.map { it.first }, direction, pages, scrolledNode)) {
                ScrollMove.End -> {
                    ended = if (scrolledNode) "end of the list - it offers ${direction.word()} no more"
                    else "a list on that axis cannot scroll ${direction.word()} - already at its end"
                    trace.note("step $step: $ended")
                    Step.Unchanged
                }
                ScrollMove.Gesture -> {
                    if (step == 1) trace.note("picked: nothing on that axis says it scrolls - gestures, unseen")
                    if (!stroke(direction, trace)) {
                        ended = "gesture not taken"
                        return@run Step.Failed(GESTURE_REFUSED)
                    }
                    delay(Repetition.PAUSE_MS)
                    Step.Done
                }
                is ScrollMove.Node -> {
                    val (picked, node) = candidates.first { it.first.id == move.id }
                    if (!scrolledNode) trace.note("picked: ${picked.describe(node)}, node action ${move.action}")
                    val before = withContext(Dispatchers.Default) { snapshot(node) }
                    if (before == null) {
                        ended = "the list is gone"
                        trace.note("step $step: $ended")
                        return@run Step.Unchanged
                    }
                    val took = withContext(Dispatchers.Main) { node.performAction(move.action.id()) }
                    if (took) delay(Repetition.PAUSE_MS)
                    val after = if (took) withContext(Dispatchers.Default) { snapshot(node) } else null
                    scrolledNode = true
                    // Refreshed by the snapshot: what the node offers now says whether it can go on.
                    candidates = listOfNotNull(after?.let { scrollable(node, picked.id, picked.activeWindow) }?.let { it to node })
                    val result = ScrollRun.result(took, before, after)
                    val what = when {
                        !took -> "action ${move.action} refused by the list"
                        after == null -> "the list is gone after the action"
                        result == Step.Done -> "moved"
                        else -> "did not move"
                    }
                    trace.note("step $step: $what")
                    if (result != Step.Done) ended = what
                    result
                }
            }
        }
        trace.note(
            when (run.stop) {
                null, Step.Done -> "stopped: count reached (${run.done} of ${run.asked})"
                else -> "stopped at step ${run.done + 1} of ${run.asked}: $ended"
            },
        )
        return run
    }

    /**
     * One scroll: the picked node's page or scroll action, else - or when it refuses - a stroke.
     * False only when Android did not take the stroke.
     */
    private suspend fun scrollStep(direction: Direction, screen: Screen, page: Boolean, trace: CommandTrace): Boolean {
        val nodes = screen.nodes
        val pick = if (page && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ScrollTarget.pickPage(nodes, direction) else ScrollTarget.pick(nodes, direction)
        if (pick is ScrollPick.Node) {
            val (picked, node) = screen.scrollables.first { it.first.id == pick.id }
            trace.note("picked: ${picked.describe(node)}, node action ${pick.action}")
            if (withContext(Dispatchers.Main) { node.performAction(pick.action.id()) }) {
                trace.note("node action ${pick.action}: taken")
                return true
            }
            trace.note("node action ${pick.action}: refused - gesture fallback")
        } else {
            trace.note("picked: no node offers ${direction.word()} - gesture fallback")
        }
        return stroke(direction, trace)
    }

    /** A finger stroke that scrolls towards [direction], clear of the bubble. */
    private suspend fun stroke(direction: Direction, trace: CommandTrace): Boolean =
        gesture(GesturePath.scroll(direction, screenWidth(), screenHeight(), service.bubbleFrame()), trace)

    /** [stroke] dispatched, how it went noted; true when it completed. */
    private suspend fun gesture(stroke: Stroke, trace: CommandTrace): Boolean {
        val result = dispatch(stroke)
        trace.note(
            "gesture: " + when (result) {
                Gesture.COMPLETED -> "dispatched and completed"
                Gesture.CANCELLED -> "dispatched, then cancelled"
                Gesture.REFUSED -> "refused by Android"
            },
        )
        return result == Gesture.COMPLETED
    }

    private enum class Gesture { COMPLETED, CANCELLED, REFUSED }

    /** Refused when the service may not perform gestures yet; cancelled when something interrupted it. */
    private suspend fun dispatch(stroke: Stroke): Gesture = withContext(Dispatchers.Main) {
        val path = Path().apply {
            moveTo(stroke.fromX, stroke.fromY)
            lineTo(stroke.toX, stroke.toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, stroke.durationMs))
            .build()
        suspendCancellableCoroutine { result ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    if (result.isActive) result.resume(Gesture.COMPLETED)
                }

                override fun onCancelled(description: GestureDescription?) {
                    if (result.isActive) result.resume(Gesture.CANCELLED)
                }
            }
            val sent = runCatching { service.dispatchGesture(gesture, callback, null) }.getOrDefault(false)
            if (!sent && result.isActive) result.resume(Gesture.REFUSED)
        }
    }

    /** For Diagnostics: the node's class and where it is - nothing it shows. */
    private fun Scrollable.describe(node: AccessibilityNodeInfo): String =
        (node.className?.toString()?.substringAfterLast('.') ?: "unnamed node") +
            (if (vertical) ", vertical" else ", horizontal") +
            (if (activeWindow) ", in the window in use" else ", in another window")

    /** What can scroll on the screen, each with its node. */
    private class Screen(val scrollables: List<Pair<Scrollable, AccessibilityNodeInfo>>) {
        val nodes: List<Scrollable> get() = scrollables.map { it.first }
    }

    private suspend fun look(trace: CommandTrace): Screen = withContext(Dispatchers.Default) { screen() }.also { screen ->
        trace.note("scrollable nodes: ${screen.scrollables.size}")
    }

    /**
     * Every node that can scroll in the apps' windows - not Amber's own overlays, not the
     * keyboard - bounded like the text field search. Of each node only whether it scrolls is
     * read; of one that does, its class, actions, grid and size.
     */
    private fun screen(): Screen {
        val windows = runCatching { service.windows }.getOrDefault(emptyList()).filter {
            it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
        val found = mutableListOf<Pair<Scrollable, AccessibilityNodeInfo>>()
        var visited = 0
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName == service.packageName) continue
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val node = queue.removeFirst()
                visited++
                scrollable(node, found.size, window.isActive)?.let { found += it to node }
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
            }
        }
        return Screen(found)
    }

    /** [node] as something that scrolls - its class, actions, grid and size; null when it does not. */
    private fun scrollable(node: AccessibilityNodeInfo, id: Int, activeWindow: Boolean): Scrollable? {
        if (!node.isScrollable || !node.isVisibleToUser) return null
        val actions = node.actionList.mapNotNull { it.scrollAction() }.toSet()
        if (actions.isEmpty()) return null
        val grid = node.collectionInfo
        val bounds = Rect().also(node::getBoundsInScreen)
        val vertical = ScrollTarget.isVertical(node.className?.toString(), grid?.rowCount ?: 0, grid?.columnCount ?: 0, actions)
        return Scrollable(id, bounds.width().toLong() * bounds.height(), activeWindow, vertical, actions)
    }

    /**
     * Where the scrolled [node] and the items inside it sit ([ScrollSnapshot]) - bounds and
     * list positions only, never text, nothing outside the node. Refreshed first; null when the
     * node is gone.
     */
    private fun snapshot(node: AccessibilityNodeInfo): ScrollSnapshot? {
        if (!runCatching { node.refresh() }.getOrDefault(false)) return null
        val rect = Rect()
        return runCatching {
            ScrollSnapshot.of(
                node,
                bounds = { n -> n.getBoundsInScreen(rect); Box(rect.left, rect.top, rect.right, rect.bottom) },
                place = { n -> n.collectionItemInfo?.let { it.rowIndex to it.columnIndex } },
                scrolls = { n -> n.isScrollable },
                children = { n -> (0 until n.childCount).mapNotNull { n.getChild(it) } },
            )
        }.getOrNull()
    }

    private fun screenWidth(): Int = screenSize().x

    private fun screenHeight(): Int = screenSize().y

    private fun screenSize(): Point {
        val windows = service.getSystemService(WindowManager::class.java)
            ?: return service.resources.displayMetrics.let { Point(it.widthPixels, it.heightPixels) }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windows.maximumWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            Point().also { windows.defaultDisplay.getRealSize(it) }
        }
    }

    private fun AccessibilityAction.scrollAction(): ScrollAction? = when (id) {
        AccessibilityAction.ACTION_SCROLL_UP.id -> ScrollAction.UP
        AccessibilityAction.ACTION_SCROLL_DOWN.id -> ScrollAction.DOWN
        AccessibilityAction.ACTION_SCROLL_LEFT.id -> ScrollAction.LEFT
        AccessibilityAction.ACTION_SCROLL_RIGHT.id -> ScrollAction.RIGHT
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> ScrollAction.FORWARD
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> ScrollAction.BACKWARD
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pageAction(id) else null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun pageAction(id: Int): ScrollAction? = when (id) {
        AccessibilityAction.ACTION_PAGE_UP.id -> ScrollAction.PAGE_UP
        AccessibilityAction.ACTION_PAGE_DOWN.id -> ScrollAction.PAGE_DOWN
        AccessibilityAction.ACTION_PAGE_LEFT.id -> ScrollAction.PAGE_LEFT
        AccessibilityAction.ACTION_PAGE_RIGHT.id -> ScrollAction.PAGE_RIGHT
        else -> null
    }

    /** Page actions are only ever picked from Android 10 on, where nodes offer them. */
    private fun ScrollAction.id(): Int = when (this) {
        ScrollAction.UP -> AccessibilityAction.ACTION_SCROLL_UP.id
        ScrollAction.DOWN -> AccessibilityAction.ACTION_SCROLL_DOWN.id
        ScrollAction.LEFT -> AccessibilityAction.ACTION_SCROLL_LEFT.id
        ScrollAction.RIGHT -> AccessibilityAction.ACTION_SCROLL_RIGHT.id
        ScrollAction.FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        ScrollAction.BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        ScrollAction.PAGE_UP, ScrollAction.PAGE_DOWN, ScrollAction.PAGE_LEFT, ScrollAction.PAGE_RIGHT ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pageId(this) else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun pageId(action: ScrollAction): Int = when (action) {
        ScrollAction.PAGE_UP -> AccessibilityAction.ACTION_PAGE_UP.id
        ScrollAction.PAGE_LEFT -> AccessibilityAction.ACTION_PAGE_LEFT.id
        ScrollAction.PAGE_RIGHT -> AccessibilityAction.ACTION_PAGE_RIGHT.id
        else -> AccessibilityAction.ACTION_PAGE_DOWN.id
    }

    // ---- Media and volume ----

    private suspend fun volume(change: VolumeChange, times: Int, trace: CommandTrace): CommandOutcome {
        if (change == VolumeChange.MUTE || change == VolumeChange.UNMUTE) return device.mute(change == VolumeChange.MUTE, trace)
        device.volumeProblem()?.let {
            trace.note("media volume: $it")
            return CommandOutcome.Failed(it)
        }
        val louder = change == VolumeChange.LOUDER
        val run = steps(times, trace) { device.volumeStep(louder, trace) }
        if (run.stop == Step.Unchanged && run.done == 0) {
            return CommandOutcome.Done(if (louder) "Already as loud as it goes" else "Already as quiet as it goes")
        }
        return run.outcome(if (louder) "Louder" else "Quieter", if (louder) "as loud as it goes" else "as quiet as it goes")
    }

    private fun Direction.word(): String = name.lowercase()

    /** For logcat: what kind of command, never its words, the app or any text. */
    private fun Command.kind(): String = when (this) {
        is Command.Navigate -> "navigate ${action.name.lowercase()}${times()}"
        is Command.OpenApp -> "open app"
        is Command.Scroll -> "scroll ${direction.word()}${times()}"
        is Command.ScrollToEnd -> "scroll to the end ${direction.word()}"
        is Command.Page -> "page ${direction.word()}${times()}"
        is Command.Swipe -> "swipe ${direction.word()}${times()}"
        is Command.Media -> "media ${key.name.lowercase()}"
        is Command.Volume -> "volume ${change.name.lowercase()}${times()}"
        is Command.Torch -> "flashlight ${on?.let { if (it) "on" else "off" } ?: "toggle"}"
        is Command.OpenSettings -> "settings ${page.name.lowercase()}"
        is Command.Edit -> "text ${edit.name.lowercase()}${times()}"
    }

    private fun Command.times(): String {
        val n = when (this) {
            is Command.Navigate -> times
            is Command.Scroll -> times
            is Command.Page -> times
            is Command.Swipe -> times
            is Command.Volume -> times
            is Command.Edit -> times
            else -> 1
        }
        return if (n > 1) " x$n" else ""
    }

    private companion object {
        const val TAG = "Amber"

        /** Like the text field search: enough for any real screen, cheap on a huge one. */
        const val MAX_NODES = 1_500
        const val MAX_NAMED = 3

        /** Diagnostics name a few more of the apps a name could be than the message does. */
        const val MAX_TRACED = 5
        const val LAUNCH_CHECKS = 15
        const val LAUNCH_CHECK_MS = 100L

        /** A frame or two for the bubble to be gone, then time for the capture itself. */
        const val CAPTURE_SETTLE_MS = 100L
        const val CAPTURE_MS = 700L

        const val GESTURE_REFUSED = "Android did not take the gesture. Switch Amber off and on once under Accessibility."
    }
}
