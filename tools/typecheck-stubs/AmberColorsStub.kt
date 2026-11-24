// Stub for typecheck-android.sh only - never compiled into the app. The message pill
// (accessibility/MessagePill.kt) takes its colours from the app's Compose theme, which cannot be
// compiled here; the theme's colour set and Compose's Color stand in with the members it uses.
package app.amethyst.ui.theme

import androidx.compose.ui.graphics.Color

data class AmberColors(
    val dark: Boolean,
    val text: Color,
    val surface: Color,
    val elevated: Color,
    val outline: Color,
) {
    companion object {
        fun of(dark: Boolean): AmberColors = AmberColors(dark, Color(0u), Color(0u), Color(0u), Color(0u))
    }
}
