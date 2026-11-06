package app.amethyst

import android.util.Log

/**
 * Logcat lines that carry user data - which app is in front, what a field is, what was said -
 * are written in debug builds only. A release build's logcat is readable over adb and ends up
 * in bug reports; there such a line is left out, or its data replaced by [HIDDEN].
 *
 * Amber's own diagnostics screen keeps the full details either way: it stays on the phone.
 */
object DebugLog {

    /** Stands in for a value a release build does not write to logcat. */
    const val HIDDEN = "<hidden>"

    /** Whether user data may go to logcat: in debug builds only. */
    val enabled: Boolean get() = BuildConfig.DEBUG

    /** Debug builds only; the message is not even built in a release build. */
    inline fun i(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.i(tag, message())
    }

    /** [value] in a debug build, [HIDDEN] in a release build: for one datum in a useful line. */
    fun redact(value: Any?): String = if (BuildConfig.DEBUG) value.toString() else HIDDEN
}
