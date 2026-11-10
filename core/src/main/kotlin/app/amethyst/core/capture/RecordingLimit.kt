package app.amethyst.core.capture

/**
 * The sign on the bubble that the longest recording is near: in its last stretch the rim
 * burns down, so whoever is still speaking sees the end coming instead of being cut off
 * mid-sentence. The recording then ends as a tap would - what was said is sent.
 */
object RecordingLimit {

    /** How long before the end the sign shows - a quarter of a short limit at most. */
    const val WARN_MS = 15_000L

    /**
     * What is left of the last stretch, 1f at its start down to 0f at the limit, after
     * [recordedMs] (paused time not counted) of a recording that may last [maxMs]; null
     * before the stretch, and for no limit at all.
     */
    fun left(recordedMs: Long, maxMs: Int): Float? {
        if (maxMs <= 0 || maxMs == Int.MAX_VALUE) return null
        val stretch = minOf(WARN_MS, maxMs / 4L)
        if (stretch <= 0) return null
        val remaining = maxMs - recordedMs
        if (remaining > stretch) return null
        return (remaining.toFloat() / stretch).coerceIn(0f, 1f)
    }
}
