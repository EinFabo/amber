package app.amethyst.core.apps

import app.amethyst.core.stats.DictationStat
import java.text.Normalizer

/**
 * Ordering and searching the app list when the user picks an app for a writing style or for
 * hiding the bubble. The apps they actually dictate in come first; the rest follow by name.
 */
object AppChoice {

    /** How many of the most used apps get their own group at the top. */
    const val FREQUENT_MAX = 6

    /** Dictations per app package, from the statistics. */
    fun usage(stats: List<DictationStat>): Map<String, Int> =
        stats.mapNotNull { it.app }.groupingBy { it }.eachCount()

    /** [frequent] most used first, [rest] by name; together every app exactly once. */
    data class Grouped<T>(val frequent: List<T>, val rest: List<T>)

    fun <T> group(apps: List<T>, packageOf: (T) -> String, labelOf: (T) -> String, usage: Map<String, Int>): Grouped<T> {
        val byName = apps.sortedBy { fold(labelOf(it)) }
        val frequent = byName
            .filter { (usage[packageOf(it)] ?: 0) > 0 }
            .sortedByDescending { usage[packageOf(it)] ?: 0 }
            .take(FREQUENT_MAX)
        val chosen = frequent.map(packageOf).toSet()
        return Grouped(frequent, byName.filterNot { packageOf(it) in chosen })
    }

    /**
     * Every word of [query] appears in the name or the package, ignoring case and accents:
     * "tele" finds Telegram, "gm" finds Gmail by its package, "nachrichten" finds "Nachrichten".
     */
    fun matches(query: String, label: String, packageName: String): Boolean {
        val words = fold(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val haystack = fold(label) + " " + packageName.lowercase()
        return words.all { it in haystack }
    }

    private val MARKS = Regex("\\p{Mn}+")

    private fun fold(text: String): String =
        MARKS.replace(Normalizer.normalize(text.lowercase().trim(), Normalizer.Form.NFD), "")
}
