package app.amethyst.core.commands

/**
 * The app a spoken name means, by its launcher label. Tiers from sure to loose - the exact name,
 * a unique start, a word inside, a name a recognizer slightly mis-heard ("Telegramm") - and the
 * first tier with any hit decides. Several apps there are never guessed between. A [strict]
 * search knows only the first two tiers: for a bare name, said without "open", which is a
 * command only when it is surely an app.
 */
sealed interface AppMatch<out T> {
    data class Found<T>(val app: T) : AppMatch<T>
    data class Ambiguous<T>(val candidates: List<T>) : AppMatch<T>
    data object NotFound : AppMatch<Nothing>

    /** The tiers of [find], from sure to loose. */
    enum class Tier { EXACT, START, INSIDE, CLOSE }

    companion object {

        /**
         * Which tier [label] meets for [name], for Diagnostics. The first tier with a hit
         * decides, so for an app [find] returned this is the tier that found it.
         */
        fun tier(name: String, label: String): Tier {
            val query = key(name)
            val key = key(label)
            return when {
                key == query -> Tier.EXACT
                query.length >= 3 && key.startsWith(query) -> Tier.START
                query.length >= 4 && query in key -> Tier.INSIDE
                else -> Tier.CLOSE
            }
        }


        fun <T> find(name: String, apps: List<T>, strict: Boolean = false, labelOf: (T) -> String): AppMatch<T> {
            val query = key(name)
            if (query.isEmpty()) return NotFound
            val labelled = apps.map { it to key(labelOf(it)) }
            val tiers = listOf<(String) -> Boolean>(
                { it == query },
                { query.length >= 3 && it.startsWith(query) },
                { query.length >= 4 && query in it },
            )
            for (tier in if (strict) tiers.take(2) else tiers) {
                decide(labelled.filter { (_, label) -> tier(label) }.map { it.first })?.let { return it }
            }
            if (strict) return NotFound
            return decide(closest(query, apps, labelOf)) ?: NotFound
        }

        /** Apps whose name, or a word of it, is within a small edit distance - the nearest only. */
        private fun <T> closest(query: String, apps: List<T>, labelOf: (T) -> String): List<T> {
            if (query.length < 4) return emptyList()
            val limit = if (query.length <= 6) 1 else 2
            val distances = apps.map { app ->
                val label = labelOf(app)
                val keys = listOf(key(label)) + VoiceCommands.normalize(label).split(' ').filter { it.length >= 4 }
                app to keys.minOf { distance(query, it) }
            }.filter { it.second <= limit }
            val best = distances.minOfOrNull { it.second } ?: return emptyList()
            return distances.filter { it.second == best }.map { it.first }
        }

        private fun <T> decide(hits: List<T>): AppMatch<T>? = when (hits.size) {
            0 -> null
            1 -> Found(hits.single())
            else -> Ambiguous(hits)
        }

        /** Letters and digits only, folded: "What's App" and "WhatsApp" are one name. */
        private fun key(text: String): String = VoiceCommands.normalize(text).replace(" ", "")

        /** Edits (insert, delete, replace, swap two neighbours) from [a] to [b]. */
        private fun distance(a: String, b: String): Int {
            val d = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i
            for (j in 0..b.length) d[0][j] = j
            for (i in 1..a.length) {
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                        d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                    }
                }
            }
            return d[a.length][b.length]
        }
    }
}
