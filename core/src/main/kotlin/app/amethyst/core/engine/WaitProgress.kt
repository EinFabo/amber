package app.amethyst.core.engine

/**
 * How far the wait after a dictation has come, for the bubble's ring: from the release to the
 * text in the field, through its steps - upload, recognition, cleanup - each weighted by the
 * time it is expected to take.
 *
 * A step fills from a real signal where there is one ([report]: bytes sent, audio still to
 * recognize, characters of a streamed reply) and by the clock against its expected time where
 * there is none - creeping, never reaching the end, so an underestimate does not leave a
 * finished-looking ring waiting. The ring never goes back: [plan] for a changed course (the
 * fallback takes over) spreads the rest from where it stands. Completion is the done state's
 * to show, so it stays short of full.
 *
 * Read by the bubble on every frame, written by the dictation; thread-safe.
 */
class WaitProgress(private val clock: () -> Long) {

    enum class Step { UPLOAD, RECOGNIZE, CLEANUP }

    private class Part(val step: Step, val expectedMs: Long)

    private var parts: List<Part> = emptyList()
    private var offset = 0f
    private var shown = 0f
    private var current = -1

    // Within the running step: what is known to be done, how far the next signal may take it,
    // and since when the clock fills the gap.
    private var floor = 0f
    private var ceiling = 1f
    private var since = 0L

    /**
     * The steps still ahead, in order, with their expected milliseconds. The first begins at
     * once; what the ring shows so far stays.
     */
    @Synchronized
    fun plan(vararg steps: Pair<Step, Long>) {
        offset = valueLocked()
        parts = steps.map { (step, ms) -> Part(step, ms.coerceAtLeast(1)) }
        current = -1
        if (parts.isNotEmpty()) startLocked(0)
    }

    /** [step] runs now; the steps before it count as done. A step not in the plan changes nothing. */
    @Synchronized
    fun begin(step: Step) {
        val index = parts.indexOfFirst { it.step == step }
        if (index > current) startLocked(index)
    }

    /**
     * A real signal within [step]: [done] of it is finished (0..1), and the clock may fill on
     * up to [upTo] until the next signal - e.g. one more chunk recognized. With [upTo] equal to
     * [done] (bytes sent) the clock adds nothing. Begins [step] if it had not begun.
     */
    @Synchronized
    fun report(step: Step, done: Float, upTo: Float = done) {
        begin(step)
        if (current < 0 || parts[current].step != step) return
        val partNow = partFractionLocked()
        floor = done.coerceIn(0f, 1f).coerceAtLeast(minOf(partNow, upTo.coerceIn(0f, 1f)))
        ceiling = upTo.coerceIn(floor, 1f)
        since = clock()
    }

    /** Where the ring stands, 0..[CEILING]. */
    @Synchronized
    fun value(): Float = valueLocked()

    private fun startLocked(index: Int) {
        current = index
        floor = 0f
        ceiling = 1f
        since = clock()
    }

    private fun partFractionLocked(): Float {
        if (current < 0) return 0f
        val span = ceiling - floor
        if (span <= 0f) return floor
        val expected = parts[current].expectedMs * span
        return floor + span * creep((clock() - since) / expected)
    }

    private fun valueLocked(): Float {
        if (current >= 0) {
            val total = parts.sumOf { it.expectedMs }.toFloat()
            val before = parts.take(current).sumOf { it.expectedMs }
            val raw = (before + partFractionLocked() * parts[current].expectedMs) / total
            shown = maxOf(shown, offset + (1 - offset) * raw)
        }
        shown = shown.coerceAtMost(CEILING)
        return shown
    }

    companion object {
        /** Short of full: the tick shows completion. */
        const val CEILING = 0.98f

        private const val LINEAR_UNTIL = 0.9f

        /**
         * A step filled by the clock after [t] of its expected time: linear up to 90 %, then
         * creeping towards - never reaching - its end.
         */
        fun creep(t: Float): Float {
            if (t <= LINEAR_UNTIL) return t.coerceAtLeast(0f)
            return LINEAR_UNTIL + (1 - LINEAR_UNTIL) * (1 - kotlin.math.exp(-(t - LINEAR_UNTIL) * 2).toFloat())
        }
    }
}
