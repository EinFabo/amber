package app.amethyst.bubble

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import app.amethyst.DebugLog
import app.amethyst.core.bubble.BubbleDock
import app.amethyst.core.bubble.BubbleGesture
import app.amethyst.core.bubble.BubblePlacement
import app.amethyst.core.bubble.BubbleSide
import app.amethyst.core.bubble.BubbleSpring
import app.amethyst.core.bubble.BubbleVisibility
import app.amethyst.core.bubble.MicLook
import app.amethyst.core.bubble.QuickAction
import app.amethyst.core.bubble.QuickMenuGeometry
import app.amethyst.core.bubble.QuickSlot
import app.amethyst.core.bubble.SafeArea
import app.amethyst.core.bubble.Screen
import app.amethyst.core.bubble.Box
import app.amethyst.core.dictation.BubbleTap
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
    /** What the tap came to: a recording started (for the double tap), or refused while working. */
    private val onTap: () -> BubbleTap.Result,
    /** A second tap right after a tap that started recording; false when it made no command. */
    private val onDoubleTap: () -> Boolean,
    /** The quick menu's slots for the state now, in the app in front ([currentApp]). */
    private val quickMenu: (currentApp: String?) -> List<QuickSlot?>,
    /** Runs a quick menu action; false when it could not (the run moved on, nothing to do). */
    private val onQuickAction: (action: QuickAction, currentApp: String?) -> Boolean,
    /** The thumb let go on a grey button: say why it cannot run. */
    private val onQuickActionOff: (action: QuickAction, currentApp: String?) -> Unit,
    /** The bubble was let go and docked on [side], at [edgeY] along it ([BubbleSettings.edgeY]). */
    private val onMoved: (side: BubbleSide, edgeY: Float) -> Unit,
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
        deadZone = dp(MENU_DEAD_ZONE_DP).toFloat(),
    )

    /** The quick menu, in a window of its own that takes no touches; on the screen while it is open. */
    private val menuView = QuickMenuView(service)
    private var menuParams: WindowManager.LayoutParams? = null

    /** The safe area last read off the menu's window, on Android 9 and 10 ([menuInsets]). */
    private var knownMenuInsets: SafeArea? = null

    /** The open menu's slots, as they were when it opened. */
    private var menuSlots: List<QuickSlot?> = emptyList()
    private var hapticsEnabled = true
    private val haptics = Haptics(service)

    private var textFieldFocused = false
    private var locked = false
    private var dictating = false

    /** The notification shade or quick settings are pulled down ([app.amethyst.core.bubble.SystemShade]). */
    private var shadeOpen = false

    /**
     * The dictation listens, not paused: the fire throws sparks - but only while the bubble is
     * on the screen ([updateSparks]). Their window is its own and stays attached when the
     * bubble vanishes, so with the shade down they kept rising over the notifications
     * (Pixel 9a, 2026-11-23).
     */
    private var listening = false

    /** The app in front, as far as the service can tell; the bubble stays out of hidden ones. */
    private var currentApp: String? = null
    private var shown = false

    /** On screen (or on its way there), not hidden in this app or without a field. */
    val visible: Boolean get() = shown
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

    /** The bubble's place along its edge: where it sits unless that would be on the keys. */
    private var restY = 0

    /** The settings the position was last taken from; an echo of the bubble's own drop moves nothing. */
    private var placedFrom: BubbleSettings? = null

    /** Top edge of the keyboard on screen, or null while none is shown. */
    private var keyboardTop: Int? = null

    /** A keyboard that floats instead of docking: kept clear of, never pushed above. */
    private var floatingKeyboard: Box? = null
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
        keepBackGestureOff()
        // Start hidden when the bubble is only wanted with a text field; the first focus
        // event pops it in.
        shown = wantsVisible()
        view.restingOpacity = settings.restingOpacity
        view.alpha = if (shown) view.targetAlpha() else 0f
        if (!shown) layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        vanished = !shown
        applyWindowVisibility()

        // The menu and the sparks first: overlays of one type stack in the order they are
        // added, so the bubble comes out on top - the menu's shade never darkens it, its
        // buttons fly out from under it, and the sparks rise from behind its disc.
        attachMenu(windows)
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
            menuParams?.let { runCatching { windows.removeView(menuView) } }
            menuParams = null
            return
        }
    }

    /**
     * The quick menu's window: the whole screen, never touchable - the touch stays with the
     * bubble that was pressed. Added once, under the bubble, and off the screen (INVISIBLE)
     * except while the menu is open: added only when it opens, it would lie on top.
     */
    private fun attachMenu(windows: WindowManager) {
        val (width, height) = screenSize()
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
        menuView.visibility = View.INVISIBLE
        menuView.onClosed = { menuView.visibility = View.INVISIBLE }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Android 9 and 10 read the safe area off this window ([menuInsets]); they arrive
            // after a rotation, a moment later than the configuration. The bubble is put back
            // only when they changed - not on every dispatch, which may come with the menu.
            menuView.setOnApplyWindowInsetsListener { v, insets ->
                val now = menuInsets()
                if (now != knownMenuInsets) {
                    knownMenuInsets = now
                    v.post { replace() }
                }
                v.onApplyWindowInsets(insets)
            }
        }
        try {
            windows.addView(menuView, menu)
            menuParams = menu
        } catch (e: Exception) {
            // The bubble works without its menu: a hold then does nothing.
            Log.w(TAG, "could not attach the quick menu overlay", e)
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
        } else {
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
        if (menuParams != null) {
            runCatching { windowManager?.removeView(menuView) }
                .onFailure { Log.w(TAG, "quick menu overlay already gone", it) }
        }
        attached = false
        params = null
        sparkParams = null
        menuParams = null
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
        // echo of a drop - same side, same place - leaves the spring to finish its swing.
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
     * The screen turned or changed size, or the display size or font size changed: the bubble
     * goes back to its edge - the same side, the same share of the height - at its size in
     * the new pixels, and the menu takes the new sizes too. Until 0.35 both kept the pixels
     * they were made with until the service connected again. Measured again once the new size
     * has settled.
     */
    fun screenChanged() {
        menuView.sizesChanged()
        fitMenuWindow()
        replace()
        view.post { replace() }
    }

    private fun replace() {
        val layoutParams = params ?: return
        if (touching) return
        moveAnimator?.cancel()
        val size = dp(settings.sizeDp)
        layoutParams.width = size
        layoutParams.height = size
        takePosition(settings, size)
        layoutParams.x = targetX()
        layoutParams.y = targetY()
        update()
    }

    /**
     * The menu's window over the whole screen as it is now. Its insets are the bars' and the
     * cutout's on Android 9 and 10 ([safeArea]), so it follows a rotation at once rather than
     * on the menu's next opening.
     */
    private fun fitMenuWindow() {
        val menu = menuParams ?: return
        val (width, height) = screenSize()
        if (menu.width == width && menu.height == height) return
        menu.width = width
        menu.height = height
        runCatching { windowManager?.updateViewLayout(menuView, menu) }
            .onFailure { Log.w(TAG, "could not resize the quick menu", it) }
    }

    /**
     * A drag that starts at the bubble on its edge is the bubble's, not Back's: the edge is where
     * the gesture navigation's Back swipe begins, and pulling the bubble away from it went
     * back in the app underneath. The whole bubble is kept out of the system's gestures
     * (Android 10+), in the view's own coordinates - the window moving takes it along.
     */
    private fun keepBackGestureOff() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        view.addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
            v.systemGestureExclusionRects = listOf(Rect(0, 0, right - left, bottom - top))
        }
    }

    /** Side and place from [from], in pixels for a bubble of [size] on this screen. */
    private fun takePosition(from: BubbleSettings, size: Int) {
        side = from.side
        restY = BubbleDock.yOf(from.edgeY, size, screen())
        placedFrom = from
    }

    private fun samePosition(a: BubbleSettings?, b: BubbleSettings): Boolean =
        a != null && a.side == b.side && a.edgeY == b.edgeY && a.sizeDp == b.sizeDp

    /**
     * Keeps the bubble off the keys. An accessibility overlay is not resized by the IME the
     * way an app window is, so a bubble whose place is where the keyboard opens would lie on
     * them; any other place it keeps.
     */
    fun setKeyboard(top: Int?, floating: Box?) {
        if (top == keyboardTop && floating == floatingKeyboard) return
        keyboardTop = top
        floatingKeyboard = floating
        reposition("keyboard top ${top ?: "none"}${floating?.let { ", floating ${it.top}..${it.bottom}" } ?: ""}")
    }

    /** Told every move and why, for Diagnostics → Keyboard. */
    var onNote: ((String) -> Unit)? = null

    private fun reposition(cause: String) {
        if (touching) return
        val layoutParams = params ?: return
        val y = targetY()
        if (y != layoutParams.y) {
            val line = "bubble ${layoutParams.y} → $y ($cause)"
            // The note logs the line as well; without one it still has to reach logcat.
            onNote?.takeIf { shown }?.invoke(line) ?: Log.i(TAG, line)
        }
        moveTo(layoutParams, y)
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
            keyboards = listOfNotNull(floatingKeyboard),
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

    /** The shade came down or went up again: the bubble leaves at once, and comes back with it gone. */
    fun setShadeOpen(open: Boolean) {
        if (open == shadeOpen) return
        shadeOpen = open
        Log.i(TAG, if (open) "notification shade open: bubble hidden" else "notification shade closed")
        if (open) closeMenu()
        applyVisibility(immediate = open)
    }

    private fun wantsVisible(): Boolean =
        BubbleVisibility.wanted(dictating, currentApp, settings, textFieldFocused, commandsOn, locked, shadeOpen)

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
        if (hidden) closeMenu()
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
        if (capturing || !shown || sparkView.visibility == View.VISIBLE) return
        val sparks = sparkParams ?: return
        val bubble = params ?: return
        if (followBubble(sparks, bubble) && attached) {
            runCatching { windowManager?.updateViewLayout(sparkView, sparks) }
                .onFailure { Log.w(TAG, "could not move the spark overlay", it) }
        }
        sparkView.visibility = View.VISIBLE
    }

    /** Sparks fly while the dictation listens and the bubble is on the screen to throw them. */
    private fun updateSparks() {
        val on = listening && shown
        if (on) showSparks()
        sparkView.listening = on
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
                // Place first, then show: it appears where it belongs - off the keys of a
                // keyboard that is on its way - instead of sliding there once it is visible.
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
                // Back from behind the shade mid-dictation: the fire goes on.
                updateSparks()
            }
        } else if (shown) {
            // Never pull the bubble out from under a finger.
            if (!immediate || touching) {
                view.postDelayed(hideLater, HIDE_DELAY_MS)
                return
            }
            shown = false
            view.removeCallbacks(sparkAfterAppear)
            // No new sparks either, while it is away: a dictation goes on behind the shade.
            updateSparks()
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
            DictationState.Starting -> BubbleVisual.OPENING
            is DictationState.Listening -> when {
                state.paused -> BubbleVisual.PAUSED
                MicLook.of(state.open) == MicLook.OPENING -> BubbleVisual.OPENING
                else -> BubbleVisual.LISTENING
            }
            is DictationState.Transcribing -> BubbleVisual.TRANSCRIBING
            is DictationState.Done -> if (state.copiedOnly) BubbleVisual.CLIPBOARD else BubbleVisual.DONE
            is DictationState.Commanded -> BubbleVisual.DONE
            is DictationState.Failed -> BubbleVisual.FAILED
        }
        // Fully there while it works, back to the chosen opacity at rest.
        if (shown) view.settleAlpha()
        if (state is DictationState.Listening) {
            view.amplitude = state.amplitude
            sparkView.amplitude = state.amplitude
        }
        view.limitLeft = (state as? DictationState.Listening)?.limitLeft
        if (state is DictationState.Transcribing) view.work = state.progress
        // Paused, the fire throws no sparks.
        listening = state is DictationState.Listening && !state.paused && state.open
        updateSparks()
        if (state is DictationState.Failed) sparkView.extinguish()
        when (state) {
            is DictationState.Done -> haptic(if (state.copiedOnly) Haptics.Kind.REJECT else Haptics.Kind.CONFIRM)
            is DictationState.Commanded -> haptic(Haptics.Kind.CONFIRM)
            is DictationState.Failed -> haptic(Haptics.Kind.REJECT)
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
                    gesture.down(event.rawX, event.rawY, event.eventTime)
                    view.press(BubbleGesture.HOLD_MS)
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
                    view.release()
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
                    haptic(Haptics.Kind.CLICK)
                    // The recording the first tap started becomes a command instead of stopping.
                    val wasDoubleTap = event.doubleTap && onDoubleTap()
                    val result = if (wasDoubleTap) null else onTap()
                    // Being written: the tap changes nothing, and says so with a buzz and the hint.
                    if (result == BubbleTap.Result.STILL_WORKING) haptic(Haptics.Kind.REJECT)
                    gesture.tapDone(at, startedRecording = result == BubbleTap.Result.STARTED)
                }

                is BubbleGesture.Event.Drag -> {
                    if (event.started) {
                        view.release()
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
                            if (over) haptic(Haptics.Kind.CLICK)
                        }
                    }
                }

                is BubbleGesture.Event.DragEnd -> {
                    val hideIn = currentApp.takeIf { mayHide && overTarget && !event.cancelled }
                    if (hideIn != null) {
                        // Back to where it was: the place the user chose stays for other apps.
                        haptic(Haptics.Kind.CONFIRM)
                        layoutParams.x = startX
                        layoutParams.y = startY
                        update()
                        onHideIn(hideIn)
                    } else {
                        val (vx, vy) = if (event.cancelled) 0f to 0f else flingVelocity()
                        dropped(layoutParams, vx, vy)
                    }
                }

                BubbleGesture.Event.MenuOpen -> {
                    view.holdDone()
                    openMenu(startX + layoutParams.width / 2f, startY + layoutParams.height / 2f)
                }

                is BubbleGesture.Event.Menu -> {
                    if (event.slotChanged) {
                        menuView.lit = event.slot
                        val slot = event.slot?.let { menuSlots.getOrNull(it) }
                        when {
                            event.slot == null -> haptic(Haptics.Kind.TICK)
                            slot?.enabled == true -> haptic(Haptics.Kind.TICK)
                            else -> Unit
                        }
                    }
                }

                is BubbleGesture.Event.Select -> {
                    val slot = menuSlots.getOrNull(event.slot)
                    // The chosen button pops, the others fly back into the bubble.
                    closeMenu(animated = true, chosen = event.slot.takeIf { slot?.enabled == true })
                    settleAfterMenu(layoutParams)
                    if (slot == null || !slot.enabled) {
                        haptic(Haptics.Kind.REJECT)
                        // A grey button says why it is grey; an empty place has nothing to say.
                        if (slot != null) onQuickActionOff(slot.action, currentApp)
                    } else {
                        Log.i(TAG, "quick menu: ${slot.action.name}")
                        haptic(if (onQuickAction(slot.action, currentApp)) Haptics.Kind.CONFIRM else Haptics.Kind.REJECT)
                    }
                }

                BubbleGesture.Event.MenuClosed -> {
                    closeMenu(animated = true)
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
        if (windowManager == null || menuParams == null) return
        menuSlots = quickMenu(currentApp)
        val fan = fanAt(cx, cy)
        gesture.fanShown(fan, menuSlots.map { it != null })
        haptic(Haptics.Kind.CLICK)
        // The screen may have turned since the window was added.
        fitMenuWindow()
        menuView.side = side
        menuView.open(fan, menuSlots)
        menuView.visibility = View.VISIBLE
        view.animate().scaleX(MENU_BUBBLE_SCALE).scaleY(MENU_BUBBLE_SCALE).setDuration(MENU_SCALE_MS)
            .setInterpolator(OvershootInterpolator()).withEndAction(null).start()
        Log.i(TAG, "quick menu: ${menuSlots.joinToString { it?.let { slot -> slot.action.name + if (slot.enabled) "" else " (off)" } ?: "-" }}, tilt ${fan.tiltDeg.roundToInt()}")
    }

    /**
     * The menu's fan around a bubble centred at ([cx], [cy]), kept below the status bar, above
     * the keyboard or the gesture bar and inside the screen's sides ([QuickMenuGeometry.fan]).
     */
    private fun fanAt(cx: Float, cy: Float): QuickMenuGeometry.Fan {
        val screen = screen()
        val bottom = screen.height - screen.safe.bottom
        val area = QuickMenuGeometry.Area(
            left = screen.safe.left.toFloat(),
            top = screen.safe.top.toFloat(),
            right = (screen.width - screen.safe.right).toFloat(),
            bottom = minOf(bottom, keyboardTop ?: bottom).toFloat(),
        )
        return QuickMenuGeometry.fan(
            side,
            cx,
            cy,
            radius = dp(QuickMenuView.RADIUS_DP).toFloat(),
            orbRadius = dp(QuickMenuView.ORB_DP) / 2f,
            margin = dp(QuickMenuView.MARGIN_DP).toFloat(),
            area = area,
        )
    }

    /**
     * Closes the quick menu: [animated], the [chosen] button pops and the rest fly back into
     * the bubble; otherwise it is gone at once (a new touch, the bubble leaving).
     */
    private fun closeMenu(animated: Boolean = false, chosen: Int? = null) {
        if (menuView.visibility != View.VISIBLE) return
        if (view.scaleX != 1f && shown) {
            view.animate().scaleX(1f).scaleY(1f).setDuration(MENU_SCALE_MS).setInterpolator(DecelerateInterpolator()).withEndAction(null).start()
        }
        if (animated) {
            menuView.close(chosen)
        } else {
            menuView.dismiss()
            menuView.visibility = View.INVISIBLE
        }
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
     * height it was let go at is its place from now on, keyboard or not - or, let go on the
     * keys, the nearest place off them ([BubblePlacement.dropped]).
     */
    private fun dropped(layoutParams: WindowManager.LayoutParams, velocityX: Float, velocityY: Float) {
        val size = layoutParams.width
        val screen = screen()
        val dock = BubbleDock.release(layoutParams.x, layoutParams.y, velocityX, velocityY, size, screen)
        side = dock.side
        restY = BubblePlacement.dropped(dock.y, dock.x, size, screen, dp(KEYBOARD_GAP_DP), keyboardTop, listOfNotNull(floatingKeyboard))
        val edgeY = BubbleDock.fractionOf(restY, size, screen)
        // The echo of this drop's settings write must not move the bubble again.
        settings = settings.copy(side = side, edgeY = edgeY)
        placedFrom = settings
        springTo(layoutParams, dock.x, targetY(size, dock.x), velocityX, 0f)
        onMoved(side, edgeY)
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

    /** The display with what the bubble keeps clear of: bars, gesture bar, cutout. The message pill keeps clear of them too. */
    fun screen(): Screen {
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
        // Android 9 and 10: the insets of the menu's window, which covers the whole screen -
        // the navigation bar on whichever side it sits in landscape, and the cutout.
        menuInsets()?.let { return it }
        // No insets known: the bars' usual sizes, the navigation bar at the bottom only in
        // portrait (in landscape it may sit on either side).
        val status = systemDimen("status_bar_height")
        val navigation = if (height > width) systemDimen("navigation_bar_height") else 0
        return SafeArea(top = status, bottom = navigation)
    }

    /**
     * The bars (stable insets: there even while hidden) and the cutout as the menu's window
     * gets them, whichever reaches further in on each side. Null while that window is not
     * the screen's size ([fitMenuWindow]) or got none - then nothing is known.
     */
    @Suppress("DEPRECATION")
    private fun menuInsets(): SafeArea? {
        val menu = menuParams ?: return null
        val (width, height) = screenSize()
        if (menu.width != width || menu.height != height) return null
        val insets = menuView.rootWindowInsets ?: return null
        val cutout = insets.displayCutout
        val area = SafeArea(
            left = maxOf(insets.stableInsetLeft, cutout?.safeInsetLeft ?: 0),
            top = maxOf(insets.stableInsetTop, cutout?.safeInsetTop ?: 0),
            right = maxOf(insets.stableInsetRight, cutout?.safeInsetRight ?: 0),
            bottom = maxOf(insets.stableInsetBottom, cutout?.safeInsetBottom ?: 0),
        )
        return area.takeIf { it != SafeArea() }
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

    private fun haptic(kind: Haptics.Kind) {
        if (!hapticsEnabled) return
        haptics.play(kind)
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TAG = "Embersay"
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

        /** Inside this radius around the bubble's centre nothing in the menu is lit. */
        const val MENU_DEAD_ZONE_DP = 26

        /** The bubble steps back a little while its menu is open. */
        const val MENU_BUBBLE_SCALE = 0.92f
        const val MENU_SCALE_MS = 200L
    }
}
