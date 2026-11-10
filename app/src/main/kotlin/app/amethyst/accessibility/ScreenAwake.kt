package app.amethyst.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Keeps the screen on while a dictation records and is worked on, up to the insertion.
 *
 * Speaking for two minutes the user does not touch the phone, and a screen timeout of a minute
 * or two turns it off mid-dictation: the lock screen takes the focus, the app ends input, and
 * by the time the text is ready no field can be found (seen with a 120 s dictation into
 * WhatsApp). A screen wake lock is deprecated for this; the window flag is the way - on a
 * window of its own, one invisible pixel that takes no touch and no focus, because the bubble's
 * window may be hidden in this app, or the bubble switched off, while the dictation runs.
 * Main thread.
 */
class ScreenAwake(private val service: AccessibilityService) {

    private var view: View? = null

    fun hold(on: Boolean) {
        if (on) add() else remove()
    }

    private fun add() {
        if (view != null) return
        val windows = service.getSystemService(WindowManager::class.java) ?: return
        val pixel = View(service)
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            windows.addView(pixel, params)
            view = pixel
        } catch (e: Exception) {
            Log.w(TAG, "could not keep the screen on during the dictation", e)
        }
    }

    private fun remove() {
        val pixel = view ?: return
        view = null
        runCatching { service.getSystemService(WindowManager::class.java)?.removeView(pixel) }
    }

    private companion object {
        const val TAG = "Amber"
    }
}
