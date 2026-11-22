package app.amethyst.accessibility

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import app.amethyst.core.bubble.Box
import app.amethyst.core.bubble.MessagePlacement
import app.amethyst.core.bubble.MessageTiming
import app.amethyst.core.bubble.Screen
import app.amethyst.ui.theme.AmberColors
import kotlin.math.roundToInt

/**
 * A short message over whatever app is open, in place of a toast.
 *
 * Android drops the toast of an app in the background whose notifications are off - the
 * default from Android 13 until the user allows them - and Amber is in the background exactly
 * when it has something to say: the user is dictating into another app. An accessibility
 * overlay is the service's own window and shows regardless, like the bubble (no "draw over
 * other apps" grant). It takes no touch and no focus, sits above the keys and clear of the
 * bubble, and is spoken by a screen reader as a toast would be. One at a time: a new message
 * takes the place of the one on screen. Main thread.
 */
class MessagePill(
    private val service: AccessibilityService,
    /** Amber's screens are dark: the pill wears the same colours. */
    private val dark: () -> Boolean,
    /** The display and its bars, as the bubble measures them; null without the bubble's controller. */
    private val screen: () -> Screen?,
    /** The bubble's frame while it is on screen. */
    private val bubble: () -> Box?,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable { fadeOut() }
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private val windows: WindowManager? get() = service.getSystemService(WindowManager::class.java)

    fun show(text: String) {
        val windows = windows ?: return
        handler.removeCallbacks(hide)
        val pill = view ?: newView()
        pill.animate().cancel()
        style(pill)
        pill.text = text

        val area = screen() ?: fallbackScreen()
        val maxWidth = MessagePlacement.maxWidth(area, dp(MARGIN_DP), dp(MAX_WIDTH_DP))
        pill.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val (x, y) = MessagePlacement.place(area, pill.measuredWidth, pill.measuredHeight, dp(GAP_DP), keyboardTop(), bubble())

        val layout = params ?: newParams()
        layout.x = x
        layout.y = y
        layout.width = pill.measuredWidth
        layout.height = pill.measuredHeight
        try {
            if (view == null) {
                pill.alpha = if (animated()) 0f else 1f
                windows.addView(pill, layout)
                view = pill
                params = layout
            } else {
                windows.updateViewLayout(pill, layout)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not show the message", e)
            remove()
            return
        }
        if (animated()) pill.animate().alpha(1f).setDuration(FADE_MS).start() else pill.alpha = 1f
        // A screen reader says it, as it says a toast. A live region speaks only when a view
        // already on screen changes, and most messages come in a window of their own, just added.
        @Suppress("DEPRECATION")
        pill.announceForAccessibility(text)
        handler.postDelayed(hide, MessageTiming.shownMs(text))
    }

    /** Off at once - the service is going. */
    fun remove() {
        handler.removeCallbacks(hide)
        val pill = view ?: return
        view = null
        params = null
        pill.animate().cancel()
        runCatching { windows?.removeView(pill) }
    }

    private fun fadeOut() {
        val pill = view ?: return
        if (!animated()) return remove()
        pill.animate().alpha(0f).setDuration(FADE_MS).withEndAction { if (view === pill) remove() }.start()
    }

    private fun newView(): TextView = TextView(service).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
        gravity = Gravity.CENTER
        // Toasts are cut at two lines from Android 16 on; the texts are written for that
        // (tools/check_strings.py), and the pill keeps to it too.
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(PADDING_H_DP), dp(PADDING_V_DP), dp(PADDING_H_DP), dp(PADDING_V_DP))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Again on every message: the theme may have changed since the last one. */
    private fun style(pill: TextView) {
        val colors = AmberColors.of(dark())
        pill.setTextColor(colors.text.toArgb())
        pill.background = GradientDrawable().apply {
            cornerRadius = dp(CORNER_DP).toFloat()
            setColor((if (colors.dark) colors.elevated else colors.surface).toArgb())
            setStroke(dp(1), colors.outline.toArgb())
        }
    }

    private fun newParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // Plain screen coordinates, the same as the keyboard's bounds and the bubble's frame.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /**
     * The docked keyboard's top edge, looked up now: the keyboard tracker only watches while
     * the bubble is on screen, and a message comes seldom enough for one window lookup.
     */
    private fun keyboardTop(): Int? = runCatching {
        val keyboard = service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return@runCatching null
        val frame = Rect().also(keyboard::getBoundsInScreen)
        frame.top.takeIf { !frame.isEmpty && it > 0 }
    }.getOrNull()

    private fun fallbackScreen(): Screen {
        val metrics = service.resources.displayMetrics
        return Screen(metrics.widthPixels, metrics.heightPixels)
    }

    private fun animated(): Boolean = ValueAnimator.areAnimatorsEnabled()

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TAG = "Embersay"
        const val FADE_MS = 150L
        const val TEXT_SP = 14f
        const val PADDING_H_DP = 18
        const val PADDING_V_DP = 10
        const val CORNER_DP = 20
        const val MARGIN_DP = 16
        const val MAX_WIDTH_DP = 360
        const val GAP_DP = 16
    }
}
