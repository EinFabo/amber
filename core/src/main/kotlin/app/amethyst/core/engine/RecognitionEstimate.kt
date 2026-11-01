package app.amethyst.core.engine

/**
 * How long recognizing a piece of audio will take, learned per engine and model from the
 * dictations so far, for the bubble's progress ring. The same rule serves the other steps of
 * the wait under their own keys: an upload per millisecond of audio, a cleanup per character.
 *
 * Recognition reports no progress of its own (whisper.cpp only per 30 s window), so the ring
 * shows an estimate: milliseconds of work per millisecond of audio, starting from the engine's
 * typical rate and pulled towards what this phone actually measured. The ring itself is
 * [WaitProgress].
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

    /** What was learned so far, to keep across restarts. */
    @Synchronized
    fun snapshot(): Map<String, Double> = HashMap(rates)

    /** Takes back what [snapshot] kept; rates learned since stay, nonsense is dropped. */
    @Synchronized
    fun restore(saved: Map<String, Double>) {
        saved.forEach { (key, rate) -> if (rate.isFinite() && rate >= 0 && key !in rates) rates[key] = rate }
    }
}
