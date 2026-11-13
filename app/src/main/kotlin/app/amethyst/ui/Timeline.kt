package app.amethyst.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember

/**
 * One clock for a small drawn scene: it runs from 0 to 1 over [durationMs], and the scene draws
 * itself from where it stands. Read [fraction] or [ms] while drawing (a Canvas, a graphicsLayer
 * block), not in composition: then a frame only redraws and nothing recomposes.
 *
 * At 1 the scene is at rest - its last frame, which is also what it shows when it does not
 * play at all: with animations switched off on the phone, or before its moment has come.
 */
@Stable
internal class Timeline(val durationMs: Int, private val at: () -> Float) {
    val fraction: Float get() = at()
    val ms: Float get() = at() * durationMs

    /** How far the part from [fromMs] to [toMs] has come, 0 before it and 1 after it. */
    fun part(fromMs: Float, toMs: Float): Float = ((ms - fromMs) / (toMs - fromMs)).coerceIn(0f, 1f)

    companion object {
        /** For [rememberTimeline]'s plays: over and over while it is on screen. */
        const val LOOP = 0
    }
}

/**
 * Holds every timeline under it at one point (0..1) instead of letting it run - for the tests
 * that render chosen moments of a scene to look at them. Null in the app.
 */
internal val LocalTimelineAt = compositionLocalOf<Float?> { null }

/**
 * Changes when the scenes under it are to play from their start once more - a page that was
 * left and is looked at again, say. Whoever knows of such a moment provides a new value.
 */
internal val LocalTimelineRestart = compositionLocalOf<Any?> { null }

/**
 * A [Timeline] that plays [plays] times ([Timeline.LOOP]: for as long as it is shown) and then
 * rests at 1. It starts when it comes on screen, and again whenever [restart] or
 * [LocalTimelineRestart] changes; with [play] false, or animations off on the phone, it rests
 * at 1 from the start.
 */
@Composable
internal fun rememberTimeline(durationMs: Int, plays: Int = 1, play: Boolean = true, restart: Any? = Unit): Timeline {
    val heldAt = LocalTimelineAt.current
    val allowed = remember { ValueAnimator.areAnimatorsEnabled() }
    val moving = play && allowed && heldAt == null
    val clock = remember(restart, LocalTimelineRestart.current, moving) { Animatable(if (moving) 0f else 1f) }
    LaunchedEffect(clock) {
        if (!moving) return@LaunchedEffect
        val once = tween<Float>(durationMs, easing = LinearEasing)
        clock.animateTo(1f, if (plays == Timeline.LOOP) infiniteRepeatable(once) else repeatable(plays, once))
    }
    return remember(clock, heldAt, play, durationMs) {
        Timeline(durationMs) { if (play && heldAt != null) heldAt else clock.value }
    }
}
