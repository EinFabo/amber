package app.amethyst.ui

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.amethyst.bubble.Fire
import app.amethyst.bubble.SparkField
import app.amethyst.core.engine.RecognitionEstimate
import app.amethyst.dictation.DictationState
import app.amethyst.ui.theme.AmberPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The dictation button on the home screen: the bubble, larger, with the same fire - a flame at
 * rest, sparks rising with the voice, a spark running round the rim while it works and one
 * burst when the text is in (see [Fire]). The canvas reaches above the disc so the sparks have
 * room. At rest it is a still image and costs no frames.
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
    val voice by animateFloatAsState(amplitude, spring(stiffness = Spring.StiffnessMedium), label = "voice")
    val stone by animateColorAsState(
        when {
            !enabled -> AmberPalette.Outline
            state is DictationState.Failed -> AmberPalette.Error
            else -> AmberPalette.Primary
        },
        tween(250),
        label = "stone",
    )

    val sparks = remember { SparkField() }
    val stateKey = state::class
    val enteredAt = remember(stateKey) { SystemClock.elapsedRealtime() }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    // Plain memory, not state: it is written while drawing, and state written there would
    // schedule yet another frame.
    val lastFrame = remember { LongArray(1) }
    val working = state is DictationState.Transcribing
    if (state is DictationState.Failed) sparks.clear()

    // Frames only while something moves: listening, working, and a short tail for the tick,
    // the burst and the last sparks.
    LaunchedEffect(stateKey) {
        while (true) {
            withFrameMillis { now = SystemClock.elapsedRealtime() }
            if (!listening && !working && now - enteredAt > SETTLE_MS) break
        }
    }

    Canvas(
        modifier
            .size(width = size, height = size * (1 + Fire.SPARK_HEADROOM))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = if (listening) "Stop dictation" else "Start test dictation" },
    ) {
        val w = this.size.width
        val cx = w / 2
        val cy = this.size.height - w / 2
        val r = w / 2 * Fire.DISC
        val elapsed = (now - enteredAt).toFloat()
        val still = !ValueAnimator.areAnimatorsEnabled()

        val dt = if (lastFrame[0] == 0L) 16L else (now - lastFrame[0]).coerceIn(0L, 100L)
        lastFrame[0] = now
        if (listening && !still) sparks.listen(now, dt, voice, cx, cy, r, w)

        drawCircle(stone, radius = r, center = Offset(cx, cy))
        val ink = Color(Fire.INK).copy(alpha = if (enabled) 1f else 0.5f)

        when (state) {
            is DictationState.Transcribing -> {
                val expected = state.expectedMs
                if (expected != null) {
                    drawProgress(cx, cy, r, w, RecognitionEstimate.progress(elapsed, expected.toFloat()), elapsed)
                } else {
                    drawFuse(cx, cy, r, w, elapsed, still)
                }
                drawFlame(ink, stone, cx, cy, r, burningMs = if (still) 0f else elapsed)
            }
            is DictationState.Done -> {
                if (!still) {
                    if (elapsed < Fire.BURST_MS) {
                        drawCircle(Color(Fire.AMBER).copy(alpha = 1 - elapsed / Fire.BURST_MS), r * Fire.FUSE_RADIUS, Offset(cx, cy), style = Stroke(w * 0.034f))
                    }
                    drawBurst(cx, cy, r, w, elapsed)
                }
                drawTick(ink, cx, cy, r, if (still) 1f else ((elapsed - 40f) / 240f).coerceIn(0f, 1f))
            }
            is DictationState.Failed -> drawBang(ink, cx, cy, r)
            DictationState.Starting, is DictationState.Listening -> drawBars(ink, cx, cy, r, voice, elapsed, still)
            else -> drawFlame(ink, stone, cx, cy, r)
        }

        sparks.forEachAlive(now, w) { x, y, length, width, alpha, color ->
            drawLine(Color(color).copy(alpha = alpha), Offset(x, y), Offset(x, y + length), width, StrokeCap.Round)
        }
    }
}

private const val SETTLE_MS = 900L

private fun DrawScope.drawFuse(cx: Float, cy: Float, r: Float, w: Float, elapsed: Float, still: Boolean) {
    val fr = r * Fire.FUSE_RADIUS
    val stroke = w * 0.034f
    val alpha = (elapsed / 200f).coerceIn(0f, 1f)
    val amber = Color(Fire.AMBER)
    if (still) {
        drawCircle(amber.copy(alpha = alpha * 0.45f), fr, Offset(cx, cy), style = Stroke(stroke))
        return
    }
    val head = -90f + (elapsed / Fire.FUSE_LAP_MS) * 360f
    val segment = Math.toDegrees(Fire.FUSE_SEGMENT.toDouble()).toFloat()
    Fire.FUSE_TAIL.forEachIndexed { i, segmentAlpha ->
        drawArc(
            amber.copy(alpha = alpha * segmentAlpha),
            startAngle = head - segment * (i + 1),
            sweepAngle = segment,
            useCenter = false,
            topLeft = Offset(cx - fr, cy - fr),
            size = Size(fr * 2, fr * 2),
            style = Stroke(stroke),
        )
    }
    val rad = Math.toRadians(head.toDouble())
    drawCircle(Color(Fire.SPARK).copy(alpha = alpha), stroke * 1.1f, Offset(cx + cos(rad).toFloat() * fr, cy + sin(rad).toFloat() * fr))
}

/** The rim as a progress ring: a faint track, the filled part, a spark at its head. */
private fun DrawScope.drawProgress(cx: Float, cy: Float, r: Float, w: Float, progress: Float, elapsed: Float) {
    val fr = r * Fire.FUSE_RADIUS
    val stroke = w * 0.034f
    val enter = (elapsed / 200f).coerceIn(0f, 1f)
    val amber = Color(Fire.AMBER)
    drawCircle(amber.copy(alpha = enter * Fire.TRACK_ALPHA), fr, Offset(cx, cy), style = Stroke(stroke))
    val sweep = 360f * progress.coerceIn(0.02f, 1f)
    drawArc(
        amber.copy(alpha = enter),
        startAngle = -90f,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(cx - fr, cy - fr),
        size = Size(fr * 2, fr * 2),
        style = Stroke(stroke, cap = StrokeCap.Round),
    )
    val rad = Math.toRadians((-90f + sweep).toDouble())
    drawCircle(Color(Fire.SPARK).copy(alpha = enter), stroke * 1.1f, Offset(cx + cos(rad).toFloat() * fr, cy + sin(rad).toFloat() * fr))
}

private fun DrawScope.drawBurst(cx: Float, cy: Float, r: Float, w: Float, elapsed: Float) {
    if (elapsed >= Fire.BURST_MS) return
    val u = elapsed / Fire.BURST_MS
    val d1 = r * (1.06f + 0.3f * Fire.easeOut(u))
    val d2 = d1 + r * 0.2f * (1 - u)
    val color = Color(Fire.SPARK).copy(alpha = 1 - u)
    for (i in 0 until 8) {
        val a = i * PI / 4 + PI / 8
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        drawLine(color, Offset(cx + dx * d1, cy + dy * d1), Offset(cx + dx * d2, cy + dy * d2), w * 0.034f * 0.9f, StrokeCap.Round)
    }
}

/** The resting flame on its disc, for show only - nothing to tap, nothing moving. */
@Composable
fun FlameMark(modifier: Modifier = Modifier, size: Dp = 88.dp) {
    Canvas(modifier.size(size)) {
        val cx = this.size.width / 2
        val cy = this.size.height / 2
        val r = this.size.width / 2 * Fire.DISC
        drawCircle(AmberPalette.Primary, radius = r, center = Offset(cx, cy))
        drawFlame(Color(Fire.INK), AmberPalette.Primary, cx, cy, r)
    }
}

/**
 * The flame glyph from [Fire.flame], its inner tongue cut back out in the disc's colour;
 * burning (flickering) while [burningMs] runs, still at 0.
 */
private fun DrawScope.drawFlame(ink: Color, disc: Color, cx: Float, cy: Float, r: Float, burningMs: Float = 0f) {
    fun flamePath(inner: Boolean) = Path().also { path ->
        val (tip, sway) = if (burningMs > 0f) Fire.flicker(burningMs, phase = if (inner) 2.1f else 0f) else 1f to 0f
        Fire.flame(cx, cy, r, inner, tip = tip, sway = sway, ops = object : Fire.FlameOps {
            override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
            override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
                path.cubicTo(x1, y1, x2, y2, x3, y3)
            override fun arcTo(left: Float, top: Float, right: Float, bottom: Float, startDeg: Float, sweepDeg: Float) =
                path.arcTo(Rect(left, top, right, bottom), startDeg, sweepDeg, false)
            override fun close() = path.close()
        })
    }
    drawPath(flamePath(inner = false), ink)
    drawPath(flamePath(inner = true), disc)
}

private fun DrawScope.drawBars(ink: Color, cx: Float, cy: Float, r: Float, level: Float, elapsed: Float, still: Boolean) {
    val profile = floatArrayOf(0.75f, 1f, 0.75f)
    val speed = floatArrayOf(2.6f, 3.2f, 2.2f)
    for (i in 0 until 3) {
        val wobble = if (still) 0.85f else 0.6f + 0.4f * sin(elapsed / 1000f * speed[i] * 2 * PI.toFloat() + i)
        val height = r * (0.16f + profile[i] * (0.2f + level * 0.6f) * wobble)
        val x = cx + (i - 1) * r * 0.28f
        drawLine(ink, Offset(x, cy - height / 2), Offset(x, cy + height / 2), r * 0.14f, StrokeCap.Round)
    }
}

private fun DrawScope.drawTick(ink: Color, cx: Float, cy: Float, r: Float, progress: Float) {
    val path = Path().apply {
        moveTo(cx - r * 0.3f, cy + r * 0.02f)
        lineTo(cx - r * 0.07f, cy + r * 0.25f)
        lineTo(cx + r * 0.33f, cy - r * 0.2f)
    }
    val measure = PathMeasure().apply { setPath(path, false) }
    val partial = Path()
    measure.getSegment(0f, measure.length * progress, partial, true)
    drawPath(partial, ink, style = Stroke(r * 0.11f, cap = StrokeCap.Round))
}

private fun DrawScope.drawBang(ink: Color, cx: Float, cy: Float, r: Float) {
    drawLine(ink, Offset(cx, cy - r * 0.3f), Offset(cx, cy + r * 0.06f), r * 0.12f, StrokeCap.Round)
    drawCircle(ink, r * 0.07f, Offset(cx, cy + r * 0.27f))
}
