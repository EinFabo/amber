package app.amethyst.bubble

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import android.view.View
import app.amethyst.core.bubble.BubbleSide
import app.amethyst.core.bubble.CleanupHere
import app.amethyst.core.bubble.QuickActions
import app.amethyst.core.bubble.QuickMenuContext
import app.amethyst.core.bubble.QuickMenuGeometry
import app.amethyst.core.bubble.QuickPhase
import app.amethyst.core.bubble.QuickSlot
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * The bubble and its quick menu drawn over a made-up chat, the way the overlay windows stack
 * them: the menu's shade and buttons, then the bubble on top. Real Android drawing
 * (Robolectric's native graphics) on a 1080 x 2340 phone, written to
 * app/build/screenshots/bubble - a look at the menu without a phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi")
class BubbleScreenshots {

    private val out = File("build/screenshots/bubble").apply { mkdirs() }
    private val context = RuntimeEnvironment.getApplication()
    private val density = context.resources.displayMetrics.density
    private val width = 1080
    private val height = 2340
    private val bubble = dp(56)
    private val statusBar = dp(24)
    private val gestureBar = dp(24)

    private val idle = QuickMenuContext(
        phase = QuickPhase.IDLE,
        hasLastDictation = true,
        fieldFocused = true,
        otherAppInFront = true,
        cleanupHere = CleanupHere.ON,
        cleanupPlanned = true,
        command = false,
    )

    @Test
    fun menuIdle() = shoot("menu-1-idle", idle)

    @Test
    fun menuIdleHot() = shoot("menu-2-idle-copy-lit", idle, lit = 1)

    @Test
    fun menuIdleDisabledHot() = shoot("menu-3-idle-nothing-to-insert-lit", idle.copy(hasLastDictation = false, cleanupHere = CleanupHere.OFF), lit = 0)

    @Test
    fun menuListening() = shoot("menu-4-listening", idle.copy(phase = QuickPhase.RECORDING), visual = BubbleVisual.LISTENING)

    @Test
    fun menuListeningHot() = shoot("menu-5-listening-no-cleanup-lit", idle.copy(phase = QuickPhase.RECORDING), visual = BubbleVisual.LISTENING, lit = 1)

    @Test
    fun menuWorking() = shoot("menu-6-working", idle.copy(phase = QuickPhase.WORKING), visual = BubbleVisual.TRANSCRIBING, lit = 0)

    @Test
    fun menuLeftEdge() = shoot("menu-7-left-edge-cleanup-lit", idle, side = BubbleSide.LEFT, lit = 2)

    /** The keyboard up and the bubble dragged down onto it: the fan turns and closes up. */
    @Test
    fun menuKeyboard() = shoot("menu-8-keyboard-low", idle, keyboardTop = 1500, centreY = 1500f - dp(40), lit = 3)

    @Test
    fun menuKeyboardUsual() = shoot("menu-9-keyboard-usual", idle, keyboardTop = 1500, centreY = 1500f - dp(108), lit = 1)

    @Test
    fun menuPaused() = shoot("menu-10-paused-resume-lit", idle.copy(phase = QuickPhase.RECORDING, paused = true, canPause = true), visual = BubbleVisual.PAUSED, lit = 3)

    @Test
    fun menuListeningPauseLit() = shoot("menu-11-listening-pause-lit", idle.copy(phase = QuickPhase.RECORDING, canPause = true), visual = BubbleVisual.LISTENING, lit = 3)

    /** The bubble on its own: listening, paused, at rest - side by side. */
    @Test
    fun bubbleStates() {
        val states = listOf(BubbleVisual.IDLE, BubbleVisual.LISTENING, BubbleVisual.PAUSED, BubbleVisual.TRANSCRIBING)
        val bitmap = Bitmap.createBitmap(bubble * 2 * states.size, bubble * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)
        states.forEachIndexed { i, visual ->
            val view = bubbleView(visual)
            canvas.save()
            canvas.translate(bubble * 2f * i + bubble / 2f, bubble / 2f)
            view.draw(canvas)
            canvas.restore()
        }
        save("bubble-states-idle-listening-paused-working", bitmap)
        val paused = Bitmap.createBitmap(bubble * 3, bubble * 3, Bitmap.Config.ARGB_8888)
        Canvas(paused).apply {
            drawColor(BACKGROUND)
            translate(bubble.toFloat(), bubble.toFloat())
            bubbleView(BubbleVisual.PAUSED).draw(this)
        }
        save("bubble-paused", paused)
    }

    /** The ring filling while the finger holds, two thirds of the way. */
    @Test
    fun holdRing() {
        val view = bubbleView(BubbleVisual.IDLE)
        view.press(300)
        idle(200)
        val bitmap = Bitmap.createBitmap(bubble * 3, bubble * 3, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)
        canvas.translate(bubble.toFloat(), bubble.toFloat())
        view.draw(canvas)
        save("bubble-hold-ring", bitmap)
    }

    private fun shoot(
        name: String,
        context: QuickMenuContext,
        side: BubbleSide = BubbleSide.RIGHT,
        visual: BubbleVisual = BubbleVisual.IDLE,
        lit: Int? = null,
        keyboardTop: Int? = null,
        centreY: Float = height * 0.72f,
    ) {
        val slots = QuickActions.slots(context)
        val cx = if (side == BubbleSide.RIGHT) width - bubble / 2f else bubble / 2f
        val bottom = minOf(height - gestureBar, keyboardTop ?: height)
        val area = QuickMenuGeometry.Area(0f, statusBar.toFloat(), width.toFloat(), bottom.toFloat())
        val fan = QuickMenuGeometry.fan(side, cx, centreY, dp(QuickMenuView.RADIUS_DP).toFloat(), dp(QuickMenuView.ORB_DP) / 2f, dp(QuickMenuView.MARGIN_DP).toFloat(), area)

        var now = 10_000L
        val menu = QuickMenuView(this.context).apply { clock = { now } }
        layout(menu, width, height)
        menu.side = side
        menu.open(fan, slots)
        now += 1_000
        menu.lit = lit
        now += 1_000

        val view = bubbleView(visual)
        view.scaleX = 0.92f
        view.scaleY = 0.92f

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawChat(canvas, keyboardTop)
        menu.draw(canvas)
        canvas.save()
        canvas.translate(cx - bubble / 2f, centreY - bubble / 2f)
        canvas.scale(view.scaleX, view.scaleY, bubble / 2f, bubble / 2f)
        view.draw(canvas)
        canvas.restore()
        save(name, bitmap)
        // A closer look at the fan.
        val left = if (side == BubbleSide.RIGHT) width - dp(300) else 0
        val top = (centreY - dp(150)).toInt().coerceIn(0, height - dp(300))
        save("$name-close", Bitmap.createBitmap(bitmap, left, top, dp(300), dp(300)))
        assertTrue(slots.size == QuickMenuGeometry.SLOTS && slots.any { it is QuickSlot })
    }

    private fun bubbleView(visual: BubbleVisual): BubbleView {
        val view = BubbleView(context)
        layout(view, bubble, bubble)
        view.visual = visual
        if (visual == BubbleVisual.LISTENING) view.amplitude = 0.5f
        // Past the fade-in of the state.
        idle(600)
        return view
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun layout(view: View, w: Int, h: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
    }

    /** A chat app underneath: a bar, a few messages, the input, maybe a keyboard. */
    private fun drawChat(canvas: Canvas, keyboardTop: Int?) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(BACKGROUND)
        paint.color = 0xFF161616.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), dp(80).toFloat(), paint)
        val floor = (keyboardTop ?: (height - gestureBar)) - dp(64)
        val messages = listOf(0.62f to false, 0.48f to true, 0.7f to false, 0.4f to true, 0.66f to false, 0.55f to true)
        var y = floor - dp(24).toFloat()
        for ((share, mine) in messages.reversed()) {
            val w = width * share
            val h = dp(52).toFloat()
            paint.color = if (mine) 0xFF3A2F1C.toInt() else 0xFF211D17.toInt()
            val left = if (mine) width - w - dp(16) else dp(16).toFloat()
            canvas.drawRoundRect(left, y - h, left + w, y, dp(14).toFloat(), dp(14).toFloat(), paint)
            y -= h + dp(10)
        }
        paint.color = 0xFF161616.toInt()
        canvas.drawRect(0f, floor.toFloat(), width.toFloat(), floor + dp(64).toFloat(), paint)
        paint.color = 0xFF0F0D0A.toInt()
        canvas.drawRoundRect(dp(10).toFloat(), floor + dp(12).toFloat(), width - dp(60).toFloat(), floor + dp(52).toFloat(), dp(20).toFloat(), dp(20).toFloat(), paint)
        if (keyboardTop != null) {
            paint.color = 0xFF1D1A15.toInt()
            canvas.drawRect(0f, keyboardTop.toFloat(), width.toFloat(), height.toFloat(), paint)
            paint.color = 0xFF2C2821.toInt()
            for (row in 0 until 4) for (key in 0 until 10) {
                val kx = dp(6) + key * (width - dp(12)) / 10f
                val ky = keyboardTop + dp(14) + row * dp(54f)
                canvas.drawRoundRect(kx + dp(3), ky, kx + (width - dp(12)) / 10f - dp(3), ky + dp(44), dp(6).toFloat(), dp(6).toFloat(), paint)
            }
        }
    }

    private fun save(name: String, bitmap: Bitmap) {
        val file = File(out, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    private fun dp(value: Int): Int = (value * density).toInt()
    private fun dp(value: Float): Float = value * density

    private companion object {
        const val BACKGROUND = 0xFF12100C.toInt()
    }
}
