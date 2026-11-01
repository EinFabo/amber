package app.amethyst.core.update

/**
 * A version as Amber numbers its releases: major.minor.patch, compared part by part as
 * numbers, so 0.10.0 comes after 0.9.9.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /** More digits than a part could sensibly have; also keeps every part an Int. */
        private const val MAX_DIGITS = 9

        /**
         * "v0.24.0", "0.24.0" or "0.23.0-debug": an optional "v", three parts of ASCII digits,
         * and anything from the first "-" or "+" on ignored - a debug build is the version it
         * was built from. Null for anything else.
         */
        fun parse(text: String?): AppVersion? {
            var core = text?.trim() ?: return null
            if (core.startsWith('v') || core.startsWith('V')) core = core.substring(1)
            core = core.substringBefore('-').substringBefore('+')
            val parts = core.split('.')
            if (parts.size != 3) return null
            val numbers = parts.map { part ->
                if (part.isEmpty() || part.length > MAX_DIGITS || part.any { it !in '0'..'9' }) return null
                part.toInt()
            }
            return AppVersion(numbers[0], numbers[1], numbers[2])
        }
    }
}
