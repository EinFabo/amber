package app.amethyst.bubble

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * The drop zone that appears at the bottom while the bubble is dragged: let go over it and
 * the bubble stays out of the app in front. A dark disc with a cross; it lights up amber
 * once the bubble is close enough to count.
 */
class HideTarget(context: Context) : View(context) {

    var active: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                animate().scaleX(if (value) GROWN else 1f).scaleY(if (value) GROWN else 1f).setDuration(120).start()
                invalidate()
            }
        }

    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f / GROWN
        val cx = width / 2f
        val cy = height / 2f
        disc.color = if (active) AMBER else DARK
        canvas.drawCircle(cx, cy, r, disc)
        cross.color = if (active) INK else LIGHT
        cross.strokeWidth = r * 0.12f
        val arm = r * 0.32f
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, cross)
        canvas.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, cross)
    }

    private companion object {
        /** Drawn inside the view's bounds even at full size, so scaling never clips it. */
        const val GROWN = 1.25f
        const val DARK = 0xE61F1F1F.toInt()
        const val LIGHT = 0xFFEDEDED.toInt()
        const val AMBER = 0xFFFFB020.toInt()
        const val INK = 0xFF0A0A0A.toInt()
    }
}
