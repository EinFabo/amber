package app.nocturne.bubble

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import app.nocturne.core.settings.BubbleEdge
import app.nocturne.core.settings.BubbleSettings
import app.nocturne.dictation.DictationState
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Puts the bubble on screen and translates touches into dictation commands.
 *
 * The window type is TYPE_ACCESSIBILITY_OVERLAY, which is why Nocturne never asks for
 * "display over other apps": an accessibility service may own such a window directly.
 */
class BubbleController(
    private val service: AccessibilityService,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
) {

    private val windowManager =
        service.getSystemService(WindowManager::class.java)

    private val view = BubbleView(service)
    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var settings = BubbleSettings()
    private var hapticsEnabled = true

    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    fun attach() {
        if (attached) return

        val size = dp(settings.sizeDp)
        val layoutParams = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = horizontalPosition(settings.edge, size)
            y = verticalPosition(settings.verticalBias, size)
        }

        view.setOnTouchListener(TouchHandler())

        try {
            windowManager.addView(view, layoutParams)
            params = layoutParams
            attached = true
            Log.i(TAG, "bubble attached as accessibility overlay")
        } catch (e: Exception) {
            Log.e(TAG, "could not attach bubble overlay", e)
        }
    }

    fun detach() {
        if (!attached) return
        runCatching { windowManager.removeView(view) }
            .onFailure { Log.w(TAG, "bubble already gone", it) }
        attached = false
        params = null
    }

    fun applySettings(bubbleSettings: BubbleSettings) {
        settings = bubbleSettings
        hapticsEnabled = bubbleSettings.haptics
        val layoutParams = params ?: return
        val size = dp(bubbleSettings.sizeDp)
        layoutParams.width = size
        layoutParams.height = size
        layoutParams.x = horizontalPosition(bubbleSettings.edge, size)
        update()
    }

    fun setTextFieldFocused(focused: Boolean) {
        view.dimmed = settings.dimWhenNoTextField && !focused
    }

    fun render(state: DictationState) {
        view.visual = when (state) {
            DictationState.Idle -> BubbleVisual.IDLE
            DictationState.Starting -> BubbleVisual.LISTENING
            is DictationState.Listening -> BubbleVisual.LISTENING
            DictationState.Transcribing -> BubbleVisual.TRANSCRIBING
            is DictationState.Done -> BubbleVisual.DONE
            is DictationState.Failed -> BubbleVisual.FAILED
        }
        if (state is DictationState.Listening) view.amplitude = state.amplitude
        when (state) {
            is DictationState.Done -> haptic(confirmConstant())
            is DictationState.Failed -> haptic(rejectConstant())
            else -> Unit
        }
    }

    private inner class TouchHandler : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var downAt = 0L
        private var dragging = false
        private var longPressFired = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val layoutParams = params ?: return false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = layoutParams.x
                    startY = layoutParams.y
                    downAt = SystemClock.elapsedRealtime()
                    dragging = false
                    longPressFired = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                    if (dragging) {
                        layoutParams.x = (startX + dx).roundToInt()
                        layoutParams.y = (startY + dy).roundToInt()
                        update()
                    } else if (!longPressFired &&
                        SystemClock.elapsedRealtime() - downAt > longPressTimeout
                    ) {
                        longPressFired = true
                        haptic(HapticFeedbackConstants.LONG_PRESS)
                        onLongPress()
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    val heldFor = SystemClock.elapsedRealtime() - downAt
                    when {
                        dragging -> snapToNearestEdge()
                        longPressFired -> Unit
                        heldFor > longPressTimeout -> {
                            haptic(HapticFeedbackConstants.LONG_PRESS)
                            onLongPress()
                        }
                        else -> {
                            haptic(HapticFeedbackConstants.CONTEXT_CLICK)
                            onTap()
                        }
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) snapToNearestEdge()
                    return true
                }
            }
            return false
        }
    }

    /** Keeps the bubble parked against a screen edge instead of floating mid-screen. */
    private fun snapToNearestEdge() {
        val layoutParams = params ?: return
        val screenWidth = screenSize().first
        val size = layoutParams.width
        val onLeft = layoutParams.x + size / 2 < screenWidth / 2
        layoutParams.x = if (onLeft) EDGE_MARGIN_PX else screenWidth - size - EDGE_MARGIN_PX
        update()
    }

    private fun horizontalPosition(edge: BubbleEdge, size: Int): Int {
        val screenWidth = screenSize().first
        return when (edge) {
            BubbleEdge.LEFT -> EDGE_MARGIN_PX
            BubbleEdge.RIGHT -> screenWidth - size - EDGE_MARGIN_PX
        }
    }

    private fun verticalPosition(bias: Float, size: Int): Int =
        ((screenSize().second - size) * bias.coerceIn(0f, 1f)).roundToInt()

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = android.util.DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun update() {
        if (!attached) return
        val layoutParams = params ?: return
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
            .onFailure { Log.w(TAG, "could not update bubble layout", it) }
    }

    // CONFIRM and REJECT only exist from API 30; below that fall back to what is available.
    private fun confirmConstant(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.CONTEXT_CLICK
        }

    private fun rejectConstant(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }

    private fun haptic(constant: Int) {
        if (!hapticsEnabled) return
        view.performHapticFeedback(constant)
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TAG = "Nocturne"
        const val EDGE_MARGIN_PX = 8
    }
}
