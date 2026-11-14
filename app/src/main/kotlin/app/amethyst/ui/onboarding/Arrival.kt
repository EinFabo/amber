package app.amethyst.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.amethyst.bubble.Fire
import app.amethyst.ui.LocalTimelineRestart
import app.amethyst.ui.drawBurst
import app.amethyst.ui.drawTick
import app.amethyst.ui.pageSpark
import app.amethyst.ui.rememberTimeline
import app.amethyst.ui.theme.AmberPalette

/*
 * Coming back from Android's settings: what the step shows in the moment Amber is in front
 * again.
 */

/**
 * The bubble with the tick it shows when a dictation is in, in the middle of the page. With
 * [arrived] - the service was switched on just now - the tick draws itself and one burst
 * flies, within [beatMs], the beat the step waits before it moves on; otherwise it is simply
 * there.
 */
@Composable
internal fun ServiceOnMark(arrived: Boolean, beatMs: Int, modifier: Modifier = Modifier, size: Dp = 88.dp) {
    val time = rememberTimeline(beatMs, play = arrived)
    val spark = pageSpark
    val disc = AmberPalette.Flame
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // The ticked row under it says it in words.
        Canvas(Modifier.size(size).clearAndSetSemantics {}) {
            val w = this.size.width
            val c = Offset(w / 2, w / 2)
            val r = w / 2 * Fire.DISC
            // A moment for the eye to arrive from the settings, then the tick and the burst together.
            val ms = time.ms - ARRIVE_MS
            drawCircle(disc, r, c)
            if (ms >= 0f && ms < Fire.BURST_MS) {
                drawCircle(disc.copy(alpha = 1 - ms / Fire.BURST_MS), r * Fire.FUSE_RADIUS, c, style = Stroke(w * 0.034f))
                drawBurst(c.x, c.y, r, w, ms, spark)
            }
            drawTick(Color(Fire.INK), c.x, c.y, r, ((ms - 40f) / 240f).coerceIn(0f, 1f))
        }
    }
}

private const val ARRIVE_MS = 150f

/**
 * Plays the timelines in [content] from their start each time Amber comes back to the front:
 * someone who returns from the settings sees the picture of where to tap from its first tap.
 */
@Composable
internal fun AfreshOnReturn(content: @Composable () -> Unit) {
    var returns by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { returns++ }
    CompositionLocalProvider(LocalTimelineRestart provides returns, content = content)
}
