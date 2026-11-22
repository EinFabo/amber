package app.amethyst

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes
import app.amethyst.accessibility.AmethystAccessibilityService
import app.amethyst.core.bubble.MessageTiming

/**
 * The one way the dictation, the quick menu and the insertion say something short over
 * whatever app is open. A toast of an app in the background is dropped when its notifications
 * are off (the default from Android 13 until the user allows them), so while the accessibility
 * service is connected the message is its own small overlay ([app.amethyst.accessibility.MessagePill]);
 * only without the service is it a plain toast. Amber's own screens, in front, keep their
 * toasts. Any thread.
 */
class Messages(
    private val context: Context,
    private val service: () -> AmethystAccessibilityService?,
) {
    private val main = Handler(Looper.getMainLooper())

    fun show(text: String) {
        main.post {
            val connected = service()
            if (connected != null) {
                connected.say(text)
            } else {
                Toast.makeText(context, text, if (MessageTiming.longToast(text)) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun show(@StringRes text: Int) = show(context.getString(text))
}
