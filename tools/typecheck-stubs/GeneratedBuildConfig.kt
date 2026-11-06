// Stands in for the BuildConfig class the Android Gradle plugin generates (buildConfig = true).
// Only the field the non-Compose sources read; not a constant, as in the generated class.
package app.amethyst

object BuildConfig {
    @JvmField
    val DEBUG: Boolean = false
}
