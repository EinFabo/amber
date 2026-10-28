package app.amethyst.capture

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.amethyst.AmethystApp
import app.amethyst.dictation.DictationState
import app.amethyst.core.capture.TriggerSource
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
        alive = true

        val dictation = AmethystApp.graph(this).dictation
        Log.i(TAG, "mic proxy activity started as foreground anchor")
        dictation.start(TriggerSource.PROXY_ACTIVITY)

        lifecycleScope.launch {
            // Wait for the run to end, then get out of the way.
            dictation.state.first { it !is DictationState.Listening && it !is DictationState.Starting }
            finish()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }
    }

    override fun onDestroy() {
        alive = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Amber"

        /**
         * True from creation until the window is gone. While it is up, it - not the app the
         * user is dictating into - holds input focus, so insertion has to wait for it.
         */
        @Volatile
        var alive: Boolean = false
            private set

        fun launch(context: Context) {
            val intent = Intent(context, MicProxyActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)
        }
    }
}
