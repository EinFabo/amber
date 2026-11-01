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
 * otherwise it requests no frames at all, and [onIdle] lets its window leave the screen - a
 * transparent window still costs the compositor a layer over every app. Whoever shows the
 * window again does so before [listening] or [spark].
 */
class SparkView(context: Context) : View(context) {

    var listening: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                lastFrame = 0L
                if (value) removeCallbacks(idleCheck)
                invalidate()
            }
        }

    /** 0f..1f from the recorder; smoothed per frame. */
    var amplitude: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    /** Nothing to draw any more: not listening, the last spark gone. Called on the main thread. */
    var onIdle: (() -> Unit)? = null

    /** Something is or will be drawn: listening, or a spark still in the air. */
    val busy: Boolean get() = listening || pendingSpark || !sparks.empty

    private val sparks = SparkField()
    private var level = 0f
    private var lastFrame = 0L

    /** A spark asked for before the view had its size: thrown at the first frame. */
    private var pendingSpark = false

    private val idleCheck = Runnable { if (!busy) onIdle?.invoke() }

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
        if (!ValueAnimator.areAnimatorsEnabled()) return
        removeCallbacks(idleCheck)
        if (width == 0) {
            // A window just brought back may not be laid out yet.
            pendingSpark = true
        } else {
            sparks.emit(SystemClock.elapsedRealtime(), 0.4f, discCx, discCy, discR, size)
        }
        invalidate()
    }

    /** Failure puts the fire out at once. */
    fun extinguish() {
        sparks.clear()
        pendingSpark = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(0L, 100L)
        lastFrame = now
        if (pendingSpark && width > 0) {
            pendingSpark = false
            sparks.emit(now, 0.4f, discCx, discCy, discR, size)
        }

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
        if (listening || alive) {
            postInvalidateDelayed(FRAME_MS)
        } else {
            lastFrame = 0L
            // Not from inside a draw: the window is taken off the screen right after it.
            removeCallbacks(idleCheck)
            post(idleCheck)
        }
    }

    private companion object {
        const val FRAME_MS = 33L
        const val ATTACK = 0.35f
        const val RELEASE = 0.08f
    }
}
