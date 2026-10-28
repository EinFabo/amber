package app.amethyst.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.amethyst.dictation.DictationState
import app.amethyst.ui.theme.AmberPalette

/**
 * The dictation button on the home screen: the bubble, larger - a flat amber disc with one
 * dark glyph. It only moves while it works; at rest it is a still image and costs no frames.
 */
@Composable
fun DictateOrb(
    state: DictationState,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 156.dp,
) {
    val listening = state is DictationState.Listening || state is DictationState.Starting
    val amplitude = (state as? DictationState.Listening)?.amplitude ?: 0f
    val voice by animateFloatAsState(
        amplitude,
        spring(stiffness = Spring.StiffnessMedium),
        label = "voice",
    )
    // Composed only in the states that need them, so the idle disc schedules no frames.
    val ripple = if (listening) looping(1_800) else 0f
    val sweep = if (state is DictationState.Transcribing) looping(1_200) * 360f else 0f

    val stone by animateColorAsState(
        when {
            !enabled -> AmberPalette.Outline
            state is DictationState.Failed -> AmberPalette.Error
            else -> AmberPalette.Primary
        },
        tween(350),
        label = "stone",
    )

    Canvas(
        modifier
            .size(size)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = if (listening) "Diktat stoppen" else "Testdiktat starten" },
    ) {
        val radius = this.size.minDimension / 2
        val girdle = radius * 0.66f

        if (listening) {
            // One ring that breathes with the voice, as on the bubble.
            drawCircle(
                stone.copy(alpha = 0.3f + voice * 0.6f),
                radius = girdle + (radius - girdle) * (0.2f + 0.15f * ripple + voice * 0.6f).coerceAtMost(0.95f),
                style = Stroke(1.5.dp.toPx()),
            )
        }

        drawCircle(stone, radius = girdle)

        if (state is DictationState.Transcribing) {
            rotate(sweep) {
                drawArc(
                    AmberPalette.Background,
                    startAngle = 0f,
                    sweepAngle = 100f,
                    useCenter = false,
                    topLeft = Offset(center.x - girdle * 0.45f, center.y - girdle * 0.45f),
                    size = Size(girdle * 0.9f, girdle * 0.9f),
                    style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }

        val glyph = AmberPalette.Background.copy(alpha = if (enabled) 1f else 0.5f)
        when (state) {
            is DictationState.Done -> drawCheck(glyph, girdle * 0.36f)
            is DictationState.Failed -> drawBang(glyph, girdle * 0.36f)
            is DictationState.Transcribing -> Unit
            else -> drawMic(glyph, girdle * 0.36f, filled = listening)
        }
    }
}

@Composable
private fun looping(periodMs: Int): Float {
    val transition = rememberInfiniteTransition(label = "loop")
    val value by transition.animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(periodMs, easing = LinearEasing)),
        label = "loopValue",
    )
    return value
}

private fun DrawScope.drawMic(color: Color, extent: Float, filled: Boolean) {
    val stroke = 2.dp.toPx()
    val capsuleWidth = extent * 0.62f
    val capsuleHeight = extent * 1.05f
    val top = center.y - extent * 0.85f
    drawRoundRect(
        color,
        topLeft = Offset(center.x - capsuleWidth / 2, top),
        size = Size(capsuleWidth, capsuleHeight),
        cornerRadius = CornerRadius(capsuleWidth / 2),
        style = if (filled) androidx.compose.ui.graphics.drawscope.Fill else Stroke(stroke),
    )
    val cradle = Path().apply {
        val r = extent * 0.55f
        val cy = top + capsuleHeight * 0.55f
        arcTo(
            androidx.compose.ui.geometry.Rect(center.x - r, cy - r, center.x + r, cy + r),
            0f,
            180f,
            true,
        )
    }
    drawPath(cradle, color, style = Stroke(stroke, cap = StrokeCap.Round))
    val stemTop = top + capsuleHeight * 0.55f + extent * 0.55f
    drawLine(color, Offset(center.x, stemTop), Offset(center.x, stemTop + extent * 0.28f), stroke, StrokeCap.Round)
}

private fun DrawScope.drawCheck(color: Color, extent: Float) {
    val path = Path().apply {
        moveTo(center.x - extent * 0.6f, center.y)
        lineTo(center.x - extent * 0.15f, center.y + extent * 0.45f)
        lineTo(center.x + extent * 0.65f, center.y - extent * 0.4f)
    }
    drawPath(path, color, style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round))
}

private fun DrawScope.drawBang(color: Color, extent: Float) {
    val stroke = 3.dp.toPx()
    drawLine(color, Offset(center.x, center.y - extent * 0.7f), Offset(center.x, center.y + extent * 0.15f), stroke, StrokeCap.Round)
    drawCircle(color, radius = stroke * 0.7f, center = Offset(center.x, center.y + extent * 0.6f))
}
