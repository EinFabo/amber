package app.nocturne.capture

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.nocturne.NocturneApp
import app.nocturne.dictation.DictationState
import app.nocturne.core.capture.TriggerSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A 1x1 transparent activity that exists only to make the process foreground.
 *
 * Fallback for devices that refuse microphone access to [MicForegroundService] - several
 * vendor ROMs kill or restrict long-lived foreground services. An activity is foreground by
 * definition, so starting this one is the one way to record that no ROM can argue with. It
 * finishes as soon as the dictation leaves the listening state, so it never shows up as a
 * window the user has to dismiss.
 */
class MicProxyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dictation = NocturneApp.graph(this).dictation
        Log.i(TAG, "mic proxy activity started as foreground anchor")
        dictation.start(TriggerSource.PROXY_ACTIVITY)

        lifecycleScope.launch {
            // Wait for the run to end, then get out of the way.
            dictation.state.first { it !is DictationState.Listening && it !is DictationState.Starting }
            finish()
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        private const val TAG = "Nocturne"

        fun launch(context: Context) {
            val intent = Intent(context, MicProxyActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)
        }
    }
}
