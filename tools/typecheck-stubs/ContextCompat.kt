// Minimal stand-ins for the AndroidX surface that Nocturne touches, so the Kotlin compiler
// can resolve the non-Compose part of :app against the REAL android framework jar.
// Written to match the real AndroidX signatures - but they are a transcription of them, so
// this harness verifies the android.* layer strongly and androidx.* only as far as the
// stubs are faithful.
package androidx.core.content

import android.content.Context
import android.content.Intent

object ContextCompat {
    fun startForegroundService(context: Context, intent: Intent) = Unit
    fun checkSelfPermission(context: Context, permission: String): Int = 0
}
