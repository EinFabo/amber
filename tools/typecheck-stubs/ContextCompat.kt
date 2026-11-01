// Minimal stand-ins for the AndroidX surface that Amethyst touches, so the Kotlin compiler
// can resolve the non-Compose part of :app against the REAL android framework jar.
// Written to match the real AndroidX signatures - but they are a transcription of them, so
// this harness verifies the android.* layer strongly and androidx.* only as far as the
// stubs are faithful.
package androidx.core.content

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

object ContextCompat {
    const val RECEIVER_NOT_EXPORTED: Int = 0x4
    fun startForegroundService(context: Context, intent: Intent) = Unit
    fun checkSelfPermission(context: Context, permission: String): Int = 0
    fun registerReceiver(context: Context, receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int): Intent? = null
}
