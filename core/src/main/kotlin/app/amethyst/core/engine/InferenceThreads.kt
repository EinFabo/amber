package app.amethyst.core.engine

/**
 * How many threads a local speech recognition uses and whether it asks Android for a
 * performance boost: full speed on a cool phone, fewer threads and no boost when the phone is
 * warm or on battery saver - a long dictation then stays cooler instead of throttling itself.
 */
object InferenceThreads {

    /** The phone's heat, from Android's thermal status (unknown counts as [NORMAL]). */
    enum class Thermal { NORMAL, LIGHT, MODERATE, SEVERE, CRITICAL }

    data class Plan(val threads: Int, val performanceHint: Boolean)

    private const val MOST = 4
    private const val LEAST_WHEN_HOT = 2

    fun choose(cores: Int, thermal: Thermal, saver: Boolean): Plan {
        val full = cores.coerceIn(1, MOST)
        val cool = !saver && thermal < Thermal.MODERATE
        if (cool) return Plan(full, performanceHint = true)
        return Plan((full / 2).coerceAtLeast(LEAST_WHEN_HOT).coerceAtMost(full), performanceHint = false)
    }
}
