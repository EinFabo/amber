package app.amethyst

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.amethyst.core.privacy.SecurityMode
import java.util.WeakHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Amber's own windows out of screenshots, screen recordings and the Recents picture, with
 * FLAG_SECURE ([SecurityMode.hidesWindow]): always in the security mode, and while a screen shows
 * dictated words ([app.amethyst.ui.ShowsDictatedText]) - on every version, where setRecentsScreenshotEnabled only
 * exists from Android 13 and Android 9 to 12 keep the Recents picture as a file. Elsewhere
 * screenshots stay possible: they are how a problem gets reported.
 */
object SecureWindow {

    /** How many parts of an activity's screen show dictated words now. Main thread only. */
    private val showing = WeakHashMap<Activity, MutableStateFlow<Int>>()

    private fun showingIn(activity: Activity): MutableStateFlow<Int> = showing.getOrPut(activity) { MutableStateFlow(0) }

    /**
     * Keeps [activity]'s window hidden while [SecurityMode.hidesWindow] says so - from its first
     * frame until the settings are read, which may say the mode is on - following every change
     * for as long as it lives. Called in onCreate.
     */
    fun follow(activity: ComponentActivity) {
        val graph = AmethystApp.graph(activity)
        val shown = showingIn(activity)
        apply(activity, SecurityMode.hidesWindow(graph.loadedSettings.value, shown.value > 0))
        activity.lifecycleScope.launch {
            combine(graph.loadedSettings, shown) { settings, count -> SecurityMode.hidesWindow(settings, count > 0) }
                .distinctUntilChanged()
                .collect { hide -> apply(activity, hide) }
        }
    }

    /**
     * Part of [activity]'s screen shows dictated words from now on: the window is hidden until
     * the returned call says it no longer does - and shown again once no part does. Main thread;
     * from a screen through [app.amethyst.ui.ShowsDictatedText].
     */
    fun showsDictatedText(activity: Activity): () -> Unit {
        val shown = showingIn(activity)
        shown.update { it + 1 }
        return { shown.update { it - 1 } }
    }

    /** The activity [context] belongs to, if any. */
    tailrec fun activityOf(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> activityOf(context.baseContext)
        else -> null
    }

    private fun apply(activity: ComponentActivity, hide: Boolean) {
        if (hide) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
