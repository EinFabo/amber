package app.nocturne.bubble

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.min

/** The visual states of the bubble, drawn as moon phases. */
enum class BubbleVisual { IDLE, LISTENING, TRANSCRIBING, DONE, FAILED }

/**
 * The bubble itself, drawn by hand on a Canvas.
 *
 * Deliberately a plain View rather than Compose: an accessibility overlay window has no
 * lifecycle, saved-state or view-model owner, and wiring those up for one circle would be
 * more code than drawing it. The idea is the moon - resting as a thin crescent, waxing with
 * the voice, full while the text is being produced.
 */
class BubbleView(context: Context) : View(context) {

    var visual: BubbleVisual = BubbleVisual.IDLE
        set(value) {
            if (field != value) {
                field = value
                stateEnteredAt = SystemClock.elapsedRealtime()
                applyAlpha()
                invalidate()
            }
        }

    /** 0f..1f, drives the ring while listening. */
    var amplitude: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (visual == BubbleVisual.LISTENING) invalidate()
        }

    /** Dimmed when no editable field has focus, so it stays out of the way. */
    var dimmed: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                // Set here, never in onDraw: changing alpha invalidates the view, which
                // from inside onDraw would redraw forever.
                applyAlpha()
            }
        }

    private var stateEnteredAt = SystemClock.elapsedRealtime()

    private val backdrop = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_SURFACE
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val moon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cutout = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_SURFACE
    }
    private val arcBounds = RectF()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) / 2f
        val ringWidth = outer * 0.10f
        val discRadius = outer - ringWidth * 1.6f

        canvas.drawCircle(cx, cy, discRadius, backdrop)

        when (visual) {
            BubbleVisual.IDLE -> {
                ring.color = COLOR_PRIMARY_SOFT
                ring.strokeWidth = ringWidth * 0.55f
                canvas.drawCircle(cx, cy, discRadius + ringWidth * 0.8f, ring)
                drawCrescent(canvas, cx, cy, discRadius * 0.52f, COLOR_PRIMARY)
            }

            BubbleVisual.LISTENING -> {
                // Ring thickness and radius both follow the voice, so quiet speech still
                // shows movement without the bubble jumping around.
                ring.color = COLOR_PRIMARY
                ring.strokeWidth = ringWidth * (0.6f + amplitude * 1.6f)
                val radius = discRadius + ringWidth * (0.8f + amplitude * 0.9f)
                canvas.drawCircle(cx, cy, radius, ring)
                drawCrescent(canvas, cx, cy, discRadius * (0.52f + amplitude * 0.12f), COLOR_ACCENT)
                postInvalidateOnAnimation()
            }

            BubbleVisual.TRANSCRIBING -> {
                val sweepStart = (elapsed() / 3f) % 360f
                ring.color = COLOR_ACCENT
                ring.strokeWidth = ringWidth
                val radius = discRadius + ringWidth * 0.8f
                arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
                canvas.drawArc(arcBounds, sweepStart, 110f, false, ring)
                moon.color = COLOR_PRIMARY
                canvas.drawCircle(cx, cy, discRadius * 0.5f, moon)
                postInvalidateOnAnimation()
            }

            BubbleVisual.DONE -> {
                ring.color = COLOR_ACCENT
                ring.strokeWidth = ringWidth
                canvas.drawCircle(cx, cy, discRadius + ringWidth * 0.8f, ring)
                moon.color = COLOR_ACCENT
                canvas.drawCircle(cx, cy, discRadius * 0.62f, moon)
            }

            BubbleVisual.FAILED -> {
                ring.color = COLOR_ERROR
                ring.strokeWidth = ringWidth * 0.8f
                canvas.drawCircle(cx, cy, discRadius + ringWidth * 0.8f, ring)
                drawCrescent(canvas, cx, cy, discRadius * 0.52f, COLOR_ERROR)
            }
        }
    }

    /** A disc with a second, offset disc punched out of it in the backdrop colour. */
    private fun drawCrescent(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        moon.color = color
        canvas.drawCircle(cx, cy, radius, moon)
        canvas.drawCircle(cx - radius * 0.55f, cy, radius * 0.95f, cutout)
    }

    private fun applyAlpha() {
        alpha = if (dimmed && visual == BubbleVisual.IDLE) DIMMED_ALPHA else 1f
    }

    private fun elapsed(): Float = (SystemClock.elapsedRealtime() - stateEnteredAt).toFloat()

    private companion object {
        const val DIMMED_ALPHA = 0.45f
        val COLOR_SURFACE = Color.parseColor("#0F0D16")
        val COLOR_PRIMARY = Color.parseColor("#A855F7")
        val COLOR_PRIMARY_SOFT = Color.parseColor("#6D28D9")
        val COLOR_ACCENT = Color.parseColor("#C084FC")
        val COLOR_ERROR = Color.parseColor("#F87171")
    }
}
