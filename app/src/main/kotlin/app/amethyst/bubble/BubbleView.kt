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
import app.amethyst.core.bubble.LevelMeter
import app.amethyst.core.engine.WaitProgress
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The visual states of the bubble. */
enum class BubbleVisual { IDLE, LISTENING, PAUSED, TRANSCRIBING, DONE, FAILED }

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
 * Paused, the fire is banked: a dark disc in a ring of cooling ember, the level bars stopped
 * as two amber bars, no sparks. Shortly before the longest recording the rim burns down: a
 * ring that shrinks towards twelve o'clock and reddens like an ember as the end comes.
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

    /**
     * A voice command is being taken: the disc turns dark with an amber rim and glyph, so a
     * command never looks like a dictation about to type somewhere.
     */
    var command: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * 0f..1f from the recorder or the recognizer, followed by time ([LevelMeter]). While
     * listening the bubble draws on its own clock; with animations off, each new value draws.
     */
    var amplitude: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (visual == BubbleVisual.LISTENING && !ValueAnimator.areAnimatorsEnabled()) invalidate()
        }

    /**
     * How far the wait after the recording has come; the rim fills with it. Null when there is
     * nothing to go by - the spark then just runs around the rim.
     */
    var work: WaitProgress? = null

    /**
     * In the last stretch before the longest recording, what is left of it (1f..0f): the rim
     * burns down with it. Null otherwise.
     */
    var limitLeft: Float? = null
        set(value) {
            val changed = field != value
            field = value
            // While listening the bubble draws on its own clock; with animations off, each step draws.
            if (changed && (value == null || !ValueAnimator.areAnimatorsEnabled())) invalidate()
        }

    private var stateEnteredAt = SystemClock.elapsedRealtime()

    /** What is drawn: rises fast with the voice, falls slowly, like a VU meter. */
    private val meter = LevelMeter()
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
        level = meter.follow(target, SystemClock.elapsedRealtime())
        val still = !ValueAnimator.areAnimatorsEnabled()
        val r = discRadius

        val inverted = command && visual != BubbleVisual.FAILED
        val banked = visual == BubbleVisual.PAUSED
        disc.color = when {
            visual == BubbleVisual.FAILED -> Fire.ERROR
            banked -> RAISED
            inverted -> Fire.INK
            else -> Fire.AMBER
        }
        glyph.color = if (inverted || banked) Fire.AMBER else Fire.INK
        canvas.drawCircle(cx, cy, r, disc)
        if (inverted) canvas.drawCircle(cx, cy, r, fuse.apply { alpha = 255 })
        if (banked) {
            fuse.color = EMBER
            canvas.drawCircle(cx, cy, r - fuse.strokeWidth / 2, fuse.apply { alpha = 255 })
            fuse.color = Fire.AMBER
        }

        when (visual) {
            BubbleVisual.TRANSCRIBING -> {
                val work = work
                if (work != null) drawProgress(canvas, r, work.value(), elapsed)
                else drawFuse(canvas, r, elapsed, still)
            }
            BubbleVisual.DONE -> if (!still) {
                drawClosingRing(canvas, r, elapsed)
                drawBurst(canvas, r, elapsed)
            }
            BubbleVisual.LISTENING -> limitLeft?.let { drawBurningDown(canvas, r, it) }
            else -> Unit
        }

        val now = SystemClock.elapsedRealtime()
        val holding = drawHold(canvas, now, still)

        // New content fades in over the first fifth of a second of a state.
        glyph.alpha = (255 * (elapsed / ENTER_MS).coerceIn(0f, 1f)).toInt()
        // A finger on the bubble presses the glyph in a little.
        val pressed = pressDepth(now, still)
        canvas.save()
        canvas.scale(1f - (1f - PRESSED_SCALE) * pressed, 1f - (1f - PRESSED_SCALE) * pressed, cx, cy)
        when (visual) {
            BubbleVisual.IDLE -> drawFlame(canvas, r)
            BubbleVisual.LISTENING -> drawBars(canvas, r, elapsed, still)
            BubbleVisual.PAUSED -> drawPause(canvas, r)
            BubbleVisual.TRANSCRIBING -> drawFlame(canvas, r, if (still) 0f else elapsed)
            BubbleVisual.DONE -> drawCheck(canvas, r, if (still) 1f else ((elapsed - 40f) / 240f).coerceIn(0f, 1f))
            BubbleVisual.FAILED -> drawBang(canvas, r)
        }
        canvas.restore()

        val pressMoving = !still && now - pressChangedAt < PRESS_MS
        when (val delay = if (holding || pressMoving) 0L else nextFrameMs(elapsed, still)) {
            null -> Unit
            0L -> postInvalidateOnAnimation()
            else -> postInvalidateDelayed(delay)
        }
    }

    /** When the finger came down and the hold ring began; null while no ring fills. */
    private var holdFrom: Long? = null
    private var holdMs = 300L

    /** The glyph pressed in (true) or let out, and when that changed. */
    private var pressed = false
    private var pressChangedAt = 0L

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Fire.AMBER
    }

    /**
     * A finger came down: the glyph presses in, and a thin ring fills around the bubble from
     * twelve o'clock for [holdMs] - the time until the quick menu opens.
     */
    fun press(holdMs: Long) {
        this.holdMs = holdMs
        holdFrom = SystemClock.elapsedRealtime()
        setPressedGlyph(true)
    }

    /** The hold is over - the menu opened, or the finger moved away: the ring goes. */
    fun holdDone() {
        holdFrom = null
        invalidate()
    }

    /** The finger is off the bubble, or dragging it: ring gone, glyph back. */
    fun release() {
        holdFrom = null
        setPressedGlyph(false)
    }

    private fun setPressedGlyph(on: Boolean) {
        if (pressed == on) {
            invalidate()
            return
        }
        val now = SystemClock.elapsedRealtime()
        // From wherever the last change had got to.
        val depth = pressDepth(now, !ValueAnimator.areAnimatorsEnabled())
        pressed = on
        pressChangedAt = now - ((if (on) depth else 1f - depth) * PRESS_MS).toLong()
        invalidate()
    }

    /** 0 let out, 1 pressed in. */
    private fun pressDepth(now: Long, still: Boolean): Float {
        val t = if (still) 1f else ((now - pressChangedAt).toFloat() / PRESS_MS).coerceIn(0f, 1f)
        val eased = Fire.easeOut(t)
        return if (pressed) eased else 1f - eased
    }

    /** The hold ring, just outside the fuse's track; true while it is still filling. */
    private fun drawHold(canvas: Canvas, now: Long, still: Boolean): Boolean {
        val from = holdFrom ?: return false
        // With animations off there is no filling ring, only the menu when it comes.
        if (still) return false
        val progress = ((now - from).toFloat() / holdMs).coerceIn(0f, 1f)
        ring.strokeWidth = fuse.strokeWidth * HOLD_STROKE
        val radius = min(width, height) / 2f - ring.strokeWidth / 2 - 1f
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(bounds, -90f, 360f * progress, false, ring)
        return progress < 1f
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

    /**
     * The longest recording is near: on the fuse's track, a ring from twelve o'clock that burns
     * down clockwise to nothing as [left] goes 1..0, amber turning ember red towards the end.
     */
    private fun drawBurningDown(canvas: Canvas, r: Float, left: Float) {
        val fr = r * Fire.FUSE_RADIUS
        bounds.set(cx - fr, cy - fr, cx + fr, cy + fr)
        fuse.strokeCap = Paint.Cap.ROUND
        fuse.color = Fire.mix(Fire.AMBER, Fire.EMBER_RED, 1f - left)
        fuse.alpha = (255 * Fire.TRACK_ALPHA).toInt()
        canvas.drawCircle(cx, cy, fr, fuse)
        fuse.alpha = 255
        if (left > 0f) canvas.drawArc(bounds, -90f, 360f * left, false, fuse)
        fuse.color = Fire.AMBER
    }

    /** On success the ring stands full for a moment and fades with the burst. */
    private fun drawClosingRing(canvas: Canvas, r: Float, elapsed: Float) {
        if (elapsed >= Fire.BURST_MS || work == null) return
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

    /**
     * Three level bars, the middle one tallest. In silence they are three still dots; the voice
     * draws them out, each wobbling at its own pace - so a quiet moment looks quiet.
     */
    private fun drawBars(canvas: Canvas, r: Float, elapsed: Float, still: Boolean) {
        glyph.style = Paint.Style.STROKE
        glyph.strokeWidth = r * 0.14f
        val spacing = r * 0.28f
        for (i in 0 until 3) {
            val wobble = if (still) 0.85f else 0.6f + 0.4f * sin(elapsed / 1000f * BAR_SPEED[i] * 2 * PI.toFloat() + i)
            // Silence leaves round dots (the stroke's caps); the voice draws them out to bars.
            val height = r * (0.16f + 0.8f * BAR_PROFILE[i]) * level * wobble
            val x = cx + (i - 1) * spacing
            canvas.drawLine(x, cy - height / 2, x, cy + height / 2, glyph)
        }
    }

    /** The level bars stopped: two even bars, the pause sign. */
    private fun drawPause(canvas: Canvas, r: Float) {
        glyph.style = Paint.Style.STROKE
        glyph.strokeWidth = r * 0.14f
        val half = r * 0.24f
        for (x in floatArrayOf(cx - r * 0.17f, cx + r * 0.17f)) canvas.drawLine(x, cy - half, x, cy + half, glyph)
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
     * changes. Listening draws 60 frames a second once faded in, on its own clock.
     * The burning flame and the fuse run for as long as recognition takes - up to a minute
     * and more on a local model, next to whisper on the CPU - so they get 30 frames a second,
     * not 120; with animations off only a progress ring still moves, four times a second.
     */
    private fun nextFrameMs(elapsed: Float, still: Boolean): Long? = when (visual) {
        // The level and the bars move by time, whenever and however often the loudness is
        // reported - Android's recognizer reports in its own rhythm, Amber's recorder 50 a second.
        BubbleVisual.LISTENING -> when {
            elapsed < ENTER_MS -> 0L
            still -> null
            else -> LISTENING_FRAME_MS
        }
        BubbleVisual.TRANSCRIBING -> when {
            !still -> WORKING_FRAME_MS
            work != null -> STILL_PROGRESS_MS
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
            .setDuration(APPEAR_MS)
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
        /**
         * Short: the bubble starts with the keyboard's first frames and should be there
         * before the keyboard is. At 180 ms it began 40 ms before Wispr Flow's bubble and
         * was fully there 50 ms after it (Pixel 9a, screen recording, 2026-11-13).
         */
        const val APPEAR_MS = 100L
        const val LISTENING_FRAME_MS = 16L
        const val ENTER_MS = 200f
        const val SETTLE_MS = 500f
        const val WORKING_FRAME_MS = 33L
        const val MIN_OPACITY = 0.2f
        const val STILL_PROGRESS_MS = 250L

        /** Paused: the raised surface for the disc, its ring an ember cooling from amber. */
        const val RAISED = 0xFF1F1F1F.toInt()
        val EMBER = Fire.mix(Fire.AMBER, Fire.EMBER_RED, 0.35f)

        /** The glyph under a finger, and how fast it gets there. */
        const val PRESSED_SCALE = 0.88f
        const val PRESS_MS = 140L

        /** The hold ring's stroke, in fuse strokes: a little bolder, it is the only thing moving. */
        const val HOLD_STROKE = 1.3f

        val BAR_PROFILE = floatArrayOf(0.75f, 1f, 0.75f)
        val BAR_SPEED = floatArrayOf(2.6f, 3.2f, 2.2f)
    }
}
