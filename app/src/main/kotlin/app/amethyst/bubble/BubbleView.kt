package app.amethyst.bubble

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.CycleInterpolator
import android.view.animation.DecelerateInterpolator
import app.amethyst.core.engine.RecognitionEstimate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The visual states of the bubble. */
enum class BubbleVisual { IDLE, LISTENING, TRANSCRIBING, DONE, FAILED }

/**
 * The bubble, drawn by hand on a Canvas.
 *
 * Deliberately a plain View rather than Compose: an accessibility overlay window has no
 * lifecycle, saved-state or view-model owner, and wiring those up for one circle would be
 * more code than drawing it.
 *
 * Fire, kept minimal ([Fire]): a flat amber disc with one dark glyph - a flame at rest, level
 * bars while listening. While the text is written the flame burns, flickering, and the rim
 * fills as a progress ring with a spark at its head (Fuse); success closes the ring, draws a
 * tick and lets one short burst of sparks go. The rising sparks while listening are drawn by
 * [SparkView] in a window of their own, because they leave this one. Nothing moves at rest.
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

    /**
     * How long the current recognition is expected to take; the rim fills against it. Null
     * when there is no estimate - the spark then just runs around the rim.
     */
    var expectedWorkMs: Long? = null

    private var stateEnteredAt = SystemClock.elapsedRealtime()

    /** What is drawn: rises fast with the voice, falls slowly, like a VU meter. */
    private var level = 0f

    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fuse = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Fire.AMBER
    }
    private val spark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Fire.SPARK }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Fire.INK
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bounds = RectF()
    private val path = Path()
    private val partial = Path()
    private val measure = PathMeasure()
    private val flameOps = object : Fire.FlameOps {
        override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
        override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
            path.cubicTo(x1, y1, x2, y2, x3, y3)
        override fun arcTo(left: Float, top: Float, right: Float, bottom: Float, startDeg: Float, sweepDeg: Float) =
            path.arcTo(left, top, right, bottom, startDeg, sweepDeg, false)
        override fun close() = path.close()
    }

    private var cx = 0f
    private var cy = 0f
    private var discRadius = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        // Room around the disc for the fuse and the burst.
        discRadius = min(w, h) / 2f * Fire.DISC
        fuse.strokeWidth = min(w, h) * 0.034f
    }

    override fun onDraw(canvas: Canvas) {
        val elapsed = (SystemClock.elapsedRealtime() - stateEnteredAt).toFloat()
        val target = if (visual == BubbleVisual.LISTENING) amplitude else 0f
        level += (target - level) * if (target > level) ATTACK else RELEASE
        val still = !ValueAnimator.areAnimatorsEnabled()
        val r = discRadius

        disc.color = if (visual == BubbleVisual.FAILED) Fire.ERROR else Fire.AMBER
        canvas.drawCircle(cx, cy, r, disc)

        when (visual) {
            BubbleVisual.TRANSCRIBING -> {
                val expected = expectedWorkMs
                if (expected != null) drawProgress(canvas, r, RecognitionEstimate.progress(elapsed, expected.toFloat()), elapsed)
                else drawFuse(canvas, r, elapsed, still)
            }
            BubbleVisual.DONE -> if (!still) {
                drawClosingRing(canvas, r, elapsed)
                drawBurst(canvas, r, elapsed)
            }
            else -> Unit
        }

        // New content fades in over the first fifth of a second of a state.
        glyph.alpha = (255 * (elapsed / ENTER_MS).coerceIn(0f, 1f)).toInt()
        when (visual) {
            BubbleVisual.IDLE -> drawFlame(canvas, r)
            BubbleVisual.LISTENING -> drawBars(canvas, r, elapsed, still)
            BubbleVisual.TRANSCRIBING -> drawFlame(canvas, r, if (still) 0f else elapsed)
            BubbleVisual.DONE -> drawCheck(canvas, r, if (still) 1f else ((elapsed - 40f) / 240f).coerceIn(0f, 1f))
            BubbleVisual.FAILED -> drawBang(canvas, r)
        }

        when (val delay = nextFrameMs(elapsed, still)) {
            null -> Unit
            0L -> postInvalidateOnAnimation()
            else -> postInvalidateDelayed(delay)
        }
    }

    /** A spark running clockwise around the rim, its tail fading behind it in four steps. */
    private fun drawFuse(canvas: Canvas, r: Float, elapsed: Float, still: Boolean) {
        val fr = r * Fire.FUSE_RADIUS
        bounds.set(cx - fr, cy - fr, cx + fr, cy + fr)
        val alpha = (elapsed / ENTER_MS).coerceIn(0f, 1f)
        if (still) {
            fuse.alpha = (255 * alpha * 0.45f).toInt()
            canvas.drawCircle(cx, cy, fr, fuse)
            return
        }
        fuse.strokeCap = Paint.Cap.BUTT
        val head = -90f + (elapsed / Fire.FUSE_LAP_MS) * 360f
        val segment = Math.toDegrees(Fire.FUSE_SEGMENT.toDouble()).toFloat()
        Fire.FUSE_TAIL.forEachIndexed { i, segmentAlpha ->
            fuse.alpha = (255 * alpha * segmentAlpha).toInt()
            canvas.drawArc(bounds, head - segment * (i + 1), segment, false, fuse)
        }
        val rad = Math.toRadians(head.toDouble())
        spark.alpha = (255 * alpha).toInt()
        canvas.drawCircle(cx + cos(rad).toFloat() * fr, cy + sin(rad).toFloat() * fr, fuse.strokeWidth * 1.1f, spark)
    }

    /** The rim as a progress ring: a faint track, the filled part, a spark at its head. */
    private fun drawProgress(canvas: Canvas, r: Float, progress: Float, elapsed: Float) {
        val fr = r * Fire.FUSE_RADIUS
        val enter = (elapsed / ENTER_MS).coerceIn(0f, 1f)
        bounds.set(cx - fr, cy - fr, cx + fr, cy + fr)
        fuse.strokeCap = Paint.Cap.ROUND
        fuse.alpha = (255 * enter * Fire.TRACK_ALPHA).toInt()
        canvas.drawCircle(cx, cy, fr, fuse)
        val sweep = 360f * progress.coerceIn(0.02f, 1f)
        fuse.alpha = (255 * enter).toInt()
        canvas.drawArc(bounds, -90f, sweep, false, fuse)
        val rad = Math.toRadians((-90f + sweep).toDouble())
        spark.alpha = (255 * enter).toInt()
        canvas.drawCircle(cx + cos(rad).toFloat() * fr, cy + sin(rad).toFloat() * fr, fuse.strokeWidth * 1.1f, spark)
    }

    /** On success the ring stands full for a moment and fades with the burst. */
    private fun drawClosingRing(canvas: Canvas, r: Float, elapsed: Float) {
        if (elapsed >= Fire.BURST_MS || expectedWorkMs == null) return
        val fr = r * Fire.FUSE_RADIUS
        fuse.strokeCap = Paint.Cap.ROUND
        fuse.alpha = (255 * (1 - elapsed / Fire.BURST_MS)).toInt()
        canvas.drawCircle(cx, cy, fr, fuse)
    }

    /** One burst of eight short sparks off the rim when the text is in. */
    private fun drawBurst(canvas: Canvas, r: Float, elapsed: Float) {
        if (elapsed >= Fire.BURST_MS) return
        val u = elapsed / Fire.BURST_MS
        spark.style = Paint.Style.STROKE
        spark.strokeCap = Paint.Cap.ROUND
        spark.strokeWidth = fuse.strokeWidth * 0.9f
        spark.alpha = (255 * (1 - u)).toInt()
        val d1 = r * (1.06f + 0.3f * Fire.easeOut(u))
        val d2 = d1 + r * 0.2f * (1 - u)
        for (i in 0 until 8) {
            val a = i * PI / 4 + PI / 8
            val dx = cos(a).toFloat()
            val dy = sin(a).toFloat()
            canvas.drawLine(cx + dx * d1, cy + dy * d1, cx + dx * d2, cy + dy * d2, spark)
        }
        spark.style = Paint.Style.FILL
    }

    /**
     * The flame glyph, its inner tongue cut back out in amber. At rest ([burningMs] 0) it
     * stands still; while the text is written it burns, the tongue flickering out of step.
     */
    private fun drawFlame(canvas: Canvas, r: Float, burningMs: Float = 0f) {
        val (tip, sway) = if (burningMs > 0f) Fire.flicker(burningMs) else 1f to 0f
        val (innerTip, innerSway) = if (burningMs > 0f) Fire.flicker(burningMs, phase = 2.1f) else 1f to 0f
        glyph.style = Paint.Style.FILL
        path.reset()
        Fire.flame(cx, cy, r, inner = false, ops = flameOps, tip = tip, sway = sway)
        canvas.drawPath(path, glyph)
        path.reset()
        Fire.flame(cx, cy, r, inner = true, ops = flameOps, tip = innerTip, sway = innerSway)
        disc.alpha = glyph.alpha
        canvas.drawPath(path, disc)
        disc.alpha = 255
    }

    /** Three level bars, the middle one tallest, each wobbling at its own pace. */
    private fun drawBars(canvas: Canvas, r: Float, elapsed: Float, still: Boolean) {
        glyph.style = Paint.Style.STROKE
        glyph.strokeWidth = r * 0.14f
        val spacing = r * 0.28f
        for (i in 0 until 3) {
            val wobble = if (still) 0.85f else 0.6f + 0.4f * sin(elapsed / 1000f * BAR_SPEED[i] * 2 * PI.toFloat() + i)
            val height = r * (0.16f + BAR_PROFILE[i] * (0.2f + level * 0.6f) * wobble)
            val x = cx + (i - 1) * spacing
            canvas.drawLine(x, cy - height / 2, x, cy + height / 2, glyph)
        }
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

    /**
     * When to draw next: 0 for the next vsync, a delay in ms, or null for not until something
     * changes. Listening is driven by the recorder's amplitude (50 a second) once faded in.
     * The burning flame and the fuse run for as long as recognition takes - up to a minute
     * and more on a local model, next to whisper on the CPU - so they get 30 frames a second,
     * not 120; with animations off only a progress ring still moves, four times a second.
     */
    private fun nextFrameMs(elapsed: Float, still: Boolean): Long? = when (visual) {
        BubbleVisual.LISTENING -> if (elapsed < ENTER_MS) 0L else null
        BubbleVisual.TRANSCRIBING -> when {
            !still -> WORKING_FRAME_MS
            expectedWorkMs != null -> STILL_PROGRESS_MS
            else -> null
        }
        // Keep drawing until the fade-in, the tick, the burst and the level decay have settled;
        // a resting bubble then costs nothing.
        else -> if (elapsed < SETTLE_MS || level > 0.01f) 0L else null
    }

    /** How visible the bubble is at rest (BubbleSettings.restingOpacity). */
    var restingOpacity: Float = 1f
        set(value) {
            field = value.coerceIn(MIN_OPACITY, 1f)
        }

    /** At rest the chosen opacity; listening, working and the result fully visible. */
    fun targetAlpha(): Float = if (visual == BubbleVisual.IDLE) restingOpacity else 1f

    /** Fades to [targetAlpha] - for a shown bubble whose state or setting changed. */
    fun settleAlpha() {
        val target = targetAlpha()
        if (alpha == target) return
        animate().alpha(target).setDuration(200).setInterpolator(DecelerateInterpolator()).withEndAction(null).start()
    }

    /** Brings the bubble in. Called when a text field gains focus. */
    fun appear() {
        animate().cancel()
        if (alpha == 0f) {
            scaleX = 0.8f
            scaleY = 0.8f
        }
        animate()
            .alpha(targetAlpha())
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

    private companion object {
        const val ATTACK = 0.35f
        const val RELEASE = 0.08f
        const val ENTER_MS = 200f
        const val SETTLE_MS = 500f
        const val WORKING_FRAME_MS = 33L
        const val MIN_OPACITY = 0.2f
        const val STILL_PROGRESS_MS = 250L

        val BAR_PROFILE = floatArrayOf(0.75f, 1f, 0.75f)
        val BAR_SPEED = floatArrayOf(2.6f, 3.2f, 2.2f)
    }
}
