package app.amethyst.core.capture

import java.util.Locale
import kotlin.math.roundToInt

/**
 * What the bubble's level was fed during one dictation, for the log: how often a value came
 * and how loud the quiet, middle and loud moments were. Counts only - nothing of the audio.
 */
class LoudnessLog {

    private val bins = IntArray(BINS)
    private var count = 0
    private var firstAt = -1L
    private var lastAt = -1L

    /** One value, 0f..1f, at [nowMs] (a monotonic clock). */
    fun add(level: Float, nowMs: Long) {
        val bin = (level.coerceIn(0f, 1f) * (BINS - 1)).roundToInt()
        bins[bin]++
        count++
        if (firstAt < 0) firstAt = nowMs
        lastAt = nowMs
    }

    /** "48/s · p10 0.31 · p50 0.42 · p90 0.63 · max 0.80", or null with no values. */
    fun summary(): String? {
        if (count == 0) return null
        val seconds = (lastAt - firstAt) / 1000.0
        val rate = if (seconds > 0.0) (count / seconds).toInt().toString() + "/s" else "$count values"
        return String.format(
            Locale.ROOT,
            "%s · p10 %.2f · p50 %.2f · p90 %.2f · max %.2f",
            rate, quantile(0.1), quantile(0.5), quantile(0.9), quantile(1.0),
        )
    }

    private fun quantile(q: Double): Float {
        val rank = maxOf(1, Math.ceil(q * count).toInt())
        var seen = 0
        for (i in bins.indices) {
            seen += bins[i]
            if (seen >= rank) return i / (BINS - 1).toFloat()
        }
        return 1f
    }

    private companion object {
        const val BINS = 101
    }
}
