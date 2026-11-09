package app.amethyst.core.bubble

import kotlin.math.abs
import kotlin.math.exp

/**
 * The bubble's voice level: the microphone's loudness, followed by time rather than by sample.
 *
 * Recognizers report their loudness at very different rates - Amber's own recorder 50 times a
 * second, Android's recognizer in its own rhythm, with "silence" between words. Moving a fixed
 * share towards each value made the level jump with every report and drop to nothing in each
 * gap. Here it rises within a few frames and falls slowly, by the time that passed, so a short
 * gap between words only dips it and the same voice looks the same whoever measures it.
 */
class LevelMeter(
    private val riseMs: Float = RISE_MS,
    private val fallMs: Float = FALL_MS,
) {

    /** 0f..1f, as last drawn. */
    var level: Float = 0f
        private set

    private var lastAt = NEVER

    /** The level at [nowMs] (a monotonic clock) on its way to [target]. */
    fun follow(target: Float, nowMs: Long): Float {
        val goal = target.coerceIn(0f, 1f)
        val dt = if (lastAt == NEVER) FIRST_STEP_MS else (nowMs - lastAt).coerceIn(0L, MAX_STEP_MS)
        lastAt = nowMs
        val tau = if (goal > level) riseMs else fallMs
        level += (goal - level) * (1f - exp(-dt / tau))
        return level
    }

    /** Whether the level has arrived at [target], so there is nothing left to animate. */
    fun settledAt(target: Float): Boolean = abs(level - target.coerceIn(0f, 1f)) < SETTLED

    /** Back to silence, for the next dictation. */
    fun reset() {
        level = 0f
        lastAt = NEVER
    }

    companion object {
        /** About the old 0.35 per frame at 50 frames a second: a word shows at once. */
        const val RISE_MS = 45f

        /** Slower than before (about 240 ms), so a gap between words is a dip, not a drop. */
        const val FALL_MS = 350f

        const val SETTLED = 0.005f
        private const val NEVER = Long.MIN_VALUE
        private const val FIRST_STEP_MS = 16L

        /** A frame that came late (the view was hidden) counts as this much, not as a jump. */
        private const val MAX_STEP_MS = 100L
    }
}
