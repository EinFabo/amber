package app.amethyst.core.engine

import kotlin.math.exp

/**
 * How long recognizing a piece of audio will take, learned per engine and model from the
 * dictations so far, for the bubble's progress ring.
 *
 * Recognition reports no progress of its own (whisper.cpp only per 30 s window), so the ring
 * shows an estimate: milliseconds of work per millisecond of audio, starting from the engine's
 * typical rate and pulled towards what this phone actually measured. The ring never claims to
 * be done before the text is there - see [progress].
 */
class RecognitionEstimate(private val learningRate: Double = 0.35) {

    private val rates = HashMap<String, Double>()

    @Synchronized
    fun expectedMs(key: String, audioMs: Long, typicalRate: Double, overheadMs: Long): Long =
        overheadMs + (audioMs * (rates[key] ?: typicalRate)).toLong()

    /** Records that [audioMs] of audio took [tookMs], of which [overheadMs] was fixed cost. */
    @Synchronized
    fun learn(key: String, audioMs: Long, tookMs: Long, overheadMs: Long) {
        if (audioMs <= 0) return
        val observed = (tookMs - overheadMs).coerceAtLeast(0L).toDouble() / audioMs
        rates[key] = rates[key]?.let { it + (observed - it) * learningRate } ?: observed
    }

    companion object {
        /**
         * Where the ring stands after [elapsedMs] of an expected [expectedMs]: linear up to 90 %,
         * then creeping towards - never reaching - full, so an underestimate does not leave a
         * finished-looking ring waiting. Completion is shown by the done state, not here.
         */
        fun progress(elapsedMs: Float, expectedMs: Float): Float {
            if (expectedMs <= 0f) return 0f
            val t = elapsedMs / expectedMs
            if (t <= LINEAR_UNTIL) return t.coerceAtLeast(0f)
            return LINEAR_UNTIL + (CEILING - LINEAR_UNTIL) * (1 - exp(-(t - LINEAR_UNTIL) * 2).toFloat())
        }

        private const val LINEAR_UNTIL = 0.9f
        private const val CEILING = 0.98f
    }
}
