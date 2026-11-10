package app.amethyst.bubble

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import app.amethyst.core.bubble.QuickAction
import app.amethyst.core.bubble.QuickMenuItem
import kotlin.math.cos
import kotlin.math.sin

/** The picture on a quick menu button. */
enum class MenuGlyph {
    INSERT, UNDO, COPY, CLEANUP_ON, CLEANUP_OFF, HIDE, CANCEL, NO_CLEANUP, START_OVER, AS_RECOGNIZED, PAUSE, RESUME,
    AMBER, STYLE, NEXT_STYLE, APP_PAGE,
}

/** The glyph shows what the action does: switching the cleanup on shows the sparkle, off crosses it out. */
val QuickAction.glyph: MenuGlyph
    get() = when (this) {
        QuickAction.INSERT_AGAIN -> MenuGlyph.INSERT
        QuickAction.UNDO_LAST -> MenuGlyph.UNDO
        QuickAction.COPY_LAST -> MenuGlyph.COPY
        QuickAction.CLEANUP_OFF_HERE -> MenuGlyph.CLEANUP_OFF
        QuickAction.CLEANUP_ON_HERE -> MenuGlyph.CLEANUP_ON
        QuickAction.HIDE_HERE -> MenuGlyph.HIDE
        QuickAction.CANCEL -> MenuGlyph.CANCEL
        QuickAction.SEND_WITHOUT_CLEANUP -> MenuGlyph.NO_CLEANUP
        QuickAction.START_OVER -> MenuGlyph.START_OVER
        QuickAction.KEEP_AS_RECOGNIZED -> MenuGlyph.AS_RECOGNIZED
        QuickAction.PAUSE -> MenuGlyph.PAUSE
        QuickAction.RESUME -> MenuGlyph.RESUME
        QuickAction.OPEN_AMBER -> MenuGlyph.AMBER
        QuickAction.STYLE_HERE -> MenuGlyph.STYLE
        QuickAction.NEXT_STYLE -> MenuGlyph.NEXT_STYLE
        QuickAction.APP_PAGE -> MenuGlyph.APP_PAGE
    }

/** The glyph a place of the editor shows: the cleanup switch with the sparkle, as when it would switch on. */
val QuickMenuItem.glyph: MenuGlyph
    get() = when (this) {
        QuickMenuItem.INSERT_AGAIN -> MenuGlyph.INSERT
        QuickMenuItem.UNDO_LAST -> MenuGlyph.UNDO
        QuickMenuItem.CLEANUP_HERE -> MenuGlyph.CLEANUP_ON
        QuickMenuItem.COPY_LAST -> MenuGlyph.COPY
        QuickMenuItem.HIDE_HERE -> MenuGlyph.HIDE
        QuickMenuItem.OPEN_AMBER -> MenuGlyph.AMBER
        QuickMenuItem.STYLE_HERE -> MenuGlyph.STYLE
        QuickMenuItem.NEXT_STYLE -> MenuGlyph.NEXT_STYLE
        QuickMenuItem.APP_PAGE -> MenuGlyph.APP_PAGE
    }

/**
 * The quick menu's glyphs, drawn the way the bubble draws its own ([BubbleView]): round-capped
 * strokes of one weight - a tenth of the button's radius, the bubble's level bars and tick at
 * the size of a button - and solid shapes where the bubble's flame is solid (the cleanup's
 * sparkle, the pupil, the play triangle). A line crossing a glyph out is cut back out of it in
 * the button's own colour, as the flame's inner tongue is.
 *
 * Each glyph is laid out on a 24-unit grid and scaled to the button.
 */
object MenuGlyphs {

    private val path = Path()
    private val cutPath = Path()
    private val matrix = Matrix()
    private val oval = RectF()

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
    }

    /**
     * Draws [glyph] centred at [cx], [cy] on a button of radius [r], in [color] at [alpha];
     * [behind] is the button's own colour, for the cut-outs.
     */
    fun draw(canvas: Canvas, glyph: MenuGlyph, cx: Float, cy: Float, r: Float, color: Int, behind: Int, alpha: Float = 1f) {
        val unit = r * HALF_SIZE / 12f
        matrix.setScale(unit, unit)
        matrix.postTranslate(cx - 12f * unit, cy - 12f * unit)
        val weight = r * WEIGHT
        stroke.strokeWidth = weight
        stroke.color = color
        stroke.alpha = (255 * alpha).toInt()
        fill.strokeWidth = weight * 0.35f
        fill.color = color
        fill.alpha = stroke.alpha

        fun strokePath(build: Path.() -> Unit) {
            path.reset()
            path.build()
            path.transform(matrix)
            canvas.drawPath(path, stroke)
        }
        fun fillPath(build: Path.() -> Unit) {
            path.reset()
            path.build()
            path.transform(matrix)
            canvas.drawPath(path, fill)
        }
        // A line through the glyph, with a gap of the button's colour on both sides of it.
        fun strike() {
            cutPath.reset()
            cutPath.moveTo(4.5f, 4.5f)
            cutPath.lineTo(19.5f, 19.5f)
            cutPath.transform(matrix)
            val line = stroke.color
            stroke.color = behind
            stroke.alpha = 255
            stroke.strokeWidth = weight * 2.2f
            canvas.drawPath(cutPath, stroke)
            stroke.color = line
            stroke.alpha = (255 * alpha).toInt()
            stroke.strokeWidth = weight
            canvas.drawPath(cutPath, stroke)
        }

        when (glyph) {
            MenuGlyph.INSERT -> {
                strokePath {
                    moveTo(4f, 7f); lineTo(13f, 7f)
                    moveTo(4f, 12f); lineTo(10f, 12f)
                    moveTo(4f, 17f); lineTo(13f, 17f)
                    moveTo(15.5f, 9f); lineTo(20f, 12f); lineTo(15.5f, 15f)
                }
            }
            // An arrow turning back: what went in comes out again.
            MenuGlyph.UNDO -> strokePath {
                moveTo(8.5f, 4.5f); lineTo(4f, 9f); lineTo(8.5f, 13.5f)
                moveTo(4f, 9f); lineTo(14.5f, 9f)
                cubicTo(18f, 9f, 20f, 11.5f, 20f, 14.5f)
                cubicTo(20f, 17.5f, 18f, 20f, 14.5f, 20f)
                lineTo(9f, 20f)
            }
            MenuGlyph.COPY -> strokePath {
                addRoundRect(9f, 9f, 19.5f, 20f, 2.5f, 2.5f, Path.Direction.CW)
                moveTo(5f, 15f)
                lineTo(5f, 6.5f)
                oval.set(5f, 4f, 10f, 9f)
                arcTo(oval, 180f, 90f, false)
                lineTo(15f, 4f)
            }
            MenuGlyph.CLEANUP_ON -> fillPath { sparkles() }
            MenuGlyph.CLEANUP_OFF -> {
                fillPath { sparkles() }
                strike()
            }
            MenuGlyph.HIDE -> {
                strokePath {
                    moveTo(2.5f, 12f)
                    quadTo(12f, 1.5f, 21.5f, 12f)
                    quadTo(12f, 22.5f, 2.5f, 12f)
                    close()
                }
                fillPath { addCircle(12f, 12f, 3.2f, Path.Direction.CW) }
                strike()
            }
            MenuGlyph.CANCEL -> strokePath {
                moveTo(6.5f, 6.5f); lineTo(17.5f, 17.5f)
                moveTo(17.5f, 6.5f); lineTo(6.5f, 17.5f)
            }
            MenuGlyph.NO_CLEANUP -> strokePath {
                // "Aa": the words as they were heard.
                moveTo(3.5f, 18f); lineTo(8f, 6f); lineTo(12.5f, 18f)
                moveTo(5.2f, 14f); lineTo(10.8f, 14f)
                oval.set(14.5f, 11f, 20f, 18f)
                addOval(oval, Path.Direction.CW)
                moveTo(20f, 11.5f); lineTo(20f, 18f)
            }
            MenuGlyph.START_OVER -> strokePath {
                oval.set(4f, 4f, 20f, 20f)
                // Round from the left, down and back up, the arrow head ahead of the end.
                arcTo(oval, 180f, -315f, true)
                moveTo(4f, 4f); lineTo(4f, 8.5f); lineTo(8.5f, 8.5f)
            }
            MenuGlyph.AS_RECOGNIZED -> strokePath {
                // The bubble's own tick.
                moveTo(5f, 12.5f); lineTo(9.5f, 17f); lineTo(19f, 7f)
            }
            MenuGlyph.PAUSE -> strokePath {
                moveTo(9f, 6.5f); lineTo(9f, 17.5f)
                moveTo(15f, 6.5f); lineTo(15f, 17.5f)
            }
            MenuGlyph.RESUME -> fillPath {
                moveTo(8.5f, 6f); lineTo(8.5f, 18f); lineTo(18.5f, 12f); close()
            }
            // Amber's own flame, as the bubble shows it: solid, its inner tongue cut back out.
            MenuGlyph.AMBER -> {
                fillPath { Fire.flame(12f, 12f, FLAME_R, inner = false, ops = FlameOnPath(this)) }
                val ink = fill.color
                fill.color = behind
                fill.alpha = 255
                fillPath { Fire.flame(12f, 12f, FLAME_R, inner = true, ops = FlameOnPath(this)) }
                fill.color = ink
                fill.alpha = stroke.alpha
            }
            // A pen, writing down to the left.
            MenuGlyph.STYLE -> strokePath {
                moveTo(15f, 4.5f); lineTo(19.5f, 9f); lineTo(9f, 19.5f); lineTo(4.5f, 19.5f); lineTo(4.5f, 15f); close()
                moveTo(12.5f, 7f); lineTo(17f, 11.5f)
            }
            // Two arrows chasing each other round a circle: one step on, and round again.
            MenuGlyph.NEXT_STYLE -> strokePath {
                arrowArc(200f)
                arrowArc(20f)
            }
            // A small window: its title bar and two lines of a page.
            MenuGlyph.APP_PAGE -> strokePath {
                addRoundRect(4f, 4.5f, 20f, 19.5f, 2.5f, 2.5f, Path.Direction.CW)
                moveTo(4f, 9f); lineTo(20f, 9f)
                moveTo(8f, 13f); lineTo(16f, 13f)
                moveTo(8f, 16f); lineTo(12.5f, 16f)
            }
        }
    }

    /** An arc of the circle round the middle, clockwise from [startDeg], with its arrow head at the end. */
    private fun Path.arrowArc(startDeg: Float) {
        oval.set(12f - ARC_R, 12f - ARC_R, 12f + ARC_R, 12f + ARC_R)
        arcTo(oval, startDeg, ARC_SWEEP, true)
        val end = Math.toRadians((startDeg + ARC_SWEEP).toDouble())
        val tipX = 12f + ARC_R * cos(end).toFloat()
        val tipY = 12f + ARC_R * sin(end).toFloat()
        // The way the arc runs at its end; the head's two strokes go back from the tip either side of it.
        val dx = -sin(end).toFloat()
        val dy = cos(end).toFloat()
        for (side in intArrayOf(-1, 1)) {
            val a = HEAD_ANGLE * side
            val bx = -(dx * cos(a) - dy * sin(a))
            val by = -(dx * sin(a) + dy * cos(a))
            moveTo(tipX, tipY)
            lineTo(tipX + bx * HEAD, tipY + by * HEAD)
        }
    }

    /** The bubble's flame ([Fire.flame]) drawn into a [Path] on the 24-unit grid. */
    private class FlameOnPath(private val path: Path) : Fire.FlameOps {
        override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
        override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = path.cubicTo(x1, y1, x2, y2, x3, y3)
        override fun arcTo(left: Float, top: Float, right: Float, bottom: Float, startDeg: Float, sweepDeg: Float) =
            path.arcTo(left, top, right, bottom, startDeg, sweepDeg, false)
        override fun close() = path.close()
    }

    /** The cleanup's mark: a large four-pointed sparkle and a small one above it. */
    private fun Path.sparkles() {
        sparkle(10.5f, 13f, 7.5f)
        sparkle(18.5f, 5.5f, 3f)
    }

    private fun Path.sparkle(cx: Float, cy: Float, size: Float) {
        val waist = size * 0.26f
        moveTo(cx, cy - size)
        quadTo(cx + waist, cy - waist, cx + size, cy)
        quadTo(cx + waist, cy + waist, cx, cy + size)
        quadTo(cx - waist, cy + waist, cx - size, cy)
        quadTo(cx - waist, cy - waist, cx, cy - size)
        close()
    }

    /** The flame's disc radius on the grid: the flame then stands about 18.5 units tall, as tall as the others. */
    private const val FLAME_R = 22f

    /** The circle the arrows of "Next style" run round, how far each runs, and their heads. */
    private const val ARC_R = 7.5f
    private const val ARC_SWEEP = 130f
    private const val HEAD = 3.4f
    private const val HEAD_ANGLE = 0.75f

    /** The glyph's half width, in button radii. */
    private const val HALF_SIZE = 0.46f

    /** The stroke, in button radii: the bubble's tick and bars at the size of a button. */
    private const val WEIGHT = 0.1f
}
