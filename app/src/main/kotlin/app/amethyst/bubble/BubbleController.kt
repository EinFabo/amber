package app.amethyst.bubble

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import app.amethyst.DebugLog
import app.amethyst.core.bubble.BubbleDock
import app.amethyst.core.bubble.BubbleGesture
import app.amethyst.core.bubble.BubblePlacement
import app.amethyst.core.bubble.BubbleSide
import app.amethyst.core.bubble.BubbleSpring
import app.amethyst.core.bubble.BubbleVisibility
import app.amethyst.core.bubble.QuickAction
import app.amethyst.core.bubble.QuickMenuGeometry
import app.amethyst.core.bubble.QuickSlot
import app.amethyst.core.bubble.SafeArea
import app.amethyst.core.bubble.Screen
import app.amethyst.core.bubble.Box
import app.amethyst.core.settings.BubbleSettings
import app.amethyst.dictation.DictationState
import kotlin.math.roundToInt

/**
 * Puts the bubble on screen and translates touches into dictation commands.
 *
 * The window type is TYPE_ACCESSIBILITY_OVERLAY, which is why Amber never asks for
 * "display over other apps": an accessibility service may own such a window directly.
 */
class BubbleController(
    private val service: AccessibilityService,
    /** Returns whether the tap started a recording (not stopped one) - for the double tap. */
    private val onTap: () -> Boolean,
    /** A second tap right after a tap that started recording; false when it made no command. */
    private val onDoubleTap: () -> Boolean,
    /** The quick menu's slots for the state now, in the app in front ([currentApp]). */
    private val quickMenu: (currentApp: String?) -> List<QuickSlot?>,
    /** Runs a quick menu action; false when it could not (the run moved on, nothing to do). */
    private val onQuickAction: (action: QuickAction, currentApp: String?) -> Boolean,
    /**
     * The bubble was let go and docked on [side]. [edgeY]: its new height along the edge
     * without a keyboard ([BubbleSettings.edgeY]); [aboveKeyboardDp]: its new height above a
     * keyboard. Null leaves that one as it was - a drag sets only the height it was made at.
     */
    private val onMoved: (side: BubbleSide, edgeY: Float?, aboveKeyboardDp: Int?) -> Unit,
    /** The bubble was dropped on the hide target while this app was in front. */
    private val onHideIn: (packageName: String) -> Unit,
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
    private val sparkView = SparkView(service).apply {
        // Nothing left to draw: off the screen, until the next listening or spark brings it back.
        onIdle = { visibility = View.INVISIBLE }
    }
    private var sparkParams: WindowManager.LayoutParams? = null
    private val sparkAfterAppear = Runnable {
        showSparks()
        sparkView.spark()
    }
    private var settings = BubbleSettings()
    private var commandsOn = false

    /** What a touch means; see [BubbleGesture]. */
    private val gesture = BubbleGesture(
        touchSlop = ViewConfiguration.get(service).scaledTouchSlop.toFloat(),
        deadZone = dp(settings.sizeDp) * MENU_DEAD_ZONE,
    )

    /** The quick menu, in a window of its own that takes no touches; added while it is open. */
    private val menuView = QuickMenuView(service)
    private var menuParams: WindowManager.LayoutParams? = null

    /** The open menu's slots, as they were when it opened. */
    private var menuSlots: List<QuickSlot?> = emptyList()
    private var hapticsEnabled = true

    private var textFieldFocused = false
    private var locked = false
    private var dictating = false

    /** The app in front, as far as the service can tell; the bubble stays out of hidden ones. */
    private var currentApp: String? = null
    private var shown = false
    private val hideLater = Runnable { applyVisibility(immediate = true) }

    /**
     * The vanish animation has run its course: the bubble's window is off the screen
     * (INVISIBLE), not merely transparent - a transparent window would stay a layer the
     * compositor blends over every app.
     */
    private var vanished = false

    /** A screenshot is being taken: both windows are off the screen until it is done. */
    private var capturing = false

    /** The edge the bubble is docked on. */
    private var side = BubbleSide.RIGHT

    /** Where the bubble rests along its edge when no keyboard is in the way. */
    private var restY = 0

    /** Its gap to the keys of a docked keyboard, in pixels. */
    private var aboveKeyboard = 0

    /** The settings the position was last taken from; an echo of the bubble's own drop moves nothing. */
    private var placedFrom: BubbleSettings? = null

    /** Top edge of the keyboard on screen, or null while none is shown. */
    private var keyboardTop: Int? = null

    /** A keyboard that floats instead of docking: kept clear of, never pushed above. */
    private var floatingKeyboard: Box? = null

    /** The field being typed in, so the bubble does not sit on it. */
    private var field: Box? = null
    /** A keyboard glide or the spring to the edge - one at a time. */
    private var moveAnimator: Animator? = null

    /** The drop zone shown while dragging; added only for a drag that may hide the bubble. */
    private val hideTarget = HideTarget(service)
    private var hideTargetParams: WindowManager.LayoutParams? = null

    /** A finger is on the bubble; it owns the position until it lifts. */
    private var touching = false

    fun attach() {
        if (attached) return

        val windows = windowManager
        if (windows == null) {
            Log.e(TAG, "no WindowManager available - the bubble cannot be shown")
            return
        }

        val size = dp(settings.sizeDp)
        takePosition(settings, size)
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
            x = targetX(size)
            y = targetY(size, x)
        }

        view.setOnTouchListener(TouchHandler())
        // Start hidden when the bubble is only wanted with a text field; the first focus
        // event pops it in.
        shown = wantsVisible()
        view.restingOpacity = settings.restingOpacity
        view.alpha = if (shown) view.targetAlpha() else 0f
        if (!shown) layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        vanished = !shown
        applyWindowVisibility()

        // Sparks first: overlays of one type stack in the order they are added, so the bubble
        // comes out on top and the sparks rise from behind its disc, not across it.
        attachSparks(windows, layoutParams)
        try {
            windows.addView(view, layoutParams)
            params = layoutParams
            attached = true
            Log.i(TAG, "bubble attached as accessibility overlay")
        } catch (e: Exception) {
            Log.e(TAG, "could not attach bubble overlay", e)
            sparkParams?.let { runCatching { windows.removeView(sparkView) } }
            sparkParams = null
            return
        }
    }

    /** Added before the bubble, so it lies underneath - seen only around the disc, never touched. */
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
        // Nothing to draw at rest: on the screen only while sparks fly (showSparks).
        sparkView.visibility = View.INVISIBLE
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
        removeHideTarget()
        closeMenu()
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
    fun applySettings(bubbleSettings: BubbleSettings, commands: Boolean, doubleTapCommands: Boolean = commands) {
        settings = bubbleSettings
        commandsOn = commands
        gesture.doubleTapCommands = doubleTapCommands
        hapticsEnabled = bubbleSettings.haptics
        view.restingOpacity = bubbleSettings.restingOpacity
        if (shown) view.settleAlpha()
        val layoutParams = params ?: return
        // A drag in progress wins over a settings write that may be its own echo, and the
        // echo of a drop - same side, same heights - leaves the spring to finish its swing.
        if (!touching && !samePosition(placedFrom, bubbleSettings)) {
            val size = dp(bubbleSettings.sizeDp)
            takePosition(bubbleSettings, size)
            layoutParams.width = size
            layoutParams.height = size
            moveAnimator?.cancel()
            layoutParams.x = targetX()
            layoutParams.y = targetY()
            update()
        }
        applyVisibility(immediate = true)
    }

    /**
     * The screen turned or changed size: the bubble goes back to its edge - the same side,
     * the same share of the height. Measured again once the new size has settled.
     */
    fun screenChanged() {
        replace()
        view.post { replace() }
    }

    private fun replace() {
        val layoutParams = params ?: return
        if (touching) return
        moveAnimator?.cancel()
        takePosition(settings, layoutParams.height)
        layoutParams.x = targetX()
        layoutParams.y = targetY()
        update()
    }

    /** Side and both heights from [from], in pixels for a bubble of [size] on this screen. */
    private fun takePosition(from: BubbleSettings, size: Int) {
        side = from.side
        restY = BubbleDock.yOf(from.edgeY, size, screen())
        aboveKeyboard = dp(from.aboveKeyboardDp)
        placedFrom = from
    }

    private fun samePosition(a: BubbleSettings?, b: BubbleSettings): Boolean =
        a != null && a.side == b.side && a.edgeY == b.edgeY && a.aboveKeyboardDp == b.aboveKeyboardDp && a.sizeDp == b.sizeDp

    /**
     * Keeps the bubble above the keyboard. An accessibility overlay is not resized by the IME
     * the way an app window is, so without this the bubble would sit on top of the keys.
     */
    fun setKeyboard(top: Int?, floating: Box?) {
        if (top == keyboardTop && floating == floatingKeyboard) return
        keyboardTop = top
        floatingKeyboard = floating
        Log.i(TAG, "keyboard top ${top ?: "none"}${floating?.let { ", floating $it" } ?: ""}")
        reposition()
    }

    /** Where the focused field is on screen, or null without one. */
    fun setField(bounds: Box?) {
        if (bounds == field) return
        field = bounds
        reposition()
    }

    private fun reposition() {
        if (touching) return
        val layoutParams = params ?: return
        moveTo(layoutParams, targetY())
    }

    /** The bubble's left edge on its side, clear of a cutout or bar there. */
    private fun targetX(size: Int = params?.width ?: dp(settings.sizeDp)): Int = BubbleDock.x(side, size, screen())

    private fun targetY(size: Int = params?.height ?: dp(settings.sizeDp), x: Int = params?.x ?: targetX(size)): Int =
        BubblePlacement.y(
            restY = restY,
            x = x,
            size = size,
            screen = screen(),
            gap = dp(KEYBOARD_GAP_DP),
            keyboardTop = keyboardTop,
            aboveKeyboard = aboveKeyboard,
            avoid = listOfNotNull(floatingKeyboard, field),
        )

    /**
     * Glides to [y] along the edge - only while shown; a hidden bubble is simply put there,
     * so it appears in the right place. The edge is taken as it is now, without a glide.
     */
    private fun moveTo(layoutParams: WindowManager.LayoutParams, y: Int) {
        moveAnimator?.cancel()
        layoutParams.x = targetX()
        if (!shown || layoutParams.y == y || !ValueAnimator.areAnimatorsEnabled()) {
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

    /** The lock screen is up (or the screen off): no bubble unless a dictation runs. */
    fun setLocked(on: Boolean) {
        if (on == locked) return
        locked = on
        applyVisibility(immediate = on)
    }

    fun setTextFieldFocused(focused: Boolean) {
        textFieldFocused = focused
        applyVisibility(immediate = false)
    }

    /** The app in front changed; in an app on the hidden list the bubble steps aside. */
    fun setCurrentApp(packageName: String?) {
        if (packageName == currentApp) return
        currentApp = packageName
        applyVisibility(immediate = false)
    }

    private fun wantsVisible(): Boolean =
        BubbleVisibility.wanted(dictating, currentApp, settings, textFieldFocused, commandsOn, locked)

    /** The run in progress is a voice command; see [BubbleView.command]. */
    fun setCommand(on: Boolean) {
        view.command = on
    }

    /** Invisible at once, sparks included, without the vanish animation - for a screenshot. */
    fun setHiddenForCapture(hidden: Boolean) {
        capturing = hidden
        applyWindowVisibility()
        // Back as they were: sparks only if some are still to be drawn.
        if (hidden) sparkView.visibility = View.INVISIBLE else if (sparkView.busy) showSparks()
    }

    /** On the screen unless it vanished or a screenshot is being taken. */
    private fun applyWindowVisibility() {
        view.visibility = if (vanished || capturing) View.INVISIBLE else View.VISIBLE
    }

    /**
     * Brings the spark window back where the bubble is now - it is not moved while off the
     * screen - before anything is drawn in it.
     */
    private fun showSparks() {
        if (capturing || sparkView.visibility == View.VISIBLE) return
        val sparks = sparkParams ?: return
        val bubble = params ?: return
        if (followBubble(sparks, bubble) && attached) {
            runCatching { windowManager?.updateViewLayout(sparkView, sparks) }
                .onFailure { Log.w(TAG, "could not move the spark overlay", it) }
        }
        sparkView.visibility = View.VISIBLE
    }

    /** Where the bubble is on screen while it shows - a dispatched gesture keeps clear of it. */
    fun frame(): Box? = params?.takeIf { shown }?.let { Box(it.x, it.y, it.x + it.width, it.y + it.height) }

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
                // Place first, then show: it appears where it belongs - above the keyboard
                // that is on its way - instead of sliding there once it is visible.
                if (!touching) {
                    moveAnimator?.cancel()
                    layoutParams.x = targetX()
                    layoutParams.y = targetY()
                }
                update()
                vanished = false
                applyWindowVisibility()
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
                    vanished = true
                    applyWindowVisibility()
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
            is DictationState.Commanded -> BubbleVisual.DONE
            is DictationState.Failed -> BubbleVisual.FAILED
        }
        // Fully there while it works, back to the chosen opacity at rest.
        if (shown) view.settleAlpha()
        if (state is DictationState.Listening) {
            view.amplitude = state.amplitude
            sparkView.amplitude = state.amplitude
        }
        if (state is DictationState.Transcribing) view.work = state.progress
        if (state is DictationState.Listening) showSparks()
        sparkView.listening = state is DictationState.Listening
        if (state is DictationState.Failed) sparkView.extinguish()
        when (state) {
            is DictationState.Done, is DictationState.Commanded -> haptic(confirmConstant())
            is DictationState.Failed -> haptic(rejectConstant())
            else -> Unit
        }
    }

    /**
     * Tap toggles a dictation; a quick second tap may make it a voice command. Holding still
     * opens the quick menu ([BubbleGesture]); sliding to a slot and lifting runs it. Dragging
     * moves the bubble at any time, idle or mid-dictation, and docks it at an edge on release.
     * What a touch means is decided by [gesture]; this only moves windows and buzzes.
     */
    private inner class TouchHandler : View.OnTouchListener {
        /** Where the bubble was when the finger came down. */
        private var startX = 0
        private var startY = 0

        /** This drag may end on the hide target: idle, and an app other than Amber in front. */
        private var mayHide = false
        private var overTarget = false

        private var velocity: VelocityTracker? = null

        private val holdCheck = Runnable { handle(gesture.hold(SystemClock.uptimeMillis()), SystemClock.uptimeMillis()) }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val layoutParams = params ?: return false
            track(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    moveAnimator?.cancel()
                    closeMenu()
                    touching = true
                    startX = layoutParams.x
                    startY = layoutParams.y
                    mayHide = false
                    overTarget = false
                    gesture.down(event.rawX, event.rawY, event.eventTime, side)
                    // A finger held perfectly still produces no move events at all.
                    v.removeCallbacks(holdCheck)
                    v.postDelayed(holdCheck, BubbleGesture.HOLD_MS)
                }

                MotionEvent.ACTION_MOVE -> handle(gesture.move(event.rawX, event.rawY, event.eventTime), event.eventTime)

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(holdCheck)
                    touching = false
                    val result = if (event.actionMasked == MotionEvent.ACTION_UP) {
                        gesture.up(event.rawX, event.rawY, event.eventTime)
                    } else {
                        gesture.cancel()
                    }
                    handle(result, event.eventTime)
                    removeHideTarget()
                    mayHide = false
                    overTarget = false
                    velocity?.recycle()
                    velocity = null
                    // A hide that was held back while the finger was down.
                    applyVisibility(immediate = false)
                }

                else -> return false
            }
            return true
        }

        private fun handle(event: BubbleGesture.Event, at: Long) {
            val layoutParams = params ?: return
            when (event) {
                BubbleGesture.Event.None -> Unit

                is BubbleGesture.Event.Tap -> {
                    // A keyboard that came or went while the finger was down was not followed.
                    moveTo(layoutParams, targetY())
                    haptic(HapticFeedbackConstants.CONTEXT_CLICK)
                    // The recording the first tap started becomes a command instead of stopping.
                    val wasDoubleTap = event.doubleTap && onDoubleTap()
                    gesture.tapDone(at, startedRecording = !wasDoubleTap && onTap())
                }

                is BubbleGesture.Event.Drag -> {
                    if (event.started) {
                        mayHide = !dictating && currentApp.let { it != null && it != service.packageName }
                        Log.i(
                            TAG,
                            "drag in ${DebugLog.redact(currentApp)}: " + when {
                                mayHide -> "hide target shown"
                                dictating -> "no hide target, a dictation is running"
                                else -> "no hide target, no other app in front"
                            },
                        )
                        if (mayHide) showHideTarget()
                    }
                    val (width, height) = screenSize()
                    layoutParams.x = (startX + event.dx).roundToInt().coerceIn(0, width - layoutParams.width)
                    layoutParams.y = (startY + event.dy).roundToInt().coerceIn(0, height - layoutParams.height)
                    update()
                    if (mayHide) {
                        val over = isOverHideTarget(layoutParams)
                        if (over != overTarget) {
                            overTarget = over
                            hideTarget.active = over
                            if (over) haptic(HapticFeedbackConstants.CONTEXT_CLICK)
                        }
                    }
                }

                is BubbleGesture.Event.DragEnd -> {
                    val hideIn = currentApp.takeIf { mayHide && overTarget && !event.cancelled }
                    if (hideIn != null) {
                        // Back to where it was: the place the user chose stays for other apps.
                        haptic(confirmConstant())
                        layoutParams.x = startX
                        layoutParams.y = startY
                        update()
                        onHideIn(hideIn)
                    } else {
                        val (vx, vy) = if (event.cancelled) 0f to 0f else flingVelocity()
                        dropped(layoutParams, vx, vy)
                    }
                }

                BubbleGesture.Event.MenuOpen -> openMenu(startX + layoutParams.width / 2f, startY + layoutParams.height / 2f)

                is BubbleGesture.Event.Menu -> {
                    // The bubble leans toward the finger, at most a third of its size.
                    val lean = QuickMenuGeometry.rubberBand(event.dx, event.dy, layoutParams.width * LEAN)
                    layoutParams.x = startX + lean.x.roundToInt()
                    layoutParams.y = startY + lean.y.roundToInt()
                    update()
                    if (event.slotChanged) {
                        menuView.lit = event.slot
                        val slot = event.slot?.let { menuSlots.getOrNull(it) }
                        when {
                            event.slot == null -> haptic(HapticFeedbackConstants.CLOCK_TICK)
                            slot?.enabled == true -> haptic(segmentConstant())
                            else -> Unit
                        }
                    }
                }

                is BubbleGesture.Event.Select -> {
                    val slot = menuSlots.getOrNull(event.slot)
                    closeMenu()
                    settleAfterMenu(layoutParams)
                    if (slot == null || !slot.enabled) {
                        haptic(rejectConstant())
                    } else {
                        Log.i(TAG, "quick menu: ${slot.action.name}")
                        haptic(if (onQuickAction(slot.action, currentApp)) confirmConstant() else rejectConstant())
                    }
                }

                BubbleGesture.Event.MenuClosed -> {
                    closeMenu()
                    settleAfterMenu(layoutParams)
                }
            }
        }

        /**
         * In screen coordinates: the window moves with the finger, so the view's own
         * coordinates hardly change during a drag.
         */
        private fun track(event: MotionEvent) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
            }
            val tracker = velocity ?: return
            val screen = MotionEvent.obtain(event)
            screen.setLocation(event.rawX, event.rawY)
            tracker.addMovement(screen)
            screen.recycle()
        }

        /** Pixels per second at the lift. */
        private fun flingVelocity(): Pair<Float, Float> {
            val tracker = velocity ?: return 0f to 0f
            tracker.computeCurrentVelocity(1000)
            return tracker.xVelocity to tracker.yVelocity
        }
    }

    /** Back from leaning toward the finger, to its edge - wherever a keyboard has put that meanwhile. */
    private fun settleAfterMenu(layoutParams: WindowManager.LayoutParams) {
        springTo(layoutParams, targetX(), targetY(), 0f, 0f)
    }

    /**
     * Shows the quick menu around a bubble centred at ([cx], [cy]). Its slots are taken now
     * and not rebuilt under the finger; the action is checked again when it runs.
     */
    private fun openMenu(cx: Float, cy: Float) {
        val windows = windowManager ?: return
        menuSlots = quickMenu(currentApp)
        haptic(HapticFeedbackConstants.LONG_PRESS)
        if (menuParams != null) return
        val (width, height) = screenSize()
        val size = params?.width ?: dp(settings.sizeDp)
        menuView.side = side
        menuView.centreX = cx
        menuView.centreY = cy
        menuView.radius = size * MENU_RADIUS
        menuView.lit = null
        menuView.slots = menuSlots
        val menu = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            screenCoordinates()
            x = 0
            y = 0
        }
        menuView.alpha = 0f
        runCatching { windows.addView(menuView, menu) }
            .onSuccess {
                menuParams = menu
                menuView.animate().alpha(1f).setDuration(MENU_FADE_MS).start()
                Log.i(TAG, "quick menu: ${menuSlots.joinToString { it?.let { slot -> slot.action.name + if (slot.enabled) "" else " (off)" } ?: "-" }}")
            }
            .onFailure { Log.w(TAG, "could not show the quick menu", it) }
    }

    private fun closeMenu() {
        if (menuParams == null) return
        menuParams = null
        menuView.animate().cancel()
        runCatching { windowManager?.removeView(menuView) }
    }

    private fun hideTargetFrame(): Triple<Int, Int, Int> {
        val (width, height) = screenSize()
        val size = dp(HIDE_TARGET_DP)
        return Triple((width - size) / 2, height - size - dp(HIDE_TARGET_LIFT_DP), size)
    }

    private fun showHideTarget() {
        val windows = windowManager ?: return
        if (hideTargetParams != null) return
        val (x, y, size) = hideTargetFrame()
        val target = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            screenCoordinates()
            this.x = x
            this.y = y
        }
        hideTarget.active = false
        hideTarget.alpha = 0f
        runCatching { windows.addView(hideTarget, target) }
            .onSuccess {
                hideTargetParams = target
                hideTarget.animate().alpha(1f).setDuration(150).start()
            }
            .onFailure { Log.w(TAG, "could not show the hide target", it) }
    }

    private fun removeHideTarget() {
        if (hideTargetParams == null) return
        hideTargetParams = null
        hideTarget.animate().cancel()
        runCatching { windowManager?.removeView(hideTarget) }
    }

    /** The bubble's centre within the target's reach - generous, a drop is quick and rough. */
    private fun isOverHideTarget(layoutParams: WindowManager.LayoutParams): Boolean {
        val (x, y, size) = hideTargetFrame()
        val dx = (layoutParams.x + layoutParams.width / 2f) - (x + size / 2f)
        val dy = (layoutParams.y + layoutParams.height / 2f) - (y + size / 2f)
        val reach = size * 0.5f + layoutParams.width * 0.5f
        return dx * dx + dy * dy < reach * reach
    }

    /**
     * Let go: to the nearer edge, or the one a sideways fling throws it to, on a spring. The
     * height it was let go at becomes its height above the keyboard while one is up, else
     * its resting height - each remembered on its own.
     */
    private fun dropped(layoutParams: WindowManager.LayoutParams, velocityX: Float, velocityY: Float) {
        val size = layoutParams.width
        val screen = screen()
        val dock = BubbleDock.release(layoutParams.x, layoutParams.y, velocityX, velocityY, size, screen)
        side = dock.side
        val keyboard = keyboardTop
        val edgeY: Float?
        val aboveDp: Int?
        if (keyboard != null) {
            aboveKeyboard = BubblePlacement.aboveKeyboard(dock.y, size, keyboard)
            aboveDp = px(aboveKeyboard)
            edgeY = null
        } else {
            restY = dock.y
            edgeY = BubbleDock.fractionOf(restY, size, screen)
            aboveDp = null
        }
        // The echo of this drop's settings write must not move the bubble again.
        settings = settings.copy(
            side = side,
            edgeY = edgeY ?: settings.edgeY,
            aboveKeyboardDp = aboveDp ?: settings.aboveKeyboardDp,
        )
        placedFrom = settings
        springTo(layoutParams, dock.x, targetY(size, dock.x), velocityX, 0f)
        onMoved(side, edgeY, aboveDp)
    }

    /**
     * To ([x], [y]) on a slightly damped spring that takes the fling's speed along: a few
     * pixels past the edge and back, instead of a hard stop.
     */
    private fun springTo(layoutParams: WindowManager.LayoutParams, x: Int, y: Int, velocityX: Float, velocityY: Float) {
        moveAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            layoutParams.x = x
            layoutParams.y = y
            update()
            return
        }
        val spring = BubbleSpring()
        var motionX = BubbleSpring.Motion(layoutParams.x.toDouble(), velocityX.toDouble())
        var motionY = BubbleSpring.Motion(layoutParams.y.toDouble(), velocityY.toDouble())
        moveAnimator = TimeAnimator().apply {
            setTimeListener { animator, _, deltaMs ->
                val seconds = deltaMs.coerceIn(0L, MAX_FRAME_MS) / 1000.0
                motionX = spring.step(motionX, x.toDouble(), seconds)
                motionY = spring.step(motionY, y.toDouble(), seconds)
                val done = spring.atRest(motionX, x.toDouble()) && spring.atRest(motionY, y.toDouble())
                layoutParams.x = if (done) x else motionX.position.roundToInt()
                layoutParams.y = if (done) y else motionY.position.roundToInt()
                update()
                if (done) animator.end()
            }
            start()
        }
    }

    /** The display with what the bubble keeps clear of: bars, gesture bar, cutout. */
    private fun screen(): Screen {
        val (width, height) = screenSize()
        return Screen(width, height, safeArea(width, height))
    }

    private fun safeArea(width: Int, height: Int): SafeArea {
        val windows = windowManager
        if (windows != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windows.currentWindowMetrics.windowInsets
            val bars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val gestures = insets.getInsetsIgnoringVisibility(WindowInsets.Type.mandatorySystemGestures())
            val area = SafeArea(bars.left, bars.top, bars.right, maxOf(bars.bottom, gestures.bottom))
            if (area != SafeArea()) return area
        }
        // Android 9 and 10, or no insets known: the bars' usual sizes, the navigation bar at
        // the bottom only in portrait (in landscape it may sit on either side).
        val status = systemDimen("status_bar_height")
        val navigation = if (height > width) systemDimen("navigation_bar_height") else 0
        return SafeArea(top = status, bottom = navigation)
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun systemDimen(name: String): Int {
        val id = service.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else 0
    }

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
        // Off the screen it stays where it is; showSparks() brings it along first.
        if (sparkView.visibility != View.VISIBLE) return
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

    /** Entering a slot of the menu: a light tick, the segment one where it exists (API 34). */
    private fun segmentConstant(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
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

    private fun px(value: Int): Int =
        (value / service.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TAG = "Amber"
        const val HIDE_DELAY_MS = 400L
        const val APPEAR_SPARK_DELAY_MS = 100L
        const val KEYBOARD_GAP_DP = 12
        const val HIDE_TARGET_DP = 64

        /** Above the gesture bar and clear of the bottom edge, where thumbs end a drag. */
        const val HIDE_TARGET_LIFT_DP = 72
        /** Close to the keyboard's own slide, so both arrive together. */
        const val MOVE_MS = 250L

        /** A longer frame (the phone busy) advances the spring by this much only. */
        const val MAX_FRAME_MS = 32L
        val KEYBOARD_EASING = PathInterpolator(0.2f, 0f, 0f, 1f)

        /** Inside this share of the bubble's size around the press nothing in the menu is lit. */
        const val MENU_DEAD_ZONE = 0.5f

        /** How far out the menu's labels sit, in bubble sizes. */
        const val MENU_RADIUS = 1.9f
        const val MENU_FADE_MS = 120L

        /** How far the bubble leans toward the finger while the menu is open, in bubble sizes. */
        const val LEAN = 0.35f
    }
}
