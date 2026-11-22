package app.amethyst.capture

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.amethyst.AmethystApp
import app.amethyst.SecureWindow
import app.amethyst.dictation.DictationState
import app.amethyst.core.capture.TriggerSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A 1x1 transparent activity that exists only to make the process foreground.
 *
 * The last fallback, never the first way to the microphone. A bubble tap records directly in
 * the accessibility service's process ([app.amethyst.core.capture.MicrophoneRouter]): Android
 * binds that service so it holds while-in-use microphone access while the screen is on. That
 * rests on how AOSP binds accessibility services, and vendor builds may differ. So when the
 * direct start delivers only silence, when Android refuses the recorder or when Android's speech
 * recognition cannot open the microphone, the dictation hands over to this activity
 * (DictationController.handToProxy) - and only then. An activity is foreground by definition,
 * so starting this one is the one way to record that no ROM can argue with; whatever recorded
 * before the direct path still records.
 *
 * It costs something, which is why it is not the first way: while it is up it holds the input
 * focus, so the keyboard may blink and insertion waits for it to go. It finishes as soon as the
 * dictation leaves the listening state, so it never shows up as a window the user has to dismiss.
 */
class MicProxyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Amber's window: hidden from screenshots and Recents in the security mode.
        SecureWindow.follow(this)
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
        private const val TAG = "Embersay"

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
