package app.amethyst.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.amethyst.bubble.Fire
import app.amethyst.ui.Timeline
import app.amethyst.ui.drawBurst
import app.amethyst.ui.drawFlame
import app.amethyst.ui.drawFuse
import app.amethyst.ui.drawProgress
import app.amethyst.ui.drawTick
import app.amethyst.ui.pageSpark
import app.amethyst.ui.rememberTimeline
import app.amethyst.ui.theme.AmberPalette

/*
 * The last step's moments: the bubble finishing as it does after a dictation, and what was
 * chosen coming in row by row.
 */

/**
 * The bubble over "All set": the fuse runs once round its rim, then the burst and the tick -
 * what it does when a text is in. While a speech model is still downloading ([downloaded] is
 * how far, 0..1) the rim shows that instead, and the finish plays when it is there.
 */
@Composable
internal fun DoneOrb(downloaded: Float?, modifier: Modifier = Modifier, size: Dp = 84.dp) {
    val waiting = downloaded != null
    val time = rememberTimeline(FUSE_MS + SETTLE_MS, play = !waiting, restart = waiting)
    val progress = rememberUpdatedState(downloaded)
    val spark = pageSpark
    val disc = AmberPalette.Flame
    Box(modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
        // The title under it says it in words.
        Canvas(Modifier.size(size).clearAndSetSemantics {}) {
            val w = this.size.width
            val c = Offset(w / 2, w / 2)
            val r = w / 2 * Fire.DISC
            val ink = Color(Fire.INK)
            drawCircle(disc, r, c)
            val ms = time.ms
            val at = progress.value
            when {
                at != null -> {
                    drawProgress(c.x, c.y, r, w, at, elapsed = 1_000f)
                    drawFlame(ink, disc, c.x, c.y, r)
                }
                ms < FUSE_MS -> {
                    drawFuse(c.x, c.y, r, w, ms, still = false)
                    drawFlame(ink, disc, c.x, c.y, r, burningMs = ms)
                }
                else -> {
                    val since = ms - FUSE_MS
                    if (since < Fire.BURST_MS) {
                        drawCircle(disc.copy(alpha = 1 - since / Fire.BURST_MS), r * Fire.FUSE_RADIUS, c, style = Stroke(w * 0.034f))
                        drawBurst(c.x, c.y, r, w, since, spark)
                    }
                    drawTick(ink, c.x, c.y, r, ((since - 40f) / 240f).coerceIn(0f, 1f))
                }
            }
        }
    }
}

/** One lap of the fuse, then room for the burst and the tick. */
private const val FUSE_MS = 1_100
private const val SETTLE_MS = 600

/** The clock the summary's rows come in by: [rows] of them, [ROW_APART_MS] apart. */
@Composable
internal fun rememberRowsIn(rows: Int): Timeline = rememberTimeline(ROWS_FROM_MS + rows * ROW_APART_MS + ROW_IN_MS)

/** Row [index] of the summary: it fades in and rises a little, after the rows above it. */
internal fun Modifier.rowIn(time: Timeline, index: Int): Modifier = graphicsLayer {
    val from = ROWS_FROM_MS + index * ROW_APART_MS
    val u = Fire.easeOut(time.part(from.toFloat(), (from + ROW_IN_MS).toFloat()))
    alpha = u
    translationY = (1f - u) * 8.dp.toPx()
}

/** After the step itself has come in. */
private const val ROWS_FROM_MS = 150
private const val ROW_APART_MS = 60
private const val ROW_IN_MS = 220
