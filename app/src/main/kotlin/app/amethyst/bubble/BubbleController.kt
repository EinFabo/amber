package app.amethyst.bubble

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.PathInterpolator
import app.amethyst.core.settings.BubbleSettings
import app.amethyst.dictation.DictationState
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Puts the bubble on screen and translates touches into dictation commands.
 *
 * The window type is TYPE_ACCESSIBILITY_OVERLAY, which is why Amber never asks for
 * "display over other apps": an accessibility service may own such a window directly.
 */
class BubbleController(
    private val service: AccessibilityService,
    private val onTap: () -> Unit,
    /** Press and hold: start dictating. Returns false when nothing was started. */
    private val onHoldStart: () -> Boolean,
    /** Finger lifted after a hold that started a dictation. */
    private val onHoldEnd: () -> Unit,
    /** The bubble was dragged to a new resting place, as fractions of the free screen area. */
    private val onMoved: (horizontalBias: Float, verticalBias: Float) -> Unit,
) {

    /**
     * Nullable on purpose: getSystemService can return null for a service this context does
     * not offer, and whether the public SDK stub declares that is not something to gamble a
     * build on. Treating it as absent is also the honest behaviour - without a WindowManager
     * there simply is no bubble, and everything else still works.
     */
    private val windowManager: WindowManager? =
        service.getSystemService(WindowManager::class.java)

    private val view = BubbleView(service)
    private var params: WindowManager.LayoutParams? = null
    private var attached = false

    /**
     * Ember's sparks rise above the bubble, out of its window. They get a second overlay window
     * that follows the bubble and takes no touches, so the air above the bubble never swallows
     * a tap meant for the app underneath.
     */
    private val sparkView = SparkView(service)
    private var sparkParams: WindowManager.LayoutParams? = null
    private val sparkAfterAppear = Runnable { sparkView.spark() }
    private var settings = BubbleSettings()
    private var hapticsEnabled = true

    private var textFieldFocused = false
    private var dictating = false
    private var shown = false
    private val hideLater = Runnable { applyVisibility(immediate = true) }

    /** Where the bubble rests when no keyboard is in the way: settings, or where it was dropped. */
    private var baseY = 0

    /** Top edge of the keyboard on screen, or null while none is shown. */
    private var keyboardTop: Int? = null
    private var moveAnimator: ValueAnimator? = null

    /** A finger is on the bubble; it owns the position until it lifts. */
    private var touching = false

    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

    fun attach() {
        if (attached) return

        val windows = windowManager
        if (windows == null) {
            Log.e(TAG, "no WindowManager available - the bubble cannot be shown")
            return
        }

        val size = dp(settings.sizeDp)
        val layoutParams = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            screenCoordinates()
            x = horizontalPosition(settings.horizontalBias, size)
            y = verticalPosition(settings.verticalBias, size)
        }
        baseY = layoutParams.y
        layoutParams.y = targetY()

        view.setOnTouchListener(TouchHandler())
        // Start hidden when the bubble is only wanted with a text field; the first focus
        // event pops it in.
        shown = wantsVisible()
        view.alpha = if (shown) 1f else 0f
        if (!shown) layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        try {
            windows.addView(view, layoutParams)
            params = layoutParams
            attached = true
            Log.i(TAG, "bubble attached as accessibility overlay")
        } catch (e: Exception) {
            Log.e(TAG, "could not attach bubble overlay", e)
            return
        }
        attachSparks(windows, layoutParams)
    }

    /** Added after the bubble, so it lies on top - drawn over it, never touched. */
    private fun attachSparks(windows: WindowManager, bubble: WindowManager.LayoutParams) {
        val sparks = WindowManager.LayoutParams(
            bubble.width,
            bubble.height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            screenCoordinates()
        }
        followBubble(sparks, bubble)
        try {
            windows.addView(sparkView, sparks)
            sparkParams = sparks
        } catch (e: Exception) {
            // The bubble works without its sparks.
            Log.w(TAG, "could not attach the spark overlay", e)
        }
    }

    /** Same column as the bubble, reaching [Fire.SPARK_HEADROOM] bubble sizes above it. */
    private fun followBubble(sparks: WindowManager.LayoutParams, bubble: WindowManager.LayoutParams): Boolean {
        val headroom = (bubble.width * Fire.SPARK_HEADROOM).roundToInt()
        val x = bubble.x
        val y = bubble.y - headroom
        val width = bubble.width
        val height = bubble.height + headroom
        if (sparks.x == x && sparks.y == y && sparks.width == width && sparks.height == height) return false
        sparks.x = x
        sparks.y = y
        sparks.width = width
        sparks.height = height
        return true
    }

    /**
     * Makes x/y plain screen coordinates. By default a window is laid out below the status bar
     * and above the navigation bar, so y = 0 sat a status bar's height down the screen - and
     * the keyboard top, which is in screen coordinates, was missed by exactly that much. The
     * bubble ended up partly on the keys.
     */
    private fun WindowManager.LayoutParams.screenCoordinates() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    fun detach() {
        view.removeCallbacks(hideLater)
        view.removeCallbacks(sparkAfterAppear)
        moveAnimator?.cancel()
        if (!attached) return
        runCatching { windowManager?.removeView(view) }
            .onFailure { Log.w(TAG, "bubble already gone", it) }
        if (sparkParams != null) {
            runCatching { windowManager?.removeView(sparkView) }
                .onFailure { Log.w(TAG, "spark overlay already gone", it) }
        }
        attached = false
        params = null
        sparkParams = null
    }

    /**
     * Safe to call before [attach]: the settings are stored either way, so a later attach
     * builds the window from them instead of from defaults.
     */
    fun applySettings(bubbleSettings: BubbleSettings) {
        settings = bubbleSettings
        hapticsEnabled = bubbleSettings.haptics
        val layoutParams = params ?: return
        // A drag in progress wins over a settings write that may be its own echo.
        if (!touching) {
            val size = dp(bubbleSettings.sizeDp)
            layoutParams.width = size
            layoutParams.height = size
            layoutParams.x = horizontalPosition(bubbleSettings.horizontalBias, size)
            baseY = verticalPosition(bubbleSettings.verticalBias, size)
            moveAnimator?.cancel()
            layoutParams.y = targetY()
            update()
        }
        applyVisibility(immediate = true)
    }

    /**
     * Keeps the bubble above the keyboard. An accessibility overlay is not resized by the IME
     * the way an app window is, so without this the bubble would sit on top of the keys.
     */
    fun setKeyboardTop(top: Int?) {
        if (top == keyboardTop) return
        keyboardTop = top
        Log.i(TAG, "keyboard top ${top ?: "none"}")
        if (touching) return
        val layoutParams = params ?: return
        moveTo(layoutParams, targetY())
    }

    private fun targetY(): Int {
        val size = params?.height ?: dp(settings.sizeDp)
        val top = keyboardTop ?: return baseY
        return minOf(baseY, top - size - dp(KEYBOARD_GAP_DP)).coerceAtLeast(0)
    }

    private fun moveTo(layoutParams: WindowManager.LayoutParams, y: Int) {
        moveAnimator?.cancel()
        if (!shown || layoutParams.y == y) {
            layoutParams.y = y
            update()
            return
        }
        moveAnimator = ValueAnimator.ofInt(layoutParams.y, y).apply {
            duration = MOVE_MS
            interpolator = KEYBOARD_EASING
            addUpdateListener {
                layoutParams.y = it.animatedValue as Int
                update()
            }
            start()
        }
    }

    fun setTextFieldFocused(focused: Boolean) {
        textFieldFocused = focused
        applyVisibility(immediate = false)
    }

    private fun wantsVisible(): Boolean =
        !settings.onlyWithTextField || textFieldFocused || dictating

    /**
     * Showing is immediate; hiding waits a moment. Moving from one field to the next reports
     * a focus loss followed by a focus gain, and without the delay the bubble would blink.
     * While hidden the window stops taking touches, so it never blocks what is underneath.
     */
    private fun applyVisibility(immediate: Boolean) {
        view.removeCallbacks(hideLater)
        val layoutParams = params ?: return
        if (wantsVisible()) {
            if (!shown) {
                shown = true
                layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                update()
                view.appear()
                // Ember arrives alight: one spark once the bubble is mostly in.
                view.removeCallbacks(sparkAfterAppear)
                view.postDelayed(sparkAfterAppear, APPEAR_SPARK_DELAY_MS)
            }
        } else if (shown) {
            // Never pull the bubble out from under a finger.
            if (!immediate || touching) {
                view.postDelayed(hideLater, HIDE_DELAY_MS)
                return
            }
            shown = false
            view.removeCallbacks(sparkAfterAppear)
            sparkView.extinguish()
            view.vanish {
                // A focus may have arrived during the animation.
                if (!shown) {
                    layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    update()
                }
            }
        }
    }

    fun render(state: DictationState) {
        // Stays up for the whole dictation and its result, even if the field loses focus.
        dictating = state != DictationState.Idle
        applyVisibility(immediate = false)
        view.visual = when (state) {
            DictationState.Idle -> BubbleVisual.IDLE
            DictationState.Starting -> BubbleVisual.LISTENING
            is DictationState.Listening -> BubbleVisual.LISTENING
            is DictationState.Transcribing -> BubbleVisual.TRANSCRIBING
            is DictationState.Done -> BubbleVisual.DONE
            is DictationState.Failed -> BubbleVisual.FAILED
        }
        if (state is DictationState.Listening) {
            view.amplitude = state.amplitude
            sparkView.amplitude = state.amplitude
        }
        if (state is DictationState.Transcribing) view.expectedWorkMs = state.expectedMs
        sparkView.listening = state is DictationState.Listening
        if (state is DictationState.Failed) sparkView.extinguish()
        when (state) {
            is DictationState.Done -> haptic(confirmConstant())
            is DictationState.Failed -> haptic(rejectConstant())
            else -> Unit
        }
    }

    /**
     * Tap toggles a dictation. Press and hold dictates until the finger lifts, and lifting
     * sends it - push to talk. Dragging moves the bubble at any time: idle, during a toggled
     * dictation, and during a hold, which keeps recording while the bubble travels.
     *
     * The hold is timed with a callback rather than detected in ACTION_MOVE: a finger held
     * perfectly still produces no move events at all. A drag that begins before the hold
     * fires is only a move and starts nothing.
     */
    private inner class TouchHandler : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var holding = false

        private val holdStarted = Runnable {
            if (dragging) return@Runnable
            holding = onHoldStart()
            if (holding) haptic(HapticFeedbackConstants.LONG_PRESS)
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val layoutParams = params ?: return false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    moveAnimator?.cancel()
                    touching = true
                    downX = event.rawX
                    downY = event.rawY
                    startX = layoutParams.x
                    startY = layoutParams.y
                    dragging = false
                    holding = false
                    v.postDelayed(holdStarted, HOLD_MS)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        // Before the hold fired this is a plain move; after, the hold goes on.
                        if (!holding) v.removeCallbacks(holdStarted)
                    }
                    if (dragging) {
                        val (width, height) = screenSize()
                        layoutParams.x = (startX + dx).roundToInt().coerceIn(0, width - layoutParams.width)
                        layoutParams.y = (startY + dy).roundToInt().coerceIn(0, height - layoutParams.height)
                        update()
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(holdStarted)
                    touching = false
                    val tapped = event.actionMasked == MotionEvent.ACTION_UP && !holding && !dragging
                    if (holding) {
                        haptic(HapticFeedbackConstants.CONTEXT_CLICK)
                        onHoldEnd()
                    }
                    // A keyboard that came or went while the finger was down was not followed.
                    if (dragging) dropped(layoutParams) else moveTo(layoutParams, targetY())
                    if (tapped) {
                        haptic(HapticFeedbackConstants.CONTEXT_CLICK)
                        onTap()
                    }
                    holding = false
                    dragging = false
                    // A hide that was held back while the finger was down.
                    applyVisibility(immediate = false)
                    return true
                }
            }
            return false
        }
    }

    /**
     * The bubble stays where it was let go. Dropped onto the keyboard, it rests there in the
     * settings but is lifted above the keys until they go away.
     */
    private fun dropped(layoutParams: WindowManager.LayoutParams) {
        baseY = layoutParams.y
        moveTo(layoutParams, targetY())
        val (width, height) = screenSize()
        val freeX = (width - layoutParams.width).coerceAtLeast(1)
        val freeY = (height - layoutParams.height).coerceAtLeast(1)
        onMoved(layoutParams.x.toFloat() / freeX, baseY.toFloat() / freeY)
    }

    private fun horizontalPosition(bias: Float, size: Int): Int =
        ((screenSize().first - size) * bias.coerceIn(0f, 1f)).roundToInt()

    private fun verticalPosition(bias: Float, size: Int): Int =
        ((screenSize().second - size) * bias.coerceIn(0f, 1f)).roundToInt()

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val windows = windowManager
        if (windows != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windows.currentWindowMetrics.bounds
                return bounds.width() to bounds.height()
            }
            val metrics = android.util.DisplayMetrics()
            windows.defaultDisplay.getRealMetrics(metrics)
            return metrics.widthPixels to metrics.heightPixels
        }
        // Falls back to the display metrics, which are close enough for positioning.
        val metrics = service.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun update() {
        if (!attached) return
        val layoutParams = params ?: return
        runCatching { windowManager?.updateViewLayout(view, layoutParams) }
            .onFailure { Log.w(TAG, "could not update bubble layout", it) }
        val sparks = sparkParams ?: return
        if (followBubble(sparks, layoutParams)) {
            runCatching { windowManager?.updateViewLayout(sparkView, sparks) }
                .onFailure { Log.w(TAG, "could not move the spark overlay", it) }
        }
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
        const val TAG = "Amber"
        const val HIDE_DELAY_MS = 400L
        const val APPEAR_SPARK_DELAY_MS = 100L
        const val KEYBOARD_GAP_DP = 12
        /** Close to the keyboard's own slide, so both arrive together. */
        const val MOVE_MS = 250L
        val KEYBOARD_EASING = PathInterpolator(0.2f, 0f, 0f, 1f)

        /**
         * Long enough that a quick tap is never taken for a hold, short enough that push to
         * talk starts without a noticeable wait. The system long-press timeout (400-500 ms)
         * felt sluggish for this.
         */
        const val HOLD_MS = 300L
    }
}
