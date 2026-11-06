package app.amethyst.bubble

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import app.amethyst.core.bubble.BubbleSide
import app.amethyst.core.bubble.QuickMenuGeometry
import app.amethyst.core.bubble.QuickSlot

/**
 * The quick menu, drawn over the whole screen in a window that takes no touches - the touch
 * stays with the bubble that was pressed. A dark fan around the bubble, one label per slot in
 * [QuickMenuGeometry]'s places, the lit one amber, and its name once more in a chip above
 * the fan, where the thumb does not cover it. Disabled slots are greyed, empty ones a dot.
 */
class QuickMenuView(context: Context) : View(context) {

    var side: BubbleSide = BubbleSide.RIGHT

    /** The bubble's centre in screen pixels, where the menu opened. */
    var centreX = 0f
    var centreY = 0f

    /** How far out the labels sit. */
    var radius = 0f

    var slots: List<QuickSlot?> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var lit: Int? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density

    private val fan = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FAN }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(14f)
        textAlign = Paint.Align.CENTER
    }
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(18f)
        textAlign = Paint.Align.CENTER
        color = Fire.INK
        isFakeBoldText = true
    }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        if (slots.isEmpty()) return
        canvas.drawCircle(centreX, centreY, radius * FAN_REACH, fan)
        val padX = 14 * density
        val halfHeight = 18 * density
        slots.forEachIndexed { index, slot ->
            if (slot == null) {
                val dot = QuickMenuGeometry.labelCentre(side, index, centreX, centreY, radius, width, height, 4 * density, 4 * density)
                pill.color = EMPTY
                canvas.drawCircle(dot.x, dot.y, 4 * density, pill)
                return@forEachIndexed
            }
            val label = slot.action.label
            val halfWidth = text.measureText(label) / 2 + padX
            val at = QuickMenuGeometry.labelCentre(side, index, centreX, centreY, radius, width, height, halfWidth, halfHeight)
            val on = index == lit && slot.enabled
            pill.color = when {
                on -> Fire.AMBER
                slot.enabled -> PILL
                else -> PILL_DISABLED
            }
            text.color = when {
                on -> Fire.INK
                slot.enabled -> LABEL
                else -> LABEL_DISABLED
            }
            box.set(at.x - halfWidth, at.y - halfHeight, at.x + halfWidth, at.y + halfHeight)
            canvas.drawRoundRect(box, halfHeight, halfHeight, pill)
            canvas.drawText(label, at.x, at.y - (text.ascent() + text.descent()) / 2, text)
        }
        val chosen = lit?.let { slots.getOrNull(it) }?.takeIf { it.enabled } ?: return
        val label = chosen.action.label
        val chipHalfWidth = chipText.measureText(label) / 2 + 18 * density
        val chipHalfHeight = 24 * density
        val at = QuickMenuGeometry.chipCentre(side, centreX, centreY, radius, width, height, chipHalfWidth, chipHalfHeight)
        pill.color = Fire.AMBER
        box.set(at.x - chipHalfWidth, at.y - chipHalfHeight, at.x + chipHalfWidth, at.y + chipHalfHeight)
        canvas.drawRoundRect(box, chipHalfHeight, chipHalfHeight, pill)
        canvas.drawText(label, at.x, at.y - (chipText.ascent() + chipText.descent()) / 2, chipText)
    }

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private companion object {
        /** The fan's dark backdrop reaches a little past the labels. */
        const val FAN_REACH = 1.45f
        const val FAN = 0x99000000.toInt()
        const val PILL = 0xF21F1F1F.toInt()
        const val PILL_DISABLED = 0xCC1F1F1F.toInt()
        const val LABEL = 0xFFEDEDED.toInt()
        const val LABEL_DISABLED = 0x80EDEDED.toInt()
        const val EMPTY = 0x66EDEDED
    }
}
