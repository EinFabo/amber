package app.amethyst

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.amethyst.core.privacy.SecurityMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Amber's own windows in the security mode ([SecurityMode.hidesWindows]): FLAG_SECURE keeps them
 * out of screenshots and screen recordings, and Android shows a blank picture for them in
 * Recents - on every version, where setRecentsScreenshotEnabled only exists from Android 13.
 */
object SecureWindow {

    /**
     * Keeps [activity]'s window hidden while the security mode is on - and from its first frame
     * until the settings are read, which may say it is - following every change for as long as
     * it lives. Called in onCreate.
     */
    fun follow(activity: ComponentActivity) {
        val graph = AmethystApp.graph(activity)
        apply(activity, SecurityMode.hidesWindows(graph.loadedSettings.value))
        activity.lifecycleScope.launch {
            graph.loadedSettings.map(SecurityMode::hidesWindows).distinctUntilChanged().collect { hide ->
                apply(activity, hide)
            }
        }
    }

    private fun apply(activity: ComponentActivity, hide: Boolean) {
        if (hide) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
