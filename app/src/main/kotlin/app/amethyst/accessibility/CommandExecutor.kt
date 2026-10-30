package app.amethyst.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import app.amethyst.Graph
import app.amethyst.apps.InstalledApp
import app.amethyst.apps.Launcher
import app.amethyst.core.commands.AppMatch
import app.amethyst.core.commands.Command
import app.amethyst.core.commands.CommandOutcome
import app.amethyst.core.commands.Direction
import app.amethyst.core.commands.GesturePath
import app.amethyst.core.commands.GlobalAction
import app.amethyst.core.commands.ScrollAction
import app.amethyst.core.commands.ScrollPick
import app.amethyst.core.commands.ScrollTarget
import app.amethyst.core.commands.Scrollable
import app.amethyst.core.commands.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Carries out a voice command - the only place Amber acts for the user. Every command passes
 * one gate first: voice commands switched on, the phone unlocked. Nothing on the screen is read
 * beyond which nodes can scroll and how large they are; logcat gets the kind of command only,
 * never what was heard or which app.
 */
class CommandExecutor(private val service: AmethystAccessibilityService, private val graph: Graph) {

    suspend fun execute(command: Command): CommandOutcome {
        if (!graph.settings.value.commands.enabled) return CommandOutcome.Refused("Voice commands are off.")
        if (service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) {
            return CommandOutcome.Refused("Unlock the phone first.")
        }
        Log.i(TAG, "command: ${command.kind()}")
        return when (command) {
            is Command.Navigate -> navigate(command.action)
            is Command.OpenApp -> open(command.name)
            is Command.Scroll -> scroll(command.direction)
            is Command.Swipe -> swipe(command.direction)
        }
    }

    private suspend fun navigate(action: GlobalAction): CommandOutcome {
        val (id, done) = when (action) {
            GlobalAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK to "Back"
            GlobalAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME to "Home screen"
            GlobalAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS to "Recent apps"
            GlobalAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS to "Notifications"
            GlobalAction.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS to "Quick settings"
            GlobalAction.LOCK_SCREEN -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN to "Locked"
            GlobalAction.SCREENSHOT -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT to "Screenshot taken"
        }
        val ok = if (action == GlobalAction.SCREENSHOT) withoutBubble { service.performGlobalAction(id) }
        else withContext(Dispatchers.Main) { service.performGlobalAction(id) }
        return if (ok) CommandOutcome.Done(done) else CommandOutcome.Failed("Android refused: ${done.lowercase()}.")
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
            is AppMatch.Found -> launch(match.app)
            is AppMatch.Ambiguous -> CommandOutcome.Failed(
                "Which one? ${match.candidates.take(MAX_NAMED).joinToString { it.label }} - say the full name.",
            )
            AppMatch.NotFound -> CommandOutcome.Failed("No app called “$name”.")
        }
    }

    /**
     * The system binds accessibility services allowed to start activities from the background.
     * A start Android blocks anyway fails without an error, so the app in front is checked.
     */
    private suspend fun launch(app: InstalledApp): CommandOutcome {
        val intent = service.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return CommandOutcome.Failed("${app.label} cannot be opened.")
        val started = withContext(Dispatchers.Main) { runCatching { service.startActivity(intent) }.isSuccess }
        if (!started) return CommandOutcome.Failed("Android did not let Amber open ${app.label}.")
        repeat(LAUNCH_CHECKS) {
            if (service.appInFront == app.packageName) return CommandOutcome.Done("Opened ${app.label}")
            delay(LAUNCH_CHECK_MS)
        }
        return CommandOutcome.Failed("${app.label} did not come to the front - Android may have blocked the start.")
    }

    private suspend fun scroll(direction: Direction): CommandOutcome {
        val done = "Scrolled ${direction.word()}"
        val found = withContext(Dispatchers.Default) { scrollables() }
        return when (val pick = ScrollTarget.pick(found.map { it.first }, direction)) {
            is ScrollPick.Node -> {
                val node = found.first { it.first.id == pick.id }.second
                val ok = withContext(Dispatchers.Main) { node.performAction(pick.action.id()) }
                if (ok) CommandOutcome.Done(done) else stroke(GesturePath.scroll(direction, screenWidth(), screenHeight(), service.bubbleFrame()), done)
            }
            ScrollPick.None -> stroke(GesturePath.scroll(direction, screenWidth(), screenHeight(), service.bubbleFrame()), done)
        }
    }

    private suspend fun swipe(direction: Direction): CommandOutcome =
        stroke(GesturePath.swipe(direction, screenWidth(), screenHeight(), service.bubbleFrame()), "Swiped ${direction.word()}")

    private suspend fun stroke(stroke: Stroke, done: String): CommandOutcome =
        if (dispatch(stroke)) {
            CommandOutcome.Done(done)
        } else {
            CommandOutcome.Failed("Android did not take the gesture. Switch Amber off and on once under Accessibility.")
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
     * Every node that can scroll in the apps' windows - not Amber's own overlays, not the
     * keyboard - bounded like the text field search. Only bounds, class and actions are read.
     */
    private fun scrollables(): List<Pair<Scrollable, AccessibilityNodeInfo>> {
        val windows = runCatching { service.windows }.getOrDefault(emptyList()).filter {
            it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
        val found = mutableListOf<Pair<Scrollable, AccessibilityNodeInfo>>()
        val bounds = Rect()
        var visited = 0
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName == service.packageName) continue
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val node = queue.removeFirst()
                visited++
                if (node.isScrollable && node.isVisibleToUser) {
                    val actions = node.actionList.mapNotNull { it.scrollAction() }.toSet()
                    if (actions.isNotEmpty()) {
                        node.getBoundsInScreen(bounds)
                        val grid = node.collectionInfo
                        val vertical = ScrollTarget.isVertical(node.className?.toString(), grid?.rowCount ?: 0, grid?.columnCount ?: 0, actions)
                        found += Scrollable(found.size, bounds.width().toLong() * bounds.height(), window.isActive, vertical, actions) to node
                    }
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
            }
        }
        return found
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
        else -> null
    }

    private fun ScrollAction.id(): Int = when (this) {
        ScrollAction.UP -> AccessibilityAction.ACTION_SCROLL_UP.id
        ScrollAction.DOWN -> AccessibilityAction.ACTION_SCROLL_DOWN.id
        ScrollAction.LEFT -> AccessibilityAction.ACTION_SCROLL_LEFT.id
        ScrollAction.RIGHT -> AccessibilityAction.ACTION_SCROLL_RIGHT.id
        ScrollAction.FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        ScrollAction.BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
    }

    private fun Direction.word(): String = name.lowercase()

    /** For logcat: what kind of command, never its words or the app. */
    private fun Command.kind(): String = when (this) {
        is Command.Navigate -> "navigate ${action.name.lowercase()}"
        is Command.OpenApp -> "open app"
        is Command.Scroll -> "scroll ${direction.word()}"
        is Command.Swipe -> "swipe ${direction.word()}"
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
    }
}
