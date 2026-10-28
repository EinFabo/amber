package app.amethyst.bubble

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View

/**
 * Ember's rising sparks, in an overlay window of their own that sits over the bubble and
 * reaches [Fire.SPARK_HEADROOM] bubble sizes above it. The window takes no touches, so the
 * space the sparks fly through never swallows a tap meant for the app underneath.
 *
 * Draws only while listening and until the last spark has died (under a second later);
 * otherwise it requests no frames at all.
 */
class SparkView(context: Context) : View(context) {

    var listening: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                lastFrame = 0L
                invalidate()
            }
        }

    /** 0f..1f from the recorder; smoothed per frame. */
    var amplitude: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    private val sparks = SparkField()
    private var level = 0f
    private var lastFrame = 0L

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** The bubble's size in this view; the disc sits at the bottom, sparks rise above it. */
    private val size: Float get() = width.toFloat()
    private val discCx: Float get() = width / 2f
    private val discCy: Float get() = height - width / 2f
    private val discR: Float get() = width / 2f * Fire.DISC

    /** One spark when the bubble appears, so it arrives alight. */
    fun spark() {
        if (!ValueAnimator.areAnimatorsEnabled() || width == 0) return
        sparks.emit(SystemClock.elapsedRealtime(), 0.4f, discCx, discCy, discR, size)
        invalidate()
    }

    /** Failure puts the fire out at once. */
    fun extinguish() {
        sparks.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(0L, 100L)
        lastFrame = now

        val target = if (listening) amplitude else 0f
        level += (target - level) * if (target > level) ATTACK else RELEASE
        if (listening && ValueAnimator.areAnimatorsEnabled()) {
            sparks.listen(now, dt, level, discCx, discCy, discR, size)
        }

        val alive = sparks.forEachAlive(now, size) { x, y, length, width, alpha, color ->
            stroke.color = color
            stroke.alpha = (255 * alpha).toInt()
            stroke.strokeWidth = width
            canvas.drawLine(x, y, x, y + length, stroke)
        }

        // 30 frames a second: sparks this small look the same, at a quarter of 120 Hz's cost.
        if (listening || alive) postInvalidateDelayed(FRAME_MS) else lastFrame = 0L
    }

    private companion object {
        const val FRAME_MS = 33L
        const val ATTACK = 0.35f
        const val RELEASE = 0.08f
    }
}
