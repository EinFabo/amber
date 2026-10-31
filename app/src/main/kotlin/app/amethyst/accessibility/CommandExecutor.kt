package app.amethyst.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
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
import app.amethyst.core.commands.CommandOutcome
import app.amethyst.core.commands.Direction
import app.amethyst.core.commands.GesturePath
import app.amethyst.core.commands.GlobalAction
import app.amethyst.core.commands.RepeatRun
import app.amethyst.core.commands.Repeats
import app.amethyst.core.commands.Repetition
import app.amethyst.core.commands.ScrollAction
import app.amethyst.core.commands.ScrollPick
import app.amethyst.core.commands.ScrollTarget
import app.amethyst.core.commands.Scrollable
import app.amethyst.core.commands.SettingsPage
import app.amethyst.core.commands.Step
import app.amethyst.core.commands.Stroke
import app.amethyst.core.commands.VolumeChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Carries out a voice command - the only place Amber acts for the user. Every command passes
 * one gate first: voice commands switched on, the phone unlocked. Nothing on the screen is read
 * beyond which nodes can scroll, how large they are and whether anything moved; a text command
 * reads the field being written in, nothing else. Logcat gets the kind of command only, never
 * what was heard, which app or any text.
 */
class CommandExecutor(private val service: AmethystAccessibilityService, private val graph: Graph) {

    private val device = DeviceControls(service)
    private val editor = FieldEditor(service, service.textInserter)

    suspend fun execute(command: Command): CommandOutcome {
        if (!graph.settings.value.commands.enabled) return CommandOutcome.Refused("Voice commands are off.")
        if (service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) {
            return CommandOutcome.Refused("Unlock the phone first.")
        }
        Log.i(TAG, "command: ${command.kind()}")
        return when (command) {
            is Command.Navigate -> navigate(command.action, command.times)
            is Command.OpenApp -> open(command.name)
            is Command.Scroll -> scroll(command.direction, command.times)
            is Command.ScrollToEnd -> scrollToEnd(command.direction)
            is Command.Page -> page(command.direction, command.times)
            is Command.Swipe -> swipe(command.direction, command.times)
            is Command.Media -> withContext(Dispatchers.Main) { device.press(command.key) }
            is Command.Volume -> withContext(Dispatchers.Main) { volume(command.change, command.times) }
            is Command.Torch -> withContext(Dispatchers.Main) { device.torch(command.on) }
            is Command.OpenSettings -> openSettings(command.page)
            is Command.Edit -> withContext(Dispatchers.Default) { editor.apply(command.edit, command.times) }
        }
    }

    /** The service is going away: let go of what watches the phone. */
    fun close() = device.close()

    private suspend fun navigate(action: GlobalAction, times: Int): CommandOutcome {
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
            return if (withoutBubble { service.performGlobalAction(id) }) CommandOutcome.Done(done) else CommandOutcome.Failed(refused)
        }
        return steps(times) { if (withContext(Dispatchers.Main) { service.performGlobalAction(id) }) Step.Done else Step.Failed(refused) }
            .outcome(done, "nothing changed")
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
            withContext(Dispatchers.Main) { service.hideBubbleForCapture(false) }
        }
    }

    private suspend fun open(name: String): CommandOutcome {
        val apps = withContext(Dispatchers.IO) { Launcher.apps(service) }
        return when (val match = AppMatch.find(name, apps) { it.label }) {
            is AppMatch.Found -> {
                val app = match.app
                val intent = service.packageManager.getLaunchIntentForPackage(app.packageName)
                    ?: return CommandOutcome.Failed("${app.label} cannot be opened.")
                start(intent, app.label, app.packageName)
            }
            is AppMatch.Ambiguous -> CommandOutcome.Failed(
                "Which one? ${match.candidates.take(MAX_NAMED).joinToString { it.label }} - say the full name.",
            )
            AppMatch.NotFound -> CommandOutcome.Failed("No app called “$name”.")
        }
    }

    /** A page of Android's settings - opened only; what is on it the user switches. */
    private suspend fun openSettings(page: SettingsPage): CommandOutcome {
        val (actions, label) = when (page) {
            SettingsPage.WIFI -> listOf(Settings.ACTION_WIFI_SETTINGS) to "Wi-Fi settings"
            SettingsPage.BLUETOOTH -> listOf(Settings.ACTION_BLUETOOTH_SETTINGS) to "Bluetooth settings"
            SettingsPage.SOUND -> listOf(Settings.ACTION_SOUND_SETTINGS) to "Sound settings"
            SettingsPage.DISPLAY -> listOf(Settings.ACTION_DISPLAY_SETTINGS) to "Display settings"
            SettingsPage.BATTERY -> listOf(Intent.ACTION_POWER_USAGE_SUMMARY, Settings.ACTION_BATTERY_SAVER_SETTINGS) to "Battery settings"
        }
        // The settings app has a launcher icon, so the manifest's launcher query makes it visible here.
        val (intent, owner) = actions.firstNotNullOfOrNull { action ->
            val intent = Intent(action)
            intent.resolveActivity(service.packageManager)?.packageName?.let { intent to it }
        } ?: return CommandOutcome.Failed("This phone has no $label page Amber can open.")
        return start(intent, label, owner)
    }

    /**
     * The system binds accessibility services allowed to start activities from the background.
     * A start Android blocks anyway fails without an error, so the app in front is checked.
     */
    private suspend fun start(intent: Intent, label: String, packageName: String): CommandOutcome {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = withContext(Dispatchers.Main) { runCatching { service.startActivity(intent) }.isSuccess }
        if (!started) return CommandOutcome.Failed("Android did not let Amber open $label.")
        repeat(LAUNCH_CHECKS) {
            if (service.appInFront == packageName) return CommandOutcome.Done("Opened $label")
            delay(LAUNCH_CHECK_MS)
        }
        return CommandOutcome.Failed("$label did not come to the front - Android may have blocked the start.")
    }

    // ---- Scrolling, pages, swiping ----

    /** Once: as v1, by the node's action or a stroke. More often: until the count or until nothing moves. */
    private suspend fun scroll(direction: Direction, times: Int): CommandOutcome {
        val did = "Scrolled ${direction.word()}"
        if (times == 1) return once(did) { scrollStep(direction, look(), page = false) }
        return moving(times) { scrollStep(direction, it, page = false) }.outcome(did, "nothing more to scroll")
    }

    private suspend fun page(direction: Direction, times: Int): CommandOutcome {
        val did = "Paged ${direction.word()}"
        if (times == 1) return once(did) { scrollStep(direction, look(), page = true) }
        return moving(times) { scrollStep(direction, it, page = true) }.outcome(did, "nothing more to scroll")
    }

    /** "Ganz nach unten": step after step until nothing moves any more, at most [Repeats.MAX_TO_END]. */
    private suspend fun scrollToEnd(direction: Direction): CommandOutcome {
        val end = when (direction) {
            Direction.DOWN -> "the bottom"
            Direction.UP -> "the top"
            Direction.LEFT -> "the left end"
            Direction.RIGHT -> "the right end"
        }
        val run = moving(Repeats.MAX_TO_END) { scrollStep(direction, it, page = false) }
        return when (run.stop) {
            Step.Unchanged -> CommandOutcome.Done(if (run.done == 0) "Already at $end" else "Scrolled to $end")
            null -> CommandOutcome.Done("Scrolled ${direction.word()} ${run.done} times - still not at $end")
            else -> run.outcome("Scrolled ${direction.word()}", "nothing more to scroll")
        }
    }

    private suspend fun swipe(direction: Direction, times: Int): CommandOutcome =
        steps(times) {
            if (dispatch(GesturePath.swipe(direction, screenWidth(), screenHeight(), service.bubbleFrame()))) Step.Done
            else Step.Failed(GESTURE_REFUSED)
        }.outcome("Swiped ${direction.word()}", "nothing changed")

    private suspend fun once(did: String, act: suspend () -> Boolean): CommandOutcome =
        if (act()) CommandOutcome.Done(did) else CommandOutcome.Failed(GESTURE_REFUSED)

    /** [times] steps with [Repetition.PAUSE_MS] between them, stopping at the first that fails. */
    private suspend fun steps(times: Int, step: suspend () -> Step): RepeatRun =
        Repetition.run(times, pause = { delay(Repetition.PAUSE_MS) }, step = step)

    /**
     * Repeated scrolling: each step acts, lets the content settle for [Repetition.PAUSE_MS] - the
     * pause between steps - and looks again; a step after which nothing moved ends the run.
     */
    private suspend fun moving(times: Int, act: suspend (Screen) -> Boolean): RepeatRun {
        var screen = look()
        return Repetition.run(times, pause = {}) {
            if (!act(screen)) return@run Step.Failed(GESTURE_REFUSED)
            delay(Repetition.PAUSE_MS)
            val after = look()
            val moved = after.layout != screen.layout
            screen = after
            if (moved) Step.Done else Step.Unchanged
        }
    }

    /**
     * One scroll: the picked node's page or scroll action, else - or when it refuses - a stroke.
     * False only when Android did not take the stroke.
     */
    private suspend fun scrollStep(direction: Direction, screen: Screen, page: Boolean): Boolean {
        val nodes = screen.scrollables.map { it.first }
        val pick = if (page && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ScrollTarget.pickPage(nodes, direction) else ScrollTarget.pick(nodes, direction)
        if (pick is ScrollPick.Node) {
            val node = screen.scrollables.first { it.first.id == pick.id }.second
            if (withContext(Dispatchers.Main) { node.performAction(pick.action.id()) }) return true
        }
        return dispatch(GesturePath.scroll(direction, screenWidth(), screenHeight(), service.bubbleFrame()))
    }

    /** False when the service may not perform gestures yet or the gesture was cancelled. */
    private suspend fun dispatch(stroke: Stroke): Boolean = withContext(Dispatchers.Main) {
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
                    if (result.isActive) result.resume(true)
                }

                override fun onCancelled(description: GestureDescription?) {
                    if (result.isActive) result.resume(false)
                }
            }
            val sent = runCatching { service.dispatchGesture(gesture, callback, null) }.getOrDefault(false)
            if (!sent && result.isActive) result.resume(false)
        }
    }

    /**
     * What can scroll, and a fingerprint of where everything is ([layout]): the bounds of the
     * nodes and the list positions of their items - no text - so a step that moved nothing shows.
     */
    private class Screen(val scrollables: List<Pair<Scrollable, AccessibilityNodeInfo>>, val layout: Int)

    private suspend fun look(): Screen = withContext(Dispatchers.Default) { screen() }

    /**
     * Every node that can scroll in the apps' windows - not Amber's own overlays, not the
     * keyboard - bounded like the text field search. Only bounds, class, actions and an item's
     * position in its list are read.
     */
    private fun screen(): Screen {
        val windows = runCatching { service.windows }.getOrDefault(emptyList()).filter {
            it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
        val found = mutableListOf<Pair<Scrollable, AccessibilityNodeInfo>>()
        val bounds = Rect()
        var layout = 1
        var visited = 0
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName == service.packageName) continue
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val node = queue.removeFirst()
                visited++
                node.getBoundsInScreen(bounds)
                val item = node.collectionItemInfo
                layout = 31 * layout + bounds.hashCode()
                if (item != null) layout = 31 * layout + item.rowIndex * 1_009 + item.columnIndex
                if (node.isScrollable && node.isVisibleToUser) {
                    val actions = node.actionList.mapNotNull { it.scrollAction() }.toSet()
                    if (actions.isNotEmpty()) {
                        val grid = node.collectionInfo
                        val vertical = ScrollTarget.isVertical(node.className?.toString(), grid?.rowCount ?: 0, grid?.columnCount ?: 0, actions)
                        found += Scrollable(found.size, bounds.width().toLong() * bounds.height(), window.isActive, vertical, actions) to node
                    }
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
            }
        }
        return Screen(found, layout)
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

    private suspend fun volume(change: VolumeChange, times: Int): CommandOutcome {
        if (change == VolumeChange.MUTE || change == VolumeChange.UNMUTE) return device.mute(change == VolumeChange.MUTE)
        device.volumeProblem()?.let { return CommandOutcome.Failed(it) }
        val louder = change == VolumeChange.LOUDER
        val run = steps(times) { device.volumeStep(louder) }
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
        const val LAUNCH_CHECKS = 15
        const val LAUNCH_CHECK_MS = 100L

        /** A frame or two for the bubble to be gone, then time for the capture itself. */
        const val CAPTURE_SETTLE_MS = 100L
        const val CAPTURE_MS = 700L

        const val GESTURE_REFUSED = "Android did not take the gesture. Switch Amber off and on once under Accessibility."
    }
}
