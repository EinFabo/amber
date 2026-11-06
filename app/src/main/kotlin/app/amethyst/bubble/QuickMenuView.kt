package app.amethyst.bubble

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import app.amethyst.core.bubble.BubbleSide
import app.amethyst.core.bubble.QuickMenuGeometry
import app.amethyst.core.bubble.QuickSlot

/**
 * The quick menu, drawn over the whole screen in a window that takes no touches - the touch
 * stays with the bubble that was pressed. The screen dims a little; four small round buttons
 * fly out of the bubble to the places of [QuickMenuGeometry.Fan], each with its glyph
 * ([MenuGlyphs]) in amber on the raised surface. The lit one grows into an amber pill toward
 * the middle of the screen with its name in it, the others step back. An empty slot is a
 * faint dashed ring, a disabled one is dimmed.
 *
 * Every frame is computed from [clock], so a still picture of any moment can be drawn.
 */
class QuickMenuView(context: Context) : View(context) {

    var side: BubbleSide = BubbleSide.RIGHT

    /** The time the animations run on; uptime in ms. */
    var clock: () -> Long = SystemClock::uptimeMillis

    /** The close animation has finished: the window can leave the screen. */
    var onClosed: (() -> Unit)? = null

    /** The lit slot, null for none; it may be a disabled one, whose pill stays grey. */
    var lit: Int? = null
        set(value) {
            if (field == value) return
            val now = clock()
            for (i in heat.indices) heat[i].to(if (i == value) 1f else 0f, now, HEAT_MS)
            dim.to(if (value != null) 1f else 0f, now, DIM_OTHERS_MS)
            field = value
            invalidate()
        }

    private var fan: QuickMenuGeometry.Fan? = null
    private var slots: List<QuickSlot?> = emptyList()
    private var openedAt = 0L
    private var closedAt: Long? = null
    private var chosen: Int? = null

    /** How far each button had come out when the menu began to close. */
    private val outAtClose = FloatArray(QuickMenuGeometry.SLOTS) { 1f }
    private val heat = Array(QuickMenuGeometry.SLOTS) { Tween() }
    private val dim = Tween()

    private val density = resources.displayMetrics.density
    private val orbRadius = ORB_DP * density / 2

    private val orb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(6 * density, 0f, 3 * density, SHADOW)
    }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = EMPTY
        pathEffect = DashPathEffect(floatArrayOf(4 * density, 3.5f * density), 0f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    }
    private val box = RectF()
    private val outEase = OvershootInterpolator(1.6f)
    private val backEase = PathInterpolator(0.5f, 0f, 0.8f, 0.4f)

    /** Opens the menu for [slots] around the bubble, laid out as [fan]. */
    fun open(fan: QuickMenuGeometry.Fan, slots: List<QuickSlot?>) {
        this.fan = fan
        this.slots = slots
        openedAt = clock()
        closedAt = null
        chosen = null
        outAtClose.fill(1f)
        heat.forEach { it.set(0f) }
        dim.set(0f)
        lit = null
        removeCallbacks(finish)
        invalidate()
    }

    /** Closes the menu: [chosen] pops, the rest fly back into the bubble. */
    fun close(chosen: Int?) {
        if (fan == null || closedAt != null) return
        val now = clock()
        for (i in outAtClose.indices) outAtClose[i] = out(i, now)
        closedAt = now
        this.chosen = chosen
        removeCallbacks(finish)
        if (animated()) postDelayed(finish, CLOSE_MS) else finish.run()
        invalidate()
    }

    /** Gone at once, without the animation. */
    fun dismiss() {
        removeCallbacks(finish)
        fan = null
        slots = emptyList()
        closedAt = null
        invalidate()
    }

    private val finish = Runnable {
        fan = null
        slots = emptyList()
        closedAt = null
        onClosed?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        val fan = fan ?: return
        val now = clock()
        val closing = closedAt

        // The screen behind dims a little, in and out.
        val shade = if (closing == null) progress(now - openedAt, DIM_MS) else 1f - progress(now - closing, DIM_MS)
        canvas.drawColor(((DIM_ALPHA * shade * 255).toInt() shl 24))

        slots.forEachIndexed { i, slot ->
            if (i >= fan.orbs.size) return@forEachIndexed
            val target = fan.orbs[i]
            var out: Float
            var scale: Float
            var alpha: Float
            if (closing == null) {
                out = out(i, now)
                scale = START_SCALE + (1 - START_SCALE) * out
                alpha = progress(now - openedAt - i * STAGGER_MS, FADE_IN_MS)
            } else if (i == chosen) {
                // The chosen one pops where it is.
                val t = progress(now - closing, POP_MS)
                out = outAtClose[i]
                scale = 1f + (POP_SCALE - 1f) * t
                alpha = 1f - t
            } else {
                val t = backEase.getInterpolation(progress(now - closing, BACK_MS))
                out = outAtClose[i] * (1 - t)
                scale = START_SCALE + (1 - START_SCALE) * out.coerceAtMost(1f)
                alpha = 1f - progress(now - closing, BACK_FADE_MS)
            }
            val x = fan.cx + (target.x - fan.cx) * out
            val y = fan.cy + (target.y - fan.cy) * out
            val h = heat[i].value(now)
            // The others step back while one is lit.
            if (slot != null && i != lit) alpha *= 1f - (1f - OTHERS_ALPHA) * dim.value(now)
            if (slot != null && !slot.enabled) alpha *= DISABLED_ALPHA + (1f - DISABLED_ALPHA) * h
            if (alpha <= 0.004f) return@forEachIndexed
            canvas.save()
            canvas.scale(scale, scale, x, y)
            if (slot == null) {
                empty.alpha = (EMPTY_ALPHA * alpha).toInt()
                canvas.drawCircle(x, y, orbRadius - empty.strokeWidth, empty)
            } else {
                drawOrb(canvas, slot, x, y, h, alpha)
            }
            canvas.restore()
        }

        if (animated() && (closing != null || now - openedAt < OPEN_MS || heat.any { it.running(now) } || dim.running(now))) {
            postInvalidateOnAnimation()
        }
    }

    /** One button, grown by [h] (0..1) into a pill toward the middle of the screen. */
    private fun drawOrb(canvas: Canvas, slot: QuickSlot, x: Float, y: Float, h: Float, alpha: Float) {
        val d = orbRadius * 2
        val label = slot.action.label
        val full = (d + PAD_OUTER * density + text.measureText(label) + PAD_INNER * density).coerceAtMost(width - 2 * EDGE * density)
        val w = d + (full - d) * h
        val towardLeft = side == BubbleSide.RIGHT
        var left = if (towardLeft) x + orbRadius - w else x - orbRadius
        left = left.coerceIn(EDGE * density, (width - w - EDGE * density).coerceAtLeast(EDGE * density))
        val iconX = if (towardLeft) left + w - orbRadius else left + orbRadius
        box.set(left, y - orbRadius, left + w, y + orbRadius)

        val lit = h * if (slot.enabled) 1f else 0f
        val fill = Fire.mix(RAISED, Fire.AMBER, lit)
        val layer = if (alpha < 1f) canvas.saveLayerAlpha(box.left - d, box.top - d, box.right + d, box.bottom + d, (255 * alpha).toInt()) else null
        orb.color = fill
        canvas.drawRoundRect(box, orbRadius, orbRadius, orb)
        rim.color = Fire.mix(LINE, Fire.AMBER, lit)
        box.inset(rim.strokeWidth / 2, rim.strokeWidth / 2)
        canvas.drawRoundRect(box, orbRadius, orbRadius, rim)

        val ink = if (slot.enabled) Fire.mix(Fire.AMBER, Fire.INK, lit) else MUTED
        MenuGlyphs.draw(canvas, slot.action.glyph, iconX, y, orbRadius, ink, behind = fill)

        // The name, once the pill has room for it.
        val words = ((h - TEXT_FROM) / (1 - TEXT_FROM)).coerceIn(0f, 1f)
        if (words > 0f) {
            text.color = if (slot.enabled) Fire.INK else MUTED
            text.alpha = (255 * words).toInt()
            val textLeft = if (towardLeft) left + PAD_OUTER * density else left + d + PAD_INNER * density
            val baseline = y - (text.ascent() + text.descent()) / 2
            canvas.save()
            canvas.clipRect(left, y - orbRadius, left + w, y + orbRadius)
            canvas.drawText(label, textLeft, baseline, text)
            canvas.restore()
        }
        if (layer != null) canvas.restoreToCount(layer)
    }

    /** How far button [i] has flown out at [now]: 0 in the bubble, 1 in place, a little more on the overshoot. */
    private fun out(i: Int, now: Long): Float =
        outEase.getInterpolation(progress(now - openedAt - i * STAGGER_MS, OUT_MS))

    private fun progress(elapsed: Long, duration: Long): Float =
        if (!animated()) 1f else (elapsed.toFloat() / duration).coerceIn(0f, 1f)

    private fun animated(): Boolean = ValueAnimator.areAnimatorsEnabled()

    /** A value easing from where it was to a new target. */
    private inner class Tween {
        private var from = 0f
        private var to = 0f
        private var at = 0L
        private var duration = 1L

        fun set(value: Float) {
            from = value
            to = value
            at = 0L
        }

        fun to(target: Float, now: Long, ms: Long) {
            if (target == to) return
            from = value(now)
            to = target
            at = now
            duration = ms
        }

        fun value(now: Long): Float {
            val t = progress(now - at, duration)
            return from + (to - from) * EASE.getInterpolation(t)
        }

        fun running(now: Long): Boolean = now - at < duration
    }

    companion object {
        /** The buttons' size, how far out they sit, how far from any edge they stay. */
        const val ORB_DP = 46
        const val RADIUS_DP = 84
        const val MARGIN_DP = 6

        private const val DIM_ALPHA = 0.32f
        private const val DIM_MS = 160L
        private const val OUT_MS = 300L
        private const val STAGGER_MS = 26L
        private const val FADE_IN_MS = 140L
        private const val OPEN_MS = OUT_MS + 3 * STAGGER_MS
        private const val START_SCALE = 0.35f
        private const val HEAT_MS = 200L
        private const val DIM_OTHERS_MS = 140L
        private const val OTHERS_ALPHA = 0.55f
        private const val DISABLED_ALPHA = 0.4f
        private const val POP_MS = 200L
        private const val POP_SCALE = 1.08f
        private const val BACK_MS = 150L
        private const val BACK_FADE_MS = 130L
        private const val CLOSE_MS = 260L

        /** The name shows once the pill is this far grown. */
        private const val TEXT_FROM = 0.35f

        /** Room in the pill: beyond the name, and between the name and the glyph. */
        private const val PAD_OUTER = 14f
        private const val PAD_INNER = 4f

        /** The pill keeps this far from the screen's sides. */
        private const val EDGE = 6f

        private const val RAISED = 0xFF1F1F1F.toInt()
        private const val LINE = 0xFF2E2E2E.toInt()
        private const val MUTED = 0xFF8A8A8A.toInt()
        private const val SHADOW = 0x66000000
        private const val EMPTY = 0xFFFFB020.toInt()
        private const val EMPTY_ALPHA = 64f

        private val EASE = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    }
}
