package app.amethyst.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.amethyst.core.bubble.KeyboardPolicy
import app.amethyst.core.bubble.KeyboardPolicy.Reading
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Reads the keyboard window from the accessibility window list and feeds it to
 * [KeyboardPolicy], which decides what the reading means. This class only measures, schedules
 * the next look and keeps a short log of what it saw for the diagnostics card - the keyboard
 * is the one thing that behaves differently on every phone, and without that log a report of
 * "it jumps" cannot be told apart from "it never sees the keyboard".
 */
class KeyboardTracker(
    private val service: AccessibilityService,
    /** The last few decisions, newest first, with their time since the field took focus. */
    private val log: MutableStateFlow<List<String>>,
    private val onTop: (Int?) -> Unit,
) {

    private val policy = KeyboardPolicy()
    private val handler = Handler(Looper.getMainLooper())
    private val check = Runnable { measure() }

    private var focused = false
    private var focusedAt = 0L
    private var lastLogged: String? = null

    fun focusChanged(isFocused: Boolean) {
        if (isFocused == focused) return
        focused = isFocused
        focusedAt = SystemClock.elapsedRealtime()
        apply(policy.onFocusChanged(isFocused, focusedAt, screenHeight()))
    }

    /**
     * Window changes come in bursts - the bubble's own moves report as window changes too - so
     * they collapse into one lookup instead of one round trip each.
     */
    fun windowsChanged() {
        handler.removeCallbacks(check)
        handler.postDelayed(check, COALESCE_MS)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
    }

    private fun measure() {
        apply(policy.onReading(read(), SystemClock.elapsedRealtime(), screenHeight()))
    }

    private fun apply(decision: KeyboardPolicy.Decision) {
        onTop(decision.top)
        record(decision)
        handler.removeCallbacks(check)
        decision.recheckInMs?.let { handler.postDelayed(check, it) }
    }

    private fun read(): Reading = runCatching {
        val all = service.windows
        val keyboard = all.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            // Looking into each window costs a round trip, so only while a field is focused.
            ?: keyboardPackage()?.takeIf { focused }?.let { ime ->
                all.firstOrNull { window -> window.root?.packageName?.toString() == ime }
            }
            ?: return@runCatching Reading.Absent

        val frame = Rect().also(keyboard::getBoundsInScreen)
        if (frame.isEmpty) return@runCatching Reading.Absent
        if (frame.height() <= screenHeight() * KeyboardPolicy.TALL_FRAME) {
            return@runCatching Reading.Window(frame.top, frame.bottom)
        }
        // A frame over most of the screen: find where the keys are actually drawn.
        drawnArea(keyboard, frame)?.let { Reading.Window(it.top, it.bottom) } ?: Reading.Unmeasurable
    }.getOrDefault(Reading.Absent)

    /**
     * The union of the keyboard's keys and labelled views - what it draws, as opposed to its
     * window frame. Walks a bounded part of the tree; only used for oversized frames.
     */
    private fun drawnArea(keyboard: AccessibilityWindowInfo, frame: Rect): Rect? {
        val root = keyboard.root ?: return null
        val frameArea = frame.width().toLong() * frame.height()
        val union = Rect()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>().apply { add(root to 0) }
        var visited = 0
        val bounds = Rect()
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++
            node.getBoundsInScreen(bounds)
            val area = bounds.width().toLong() * bounds.height()
            val meaningful = node.isClickable || node.isFocusable ||
                !node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty()
            if (meaningful && !bounds.isEmpty && area < frameArea * 0.9) union.union(bounds)
            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it to depth + 1) }
            }
        }
        return union.takeUnless { it.isEmpty }
    }

    private fun keyboardPackage(): String? = runCatching {
        Settings.Secure.getString(service.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    }.getOrNull()?.substringBefore('/')?.takeIf { it.isNotBlank() }

    /** The whole display, status and navigation bar included - window bounds use the same. */
    @Suppress("DEPRECATION")
    private fun screenHeight(): Int {
        val windows = service.getSystemService(WindowManager::class.java)
        if (windows != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return windows.currentWindowMetrics.bounds.height()
            val metrics = DisplayMetrics()
            windows.defaultDisplay.getRealMetrics(metrics)
            return metrics.heightPixels
        }
        return service.resources.displayMetrics.heightPixels
    }

    private fun record(decision: KeyboardPolicy.Decision) {
        val line = "${decision.why} → ${decision.top ?: "down"}"
        if (line == lastLogged) return
        lastLogged = line
        val since = SystemClock.elapsedRealtime() - focusedAt
        Log.i(TAG, "keyboard: $line (+$since ms)")
        log.value = (listOf("+$since ms  $line") + log.value).take(LOG_LINES)
    }

    private companion object {
        const val TAG = "Amber"
        const val COALESCE_MS = 16L
        const val MAX_NODES = 80
        const val MAX_DEPTH = 6
        const val LOG_LINES = 12
    }
}
