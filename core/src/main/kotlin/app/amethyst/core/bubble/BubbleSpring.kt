package app.amethyst.core.bubble

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * A slightly damped spring for one axis of the bubble's way to its edge: it takes the fling's
 * speed along, swings a few pixels past the edge and settles, instead of stopping dead.
 * Stiffness and damping as in Android's own bubbles (stiffness 700, damping 0.85), a touch
 * less damped so the swing can be seen.
 */
class BubbleSpring(
    private val stiffness: Double = STIFFNESS,
    private val dampingRatio: Double = DAMPING_RATIO,
) {
    /** Position in pixels, velocity in pixels per second. */
    data class Motion(val position: Double, val velocity: Double)

    /**
     * Advances [motion] towards [target] by [seconds], in small fixed steps so a long frame
     * (the phone busy for a moment) cannot throw the bubble off the screen.
     */
    fun step(motion: Motion, target: Double, seconds: Double): Motion {
        val steps = ceil(seconds.coerceAtLeast(0.0) / MAX_STEP_S).toInt().coerceAtLeast(1)
        val dt = seconds.coerceAtLeast(0.0) / steps
        val damping = 2 * dampingRatio * sqrt(stiffness)
        var x = motion.position
        var v = motion.velocity
        repeat(steps) {
            // Semi-implicit Euler: stable for a spring at these step sizes.
            v += (-stiffness * (x - target) - damping * v) * dt
            x += v * dt
        }
        return Motion(x, v)
    }

    fun atRest(motion: Motion, target: Double): Boolean =
        abs(motion.position - target) < REST_PX && abs(motion.velocity) < REST_PX_PER_S

    companion object {
        const val STIFFNESS = 700.0
        const val DAMPING_RATIO = 0.75
        private const val MAX_STEP_S = 0.004
        private const val REST_PX = 0.5
        private const val REST_PX_PER_S = 20.0
    }
}
