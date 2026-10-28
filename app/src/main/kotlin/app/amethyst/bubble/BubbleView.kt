package app.amethyst.bubble

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.CycleInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** The visual states of the bubble. */
enum class BubbleVisual { IDLE, LISTENING, TRANSCRIBING, DONE, FAILED }

/**
 * The bubble, drawn by hand on a Canvas.
 *
 * Deliberately a plain View rather than Compose: an accessibility overlay window has no
 * lifecycle, saved-state or view-model owner, and wiring those up for one circle would be
 * more code than drawing it. A flat amber disc with one dark glyph - no glow, no gradients.
 * While listening, a single ring around it follows the voice.
 */
class BubbleView(context: Context) : View(context) {

    var visual: BubbleVisual = BubbleVisual.IDLE
        set(value) {
            if (field != value) {
                field = value
                stateEnteredAt = SystemClock.elapsedRealtime()
                if (value == BubbleVisual.FAILED) shake()
                invalidate()
            }
        }

    /** 0f..1f from the recorder; smoothed per frame before it is drawn. */
    var amplitude: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (visual == BubbleVisual.LISTENING) invalidate()
        }

    private var stateEnteredAt = SystemClock.elapsedRealtime()

    /** What is drawn: rises fast with the voice, falls slowly, like a VU meter. */
    private var level = 0f

    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = COLOR_INK
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_INK
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bounds = RectF()
    private val path = Path()
    private val partial = Path()
    private val measure = PathMeasure()

    private var cx = 0f
    private var cy = 0f
    private var discRadius = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        // Room around the disc for the voice ring.
        discRadius = min(w, h) / 2f * 0.78f
        ring.strokeWidth = discRadius * 0.07f
        arc.strokeWidth = discRadius * 0.08f
    }

    override fun onDraw(canvas: Canvas) {
        val elapsed = (SystemClock.elapsedRealtime() - stateEnteredAt).toFloat()
        val target = if (visual == BubbleVisual.LISTENING) amplitude else 0f
        level += (target - level) * if (target > level) ATTACK else RELEASE
        val outer = min(width, height) / 2f
        val tint = if (visual == BubbleVisual.FAILED) COLOR_ERROR else COLOR_AMBER

        if (visual == BubbleVisual.LISTENING) {
            val radius = discRadius + (outer - discRadius - ring.strokeWidth) * (0.25f + level * 0.75f)
            ring.color = withAlpha(tint, (90 + level * 165).toInt())
            canvas.drawCircle(cx, cy, radius, ring)
        }

        val r = discRadius
        disc.color = tint
        canvas.drawCircle(cx, cy, r, disc)

        // New content fades in over the first fifth of a second of a state.
        glyph.alpha = (255 * (elapsed / ENTER_MS).coerceIn(0f, 1f)).toInt()

        when (visual) {
            BubbleVisual.IDLE -> drawMic(canvas, r)
            BubbleVisual.LISTENING -> drawBars(canvas, r, elapsed)
            BubbleVisual.TRANSCRIBING -> drawSpinner(canvas, r, elapsed)
            BubbleVisual.DONE -> drawCheck(canvas, r, ((elapsed - 40f) / 240f).coerceIn(0f, 1f))
            BubbleVisual.FAILED -> drawBang(canvas, r)
        }

        if (needsFrames(elapsed)) postInvalidateOnAnimation()
    }

    /** Three level bars, the middle one tallest, each wobbling at its own pace. */
    private fun drawBars(canvas: Canvas, r: Float, elapsed: Float) {
        glyph.style = Paint.Style.STROKE
        glyph.strokeWidth = r * 0.14f
        val spacing = r * 0.28f
        for (i in 0 until 3) {
            val wobble = 0.6f + 0.4f * sin(elapsed / 1000f * BAR_SPEED[i] * 2 * PI.toFloat() + i)
            val height = r * (0.16f + BAR_PROFILE[i] * (0.2f + level * 0.6f) * wobble)
            val x = cx + (i - 1) * spacing
            canvas.drawLine(x, cy - height / 2, x, cy + height / 2, glyph)
        }
    }

    /** A short dark arc turning inside the disc. */
    private fun drawSpinner(canvas: Canvas, r: Float, elapsed: Float) {
        arc.alpha = glyph.alpha
        val ar = r * 0.42f
        bounds.set(cx - ar, cy - ar, cx + ar, cy + ar)
        canvas.drawArc(bounds, (elapsed * 0.4f) % 360f, 100f, false, arc)
    }

    private fun drawMic(canvas: Canvas, r: Float) {
        glyph.style = Paint.Style.STROKE
        glyph.strokeWidth = r * 0.09f
        val w = r * 0.28f
        val top = cy - r * 0.42f
        val capsuleHeight = r * 0.48f
        bounds.set(cx - w / 2, top, cx + w / 2, top + capsuleHeight)
        canvas.drawRoundRect(bounds, w / 2, w / 2, glyph)
        val cr = r * 0.26f
        val cyc = top + capsuleHeight * 0.5f
        bounds.set(cx - cr, cyc - cr, cx + cr, cyc + cr)
        canvas.drawArc(bounds, 0f, 180f, false, glyph)
        canvas.drawLine(cx, cyc + cr, cx, cyc + cr + r * 0.14f, glyph)
    }

    /** The tick draws itself from left to right as [progress] goes 0..1. */
    private fun drawCheck(canvas: Canvas, r: Float, progress: Float) {
        glyph.style = Paint.Style.STROKE
        glyph.strokeWidth = r * 0.11f
        path.reset()
        path.moveTo(cx - r * 0.3f, cy + r * 0.02f)
        path.lineTo(cx - r * 0.07f, cy + r * 0.25f)
        path.lineTo(cx + r * 0.33f, cy - r * 0.2f)
        measure.setPath(path, false)
        partial.reset()
        measure.getSegment(0f, measure.length * progress, partial, true)
        canvas.drawPath(partial, glyph)
    }

    private fun drawBang(canvas: Canvas, r: Float) {
        glyph.style = Paint.Style.STROKE
        glyph.strokeWidth = r * 0.12f
        canvas.drawLine(cx, cy - r * 0.3f, cx, cy + r * 0.06f, glyph)
        glyph.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy + r * 0.27f, r * 0.07f, glyph)
    }

    private fun needsFrames(elapsed: Float): Boolean = when (visual) {
        BubbleVisual.LISTENING, BubbleVisual.TRANSCRIBING -> true
        // Keep drawing until the fade-in, the tick and the level decay have settled; a resting
        // bubble then costs nothing.
        else -> elapsed < SETTLE_MS || level > 0.01f
    }

    /** Brings the bubble in. Called when a text field gains focus. */
    fun appear() {
        animate().cancel()
        if (alpha == 0f) {
            scaleX = 0.8f
            scaleY = 0.8f
        }
        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction(null)
            .start()
    }

    /** Fades the bubble out, then runs [onHidden]. */
    fun vanish(onHidden: () -> Unit) {
        animate().cancel()
        animate()
            .alpha(0f)
            .scaleX(0.8f)
            .scaleY(0.8f)
            .setDuration(140)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction(onHidden)
            .start()
    }

    /** A short sideways shake, like a head saying no. */
    private fun shake() {
        animate()
            .translationX(width * 0.05f)
            .setDuration(320)
            .setInterpolator(CycleInterpolator(3f))
            .withEndAction { translationX = 0f }
            .start()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private companion object {
        // Same colours as AmberPalette in the app.
        val COLOR_AMBER = Color.parseColor("#FFB020")
        val COLOR_ERROR = Color.parseColor("#E5786D")
        val COLOR_INK = Color.parseColor("#0A0A0A")

        const val ATTACK = 0.35f
        const val RELEASE = 0.08f
        const val ENTER_MS = 200f
        const val SETTLE_MS = 500f

        val BAR_PROFILE = floatArrayOf(0.75f, 1f, 0.75f)
        val BAR_SPEED = floatArrayOf(2.6f, 3.2f, 2.2f)
    }
}
