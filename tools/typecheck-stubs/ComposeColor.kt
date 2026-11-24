// Stub for typecheck-android.sh only - never compiled into the app. Mirrors Compose's Color and
// its toArgb() closely enough for the message pill's call sites to resolve.
package androidx.compose.ui.graphics

@JvmInline
value class Color(val value: ULong)

fun Color.toArgb(): Int = value.toInt()
