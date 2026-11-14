package app.amethyst.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.bubble.Fire
import app.amethyst.core.bubble.BubbleSide
import app.amethyst.core.onboarding.Practice
import app.amethyst.ui.Hint
import app.amethyst.ui.StatusDot
import app.amethyst.ui.Timeline
import app.amethyst.ui.drawBurst
import app.amethyst.ui.pageSpark
import app.amethyst.ui.rememberTimeline
import app.amethyst.ui.theme.AmberPalette
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/*
 * The practice step's moments: what it shows when a try works, and when nothing has happened
 * for a while. Each plays once, in passing - nothing here waits for it or is held up by it.
 */

/**
 * The test field's border glowing amber for a moment, when [landed] turns true: the first
 * dictation has just gone into it.
 */
@Composable
internal fun Modifier.landingGlow(landed: Boolean): Modifier {
    val time = rememberTimeline(GLOW_MS, play = landed)
    val amber = AmberPalette.Primary
    return drawWithContent {
        drawContent()
        val u = time.fraction
        if (u >= 1f) return@drawWithContent
        // Up fast, down slowly.
        val glow = if (u < 0.2f) u / 0.2f else 1f - (u - 0.2f) / 0.8f
        val corner = CornerRadius(12.dp.toPx())
        drawRoundRect(amber.copy(alpha = glow), cornerRadius = corner, style = Stroke(2.dp.toPx()))
        val out = 3.dp.toPx()
        drawRoundRect(
            amber.copy(alpha = glow * 0.4f),
            topLeft = Offset(-out, -out),
            size = Size(size.width + 2 * out, size.height + 2 * out),
            cornerRadius = CornerRadius(12.dp.toPx() + out),
            style = Stroke(6.dp.toPx()),
        )
    }
}

/**
 * A task's dot. One that ticks while the step is up - [done] turning true, not a task found
 * done when the step opened - throws one burst as its tick draws on.
 */
@Composable
internal fun TaskDot(done: Boolean) {
    val openAtFirst = remember { !done }
    val time = rememberTimeline(TICK_MS + Fire.BURST_MS.toInt(), play = done && openAtFirst)
    val spark = pageSpark
    Box {
        StatusDot(done)
        Canvas(Modifier.matchParentSize()) {
            // As the tick is nearly drawn.
            val since = time.ms - TICK_MS
            if (done && since >= 0f) drawBurst(size.width / 2, size.height / 2, size.width / 2, 56.dp.toPx(), since, spark)
        }
    }
}

/** "All three done", coming in a little larger than nothing: the step's last word. */
@Composable
internal fun AllDoneLine() {
    val time = rememberTimeline(IN_MS)
    Text(
        stringResource(R.string.practice_all_done),
        style = MaterialTheme.typography.bodyLarge,
        color = AmberPalette.Text,
        modifier = Modifier.graphicsLayer {
            val u = Fire.easeOut(time.fraction)
            alpha = u
            scaleX = 0.8f + 0.2f * u
            scaleY = 0.8f + 0.2f * u
            transformOrigin = TransformOrigin(0f, 0.5f)
        },
    )
}

/**
 * After [Practice.QUIET_MS] in which nothing happened ([quiet] staying the same): a line at
 * the bubble's edge of the page, an arrow in it moving gently towards that edge. It stays
 * inside the page - the bubble itself is drawn over the app by the service, at the screen's
 * edge, and nothing here reaches it. Gone again with the next thing that happens.
 */
@Composable
internal fun BubbleHint(side: BubbleSide, wanted: Boolean, quiet: Any?) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(wanted, quiet) {
        shown = false
        if (wanted) {
            delay(Practice.QUIET_MS)
            shown = true
        }
    }
    if (!shown) return
    val left = side == BubbleSide.LEFT
    val time = rememberTimeline(POINT_MS, plays = Timeline.LOOP)
    val amber = AmberPalette.Primary
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (left) Alignment.Start else Alignment.End),
    ) {
        val arrow = @Composable {
            Canvas(Modifier.size(width = 22.dp, height = 18.dp)) {
                val swell = if (time.fraction < 1f) 0.5f - 0.5f * sin(time.fraction * 2f * PI.toFloat() + PI.toFloat() / 2f) else 1f
                val towards = if (left) -1f else 1f
                val reach = 4.dp.toPx()
                val stroke = 2.dp.toPx()
                for (i in 0..1) {
                    val x = size.width / 2 + towards * ((i - 0.5f) * 7.dp.toPx() + (swell - 0.5f) * reach)
                    val color = amber.copy(alpha = 0.45f + 0.55f * swell)
                    drawLine(color, Offset(x - towards * 4.dp.toPx(), 3.dp.toPx()), Offset(x, size.height / 2), stroke, StrokeCap.Round)
                    drawLine(color, Offset(x, size.height / 2), Offset(x - towards * 4.dp.toPx(), size.height - 3.dp.toPx()), stroke, StrokeCap.Round)
                }
            }
        }
        if (left) arrow()
        Hint(
            stringResource(if (left) R.string.practice_bubble_left else R.string.practice_bubble_right),
            AmberPalette.Text,
            Modifier.weight(1f, fill = false),
        )
        if (!left) arrow()
    }
}

private const val GLOW_MS = 1_100
private const val TICK_MS = 180
private const val IN_MS = 320
private const val POINT_MS = 1_600
